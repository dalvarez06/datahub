import { useApolloClient } from '@apollo/client';
import React, { useContext, useEffect, useMemo, useState } from 'react';
import { ReactFlowProvider } from 'reactflow';

import { useGetLineageTimeParams } from '@app/lineage/utils/useGetLineageTimeParams';
import LineageDisplay from '@app/lineageV3/LineageDisplay';
import {
    FetchStatus,
    LINEAGE_FILTER_PAGINATION,
    LineageEntity,
    LineageNodesContext,
    addToAdjacencyList,
    generateIgnoreAsHops,
    getEdgeId,
    isQuery,
    useIgnoreSchemaFieldStatus,
} from '@app/lineageV3/common';
import useShouldHideTransformations from '@app/lineageV3/settings/useShouldHideTransformations';
import useShouldShowDataProcessInstances from '@app/lineageV3/settings/useShouldShowDataProcessInstances';
import useShouldShowGhostEntities from '@app/lineageV3/settings/useShouldShowGhostEntities';
import { addQueryNodes, setEntityNodeDefault } from '@app/lineageV3/queries/useSearchAcrossLineage';
import pruneAllDuplicateEdges from '@app/lineageV3/queries/pruneAllDuplicateEdges';
import { DEGREE_FILTER_NAME } from '@app/search/utils/constants';

import { SearchAcrossLineageStructureDocument } from '@graphql/search.generated';
import { EntityType, LineageDirection, SearchAcrossLineageInput } from '@types';
import { DEFAULT_SEARCH_FLAGS } from '@app/lineageV3/queries/useSearchAcrossLineage';

type RootNode = {
    urn: string;
    type: EntityType;
};

type Props = {
    roots: RootNode[];
    maxDepth?: boolean;
    skipCache?: boolean;
};

const PER_HOP_LIMIT = 2;

export default function MultiRootLineageGraph({ roots, maxDepth = true, skipCache = false }: Props) {
    const rootType = roots[0]?.type || EntityType.Dataset;
    const rootUrn = roots[0]?.urn || '';
    const rootUrns = useMemo(() => roots.map((root) => root.urn), [roots]);

    const [nodes] = useState(new Map<string, LineageEntity>());
    const [edges] = useState(new Map());
    const [adjacencyList] = useState({
        [LineageDirection.Upstream]: new Map(),
        [LineageDirection.Downstream]: new Map(),
    });
    const [nodeVersion, setNodeVersion] = useState(0);
    const [dataVersion, setDataVersion] = useState(0);
    const [columnEdgeVersion, setColumnEdgeVersion] = useState(0);
    const [displayVersion, setDisplayVersion] = useState<[number, string[]]>([0, []]);
    const [hideTransformations, setHideTransformations] = useShouldHideTransformations();
    const [showDataProcessInstances, setShowDataProcessInstances] = useShouldShowDataProcessInstances();
    const [showGhostEntities, setShowGhostEntities] = useShouldShowGhostEntities(rootType);

    const context = {
        rootUrn,
        rootUrns,
        rootType,
        nodes,
        edges,
        adjacencyList,
        nodeVersion,
        setNodeVersion,
        dataVersion,
        setDataVersion,
        displayVersion,
        setDisplayVersion,
        columnEdgeVersion,
        setColumnEdgeVersion,
        hideTransformations,
        setHideTransformations,
        showDataProcessInstances,
        setShowDataProcessInstances,
        showGhostEntities,
        setShowGhostEntities,
    };

    return (
        <LineageNodesContext.Provider value={context}>
            <MultiRootImpactAnalysisNodeInitializer
                roots={roots}
                maxDepth={maxDepth}
                skipCache={skipCache}
            />
        </LineageNodesContext.Provider>
    );
}

function MultiRootImpactAnalysisNodeInitializer({
    roots,
    maxDepth,
    skipCache,
}: {
    roots: RootNode[];
    maxDepth: boolean;
    skipCache: boolean;
}) {
    const initialized = useInitializeNodes(roots, maxDepth, skipCache);

    return (
        <ReactFlowProvider>
            <LineageDisplay initialized={initialized} />
        </ReactFlowProvider>
    );
}

function useInitializeNodes(roots: RootNode[], maxDepth: boolean, skipCache: boolean): boolean {
    const context = useContext(LineageNodesContext);
    const client = useApolloClient();
    const { startTimeMillis, endTimeMillis } = useGetLineageTimeParams();
    const { nodes, adjacencyList, edges, setNodeVersion, setDisplayVersion, showGhostEntities } = context;
    const ignoreSchemaFieldStatus = useIgnoreSchemaFieldStatus();

    const [initialized, setInitialized] = useState(false);
    const contextForFetch = useMemo(
        () => ({ nodes, edges, adjacencyList, setDisplayVersion, setNodeVersion }),
        [nodes, edges, adjacencyList, setDisplayVersion, setNodeVersion],
    );
    const rootsKey = useMemo(
        () => roots.map((root) => `${root.urn}:${root.type}`).sort().join('|'),
        [roots],
    );

    useEffect(() => {
        nodes.clear();
        adjacencyList[LineageDirection.Upstream].clear();
        adjacencyList[LineageDirection.Downstream].clear();
        edges.clear();

        roots.forEach((root) => {
            nodes.set(root.urn, makeInitialNode(root.urn, root.type));
        });

        setNodeVersion(0);
        setDisplayVersion([0, roots.map((root) => root.urn)]);
    }, [rootsKey, startTimeMillis, endTimeMillis, nodes, adjacencyList, edges, setNodeVersion, setDisplayVersion, roots]);

    const hasSchemaFieldRoot = useMemo(
        () => roots.some((root) => root.type === EntityType.SchemaField),
        [roots],
    );

    useEffect(() => {
        if (hasSchemaFieldRoot && ignoreSchemaFieldStatus) return;
        adjacencyList[LineageDirection.Upstream].clear();
        adjacencyList[LineageDirection.Downstream].clear();
        edges.clear();
        nodes.forEach((node) => {
            // eslint-disable-next-line no-param-reassign
            node.entity = undefined;
        });
        setDisplayVersion(([version]) => [version + 1, []]);
    }, [showGhostEntities, ignoreSchemaFieldStatus, hasSchemaFieldRoot, nodes, adjacencyList, edges, setDisplayVersion]);

    useEffect(() => {
        let cancelled = false;

        async function fetchAllLineage() {
            if (!roots.length) {
                setInitialized(true);
                return;
            }

            setInitialized(false);

            const tasks = roots.flatMap((root) => [
                fetchLineageForRoot(
                    client,
                    root,
                    LineageDirection.Upstream,
                    maxDepth,
                    skipCache,
                    startTimeMillis,
                    endTimeMillis,
                    contextForFetch,
                ),
                fetchLineageForRoot(
                    client,
                    root,
                    LineageDirection.Downstream,
                    maxDepth,
                    skipCache,
                    startTimeMillis,
                    endTimeMillis,
                    contextForFetch,
                ),
            ]);

            await Promise.all(tasks);

            if (!cancelled) {
                roots.forEach((root) => {
                    const node = nodes.get(root.urn);
                    if (!node) return;
                    node.fetchStatus = {
                        ...node.fetchStatus,
                        [LineageDirection.Upstream]: FetchStatus.COMPLETE,
                        [LineageDirection.Downstream]: FetchStatus.COMPLETE,
                    };
                });
                setNodeVersion((version) => version + 1);
                setDisplayVersion(([version]) => [version + 1, []]);
                setInitialized(true);
            }
        }

        fetchAllLineage();

        return () => {
            cancelled = true;
        };
    }, [
        rootsKey,
        maxDepth,
        skipCache,
        startTimeMillis,
        endTimeMillis,
        roots.length,
        client,
        contextForFetch,
        showGhostEntities,
    ]);

    return initialized;
}

async function fetchLineageForRoot(
    client: ReturnType<typeof useApolloClient>,
    root: RootNode,
    direction: LineageDirection,
    maxDepth: boolean,
    skipCache: boolean,
    startTimeMillis: number | null | undefined,
    endTimeMillis: number | null | undefined,
    context: {
        nodes: React.ContextType<typeof LineageNodesContext>['nodes'];
        edges: React.ContextType<typeof LineageNodesContext>['edges'];
        adjacencyList: React.ContextType<typeof LineageNodesContext>['adjacencyList'];
        setDisplayVersion: React.ContextType<typeof LineageNodesContext>['setDisplayVersion'];
        setNodeVersion: React.ContextType<typeof LineageNodesContext>['setNodeVersion'];
    },
) {
    const input: SearchAcrossLineageInput = {
        urn: root.urn,
        direction,
        start: 0,
        count: 10000,
        orFilters: [
            {
                and: [
                    {
                        field: DEGREE_FILTER_NAME,
                        values: maxDepth ? ['1', '2', '3+'] : ['1'],
                    },
                ],
            },
        ],
        lineageFlags: {
            startTimeMillis: startTimeMillis || undefined,
            endTimeMillis: endTimeMillis || undefined,
            entitiesExploredPerHopLimit: maxDepth ? PER_HOP_LIMIT : undefined,
            ignoreAsHops: generateIgnoreAsHops(root.type),
        },
        searchFlags: {
            ...DEFAULT_SEARCH_FLAGS,
            skipCache,
        },
    };

    const result = await client.query({
        query: SearchAcrossLineageStructureDocument,
        variables: { input },
        fetchPolicy: skipCache ? 'no-cache' : undefined,
    });

    applySearchAcrossLineageResults(result.data?.searchAcrossLineage, root, direction, context);
}

function applySearchAcrossLineageResults(
    results: {
        searchResults?: Array<{
            entity: { urn: string; type: EntityType };
            paths?: Array<{ path: Array<{ urn: string; type: EntityType }> } | null> | null;
            explored: boolean;
            ignoredAsHop: boolean;
        }>;
    } | null | undefined,
    root: RootNode,
    direction: LineageDirection,
    context: {
        nodes: React.ContextType<typeof LineageNodesContext>['nodes'];
        edges: React.ContextType<typeof LineageNodesContext>['edges'];
        adjacencyList: React.ContextType<typeof LineageNodesContext>['adjacencyList'];
        setDisplayVersion: React.ContextType<typeof LineageNodesContext>['setDisplayVersion'];
        setNodeVersion: React.ContextType<typeof LineageNodesContext>['setNodeVersion'];
    },
) {
    if (!results?.searchResults?.length) {
        const rootNode = context.nodes.get(root.urn);
        if (rootNode) {
            rootNode.fetchStatus = { ...rootNode.fetchStatus, [direction]: FetchStatus.COMPLETE };
        }
        return;
    }

    const { nodes, edges, adjacencyList, setDisplayVersion, setNodeVersion } = context;
    const smallContext = { nodes, edges, adjacencyList, setDisplayVersion, rootType: root.type };
    let addedNode = false;

    results.searchResults.forEach((result) => {
        addedNode = addedNode || !nodes.has(result.entity.urn);
        const node = setEntityNodeDefault(result.entity.urn, result.entity.type, direction, {
            nodes,
            rootType: root.type,
        });
        if (result.explored || result.ignoredAsHop) {
            node.fetchStatus = { ...node.fetchStatus, [direction]: FetchStatus.COMPLETE };
            node.isExpanded = { ...node.isExpanded, [direction]: true };
        }

        result.paths?.forEach((path) => {
            if (!path) return;
            const parent = path.path[path.path.length - 2];
            if (!parent) return;
            if (isQuery(parent)) {
                const grandparent = path.path[path.path.length - 3];
                if (grandparent) {
                    edges.set(getEdgeId(grandparent.urn, result.entity.urn, direction), { isDisplayed: true });
                    addToAdjacencyList(adjacencyList, direction, grandparent.urn, result.entity.urn);
                }
            } else {
                edges.set(getEdgeId(parent.urn, result.entity.urn, direction), { isDisplayed: true });
                addToAdjacencyList(adjacencyList, direction, parent.urn, result.entity.urn);
            }

            addQueryNodes(path.path, direction, smallContext);
        });
    });

    const rootNode = nodes.get(root.urn);
    if (rootNode) {
        rootNode.fetchStatus = { ...rootNode.fetchStatus, [direction]: FetchStatus.COMPLETE };
    }

    pruneAllDuplicateEdges(root.urn, direction, smallContext);

    if (addedNode) {
        setNodeVersion((version) => version + 1);
    }
    setDisplayVersion(([version]) => [version + 1, []]);
}

function makeInitialNode(urn: string, type: EntityType): LineageEntity {
    return {
        id: urn,
        urn,
        type,
        direction: LineageDirection.Upstream,
        isExpanded: {
            [LineageDirection.Upstream]: true,
            [LineageDirection.Downstream]: true,
        },
        fetchStatus: {
            [LineageDirection.Upstream]: FetchStatus.LOADING,
            [LineageDirection.Downstream]: FetchStatus.LOADING,
        },
        filters: {
            [LineageDirection.Upstream]: { limit: LINEAGE_FILTER_PAGINATION, facetFilters: new Map() },
            [LineageDirection.Downstream]: { limit: LINEAGE_FILTER_PAGINATION, facetFilters: new Map() },
        },
    };
}
