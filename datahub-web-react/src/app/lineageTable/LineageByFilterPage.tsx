import { ArrowDownOutlined, ArrowUpOutlined } from '@ant-design/icons';
import { Button, Drawer, Tooltip, Typography } from 'antd';
import React, { useEffect, useMemo, useState } from 'react';
import { useHistory } from 'react-router';
import styled from 'styled-components';

import { EmbeddedListSearchSection as EmbeddedListSearchSectionV1 } from '@app/entity/shared/components/styled/search/EmbeddedListSearchSection';
import { GetSearchResultsParams } from '@app/entity/shared/components/styled/search/types';
import { ImpactAnalysis as ImpactAnalysisV1 } from '@app/entity/shared/tabs/Lineage/ImpactAnalysis';
import { EmbeddedListSearchSection as EmbeddedListSearchSectionV2 } from '@app/entityV2/shared/components/styled/search/EmbeddedListSearchSection';
import { ImpactAnalysis as ImpactAnalysisV2 } from '@app/entityV2/shared/tabs/Lineage/ImpactAnalysis';
import { useEntityRegistry } from '@app/useEntityRegistry';
import { useIsThemeV2 } from '@app/useIsThemeV2';

import { useGetSearchResultsForMultipleQuery, useLineageSummaryQuery } from '@graphql/search.generated';
import {
    Entity,
    EntityType,
    FacetFilterInput,
    LineageDirection,
    LineageSummary,
    LineageSummaryDirection,
} from '@types';

const DEFAULT_SAMPLE_COUNT = 3;
const FULL_LINEAGE_FILTERS: Array<FacetFilterInput> = [{ field: 'degree', values: ['1', '2', '3+'] }];

const ActionContainer = styled.div`
    margin-left: auto;
    display: flex;
    flex-direction: column;
    gap: 6px;
    min-width: 280px;
    @media (max-width: 768px) {
        margin-left: 0;
        min-width: 0;
        width: 100%;
    }
`;

const DirectionRow = styled.div`
    display: flex;
    align-items: center;
    gap: 8px;
    @media (max-width: 768px) {
        flex-wrap: wrap;
    }
`;

const DirectionButton = styled(Button)`
    &&& {
        padding: 0;
        height: auto;
        display: flex;
        align-items: center;
        gap: 6px;
    }
`;

const DirectionLabel = styled(Typography.Text)`
    font-size: 12px;
`;

const DirectionCount = styled.span`
    font-weight: 600;
`;

const RelationshipPreview = styled(Typography.Text)`
    font-size: 12px;
    color: #7f7f7f;
    max-width: 220px;
    white-space: nowrap;
    overflow: hidden;
    text-overflow: ellipsis;
    @media (max-width: 768px) {
        max-width: 100%;
        white-space: normal;
    }
`;

const DrawerTitle = styled.div`
    display: flex;
    flex-direction: column;
    gap: 2px;
`;

const DrawerSubtitle = styled(Typography.Text)`
    font-size: 12px;
    color: #7f7f7f;
`;

type DrawerState = {
    urn: string;
    direction: LineageDirection;
};

type LineageActionProps = {
    urn: string;
    type: EntityType;
};

const areArraysEqual = (left: string[], right: string[]) => {
    if (left.length !== right.length) return false;
    return left.every((value, index) => value === right[index]);
};

export const LineageByFilterPage = () => {
    const history = useHistory();
    const entityRegistry = useEntityRegistry();
    const isThemeV2 = useIsThemeV2();
    const [currentUrns, setCurrentUrns] = useState<string[]>([]);
    const [entitiesByUrn, setEntitiesByUrn] = useState<Record<string, Entity>>({});
    const [drawerState, setDrawerState] = useState<DrawerState | null>(null);

    const useGetSearchResults = (params: GetSearchResultsParams) => {
        const { data, loading, error, refetch } = useGetSearchResultsForMultipleQuery(params);

        useEffect(() => {
            if (!data?.searchAcrossEntities) {
                setCurrentUrns([]);
                setEntitiesByUrn({});
                return;
            }

            const searchResults = data.searchAcrossEntities.searchResults || [];
            const nextUrns = searchResults.map((result) => result.entity.urn);
            setCurrentUrns((prev) => (areArraysEqual(prev, nextUrns) ? prev : nextUrns));

            const nextEntitiesByUrn = searchResults.reduce((acc, result) => {
                acc[result.entity.urn] = result.entity;
                return acc;
            }, {} as Record<string, Entity>);
            setEntitiesByUrn(nextEntitiesByUrn);
        }, [data]);

        return {
            data: data?.searchAcrossEntities,
            loading,
            error,
            refetch: (refetchParams: GetSearchResultsParams['variables']) =>
                refetch(refetchParams).then((res) => res.data.searchAcrossEntities),
        };
    };

    const summaryUrns = useMemo(() => Array.from(new Set(currentUrns)), [currentUrns]);
    const { data: lineageSummaryData, loading: lineageSummaryLoading } = useLineageSummaryQuery({
        variables: {
            input: {
                urns: summaryUrns,
                sampleCount: DEFAULT_SAMPLE_COUNT,
            },
        },
        skip: summaryUrns.length === 0,
    });

    const summaryByUrn = useMemo(() => {
        const map: Record<string, LineageSummary> = {};
        lineageSummaryData?.lineageSummary?.results?.forEach((summary) => {
            map[summary.urn] = summary;
        });
        return map;
    }, [lineageSummaryData]);

    const getEntityDisplayName = (entity?: Entity | null) => {
        if (!entity) return 'Unknown';
        return entityRegistry.getDisplayName(entity.type, entity);
    };

    const getRelationshipNames = (direction?: LineageSummaryDirection) => {
        if (!direction?.relationships?.length) return [];
        return direction.relationships.map((relationship) => getEntityDisplayName(relationship.entity));
    };

    const buildRelationshipPreview = (direction: LineageSummaryDirection | undefined, label: string) => {
        if (!direction) {
            return {
                preview: lineageSummaryLoading ? 'Loading...' : '--',
                tooltip: undefined,
            };
        }

        if (!direction.total) {
            return {
                preview: `No ${label.toLowerCase()}`,
                tooltip: undefined,
            };
        }

        const names = getRelationshipNames(direction);
        const moreCount = Math.max(direction.total - names.length, 0);
        const basePreview = names.length ? names.join(', ') : 'View details';
        const preview = moreCount > 0 ? `${basePreview} +${moreCount} more` : basePreview;
        return {
            preview,
            tooltip: names.length ? names.join(', ') : undefined,
        };
    };

    const openDrawer = (urn: string, direction: LineageDirection, event: React.MouseEvent) => {
        event.preventDefault();
        event.stopPropagation();
        setDrawerState({ urn, direction });
    };

    const closeDrawer = () => setDrawerState(null);

    const drawerEntity = drawerState ? entitiesByUrn[drawerState.urn] : undefined;
    const drawerTitle = drawerState ? (
        <DrawerTitle>
            <Typography.Text strong>
                {drawerEntity ? getEntityDisplayName(drawerEntity) : drawerState.urn}
            </Typography.Text>
            <DrawerSubtitle>
                {drawerState.direction === LineageDirection.Upstream ? 'Upstream' : 'Downstream'} lineage
            </DrawerSubtitle>
        </DrawerTitle>
    ) : null;

    const handleLineageClick = () => {
        if (!drawerState) return;
        const entity = entitiesByUrn[drawerState.urn];
        if (!entity) return;
        history.push(`${entityRegistry.getEntityUrl(entity.type, entity.urn)}?is_lineage_mode=true`);
    };

    const renderDirectionRow = (
        urn: string,
        direction: LineageDirection,
        directionSummary: LineageSummaryDirection | undefined,
    ) => {
        const label = direction === LineageDirection.Upstream ? 'Upstream' : 'Downstream';
        const countLabel = directionSummary ? directionSummary.total : lineageSummaryLoading ? '...' : '--';
        const { preview, tooltip } = buildRelationshipPreview(directionSummary, label);
        const Icon = direction === LineageDirection.Upstream ? ArrowUpOutlined : ArrowDownOutlined;

        return (
            <DirectionRow>
                <DirectionButton type="text" onClick={(event) => openDrawer(urn, direction, event)}>
                    <Icon />
                    <DirectionLabel>{label}</DirectionLabel>
                    <DirectionCount>{countLabel}</DirectionCount>
                </DirectionButton>
                <Tooltip title={tooltip} placement="topLeft">
                    <RelationshipPreview>{preview}</RelationshipPreview>
                </Tooltip>
            </DirectionRow>
        );
    };

    const LineageAction = ({ urn }: LineageActionProps) => {
        const summary = summaryByUrn[urn];

        return (
            <ActionContainer>
                {renderDirectionRow(urn, LineageDirection.Upstream, summary?.upstream)}
                {renderDirectionRow(urn, LineageDirection.Downstream, summary?.downstream)}
            </ActionContainer>
        );
    };

    const drawerContent = drawerState ? (
        isThemeV2 ? (
            <ImpactAnalysisV2 urn={drawerState.urn} direction={drawerState.direction} defaultFilters={FULL_LINEAGE_FILTERS} />
        ) : (
            <ImpactAnalysisV1
                urn={drawerState.urn}
                direction={drawerState.direction}
                defaultFilters={FULL_LINEAGE_FILTERS}
                onLineageClick={handleLineageClick}
                isLineageTab
            />
        )
    ) : null;

    return (
        <>
            {isThemeV2 ? (
                <EmbeddedListSearchSectionV2
                    emptySearchQuery="*"
                    placeholderText="Search assets and filter to view lineage..."
                    defaultShowFilters
                    applyView
                    useGetSearchResults={useGetSearchResults}
                    entityAction={LineageAction}
                />
            ) : (
                <EmbeddedListSearchSectionV1
                    emptySearchQuery="*"
                    placeholderText="Search assets and filter to view lineage..."
                    defaultShowFilters
                    applyView
                    useGetSearchResults={useGetSearchResults}
                    entityAction={LineageAction}
                />
            )}
            <Drawer title={drawerTitle} placement="right" width={720} onClose={closeDrawer} open={!!drawerState}>
                {drawerContent}
            </Drawer>
        </>
    );
};
