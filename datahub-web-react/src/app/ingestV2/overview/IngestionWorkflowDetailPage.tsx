import { PlayCircleOutlined, ReloadOutlined } from '@ant-design/icons';
import { Button, PageTitle, Tooltip } from '@components';
import { Alert, Empty, List, Tag, Typography, message } from 'antd';
import React, { useCallback, useEffect, useMemo, useState } from 'react';
import { useLocation } from 'react-router-dom';
import ReactFlow, { Background, Controls, Edge, MarkerType, Node, Position } from 'reactflow';
import 'reactflow/dist/style.css';
import styled from 'styled-components';

import { formatDateTime } from '@app/ingestV2/shared/components/columns/DateTimeColumn';
import { formatDuration, toRelativeTimeString } from '@app/shared/time/timeUtils';
import { useShowNavBarRedesign } from '@app/useShowNavBarRedesign';
import { resolveRuntimePath } from '@utils/runtimeBasePath';

import {
    WorkflowDetailResponse,
    WorkflowExecutionDetailResponse,
    WorkflowExecutionOverview,
    WorkflowGraph,
    WorkflowStateStatus,
    WorkflowTaskLog,
} from './types';
import { DEFAULT_WORKFLOW_PROVIDER } from './workflowProviders';

const DEFAULT_LIMIT = 15;
const POLL_INTERVAL_MS = 5000;

const STATUS_COLOR_MAP: Record<string, string> = {
    SUCCEEDED: '#2ECC71',
    FAILED: '#D64541',
    TIMED_OUT: '#F39C12',
    ABORTED: '#C0392B',
    RUNNING: '#2D8CFF',
};

const getStatusColor = (status?: string | null) => {
    if (!status) return '#98A2B3';
    return STATUS_COLOR_MAP[status] || '#98A2B3';
};

const isFailureStatus = (status?: string | null) =>
    status === 'FAILED' || status === 'TIMED_OUT' || status === 'ABORTED';

const isRunningStatus = (status?: string | null) => status === 'RUNNING';

const PageContainer = styled.div<{ $isShowNavBarRedesign?: boolean }>`
    padding-top: 20px;
    background-color: white;
    border-radius: ${(props) =>
        props.$isShowNavBarRedesign ? props.theme.styles['border-radius-navbar-redesign'] : '8px'};
    ${(props) =>
        props.$isShowNavBarRedesign &&
        `
        overflow: hidden;
        margin: 5px;
        box-shadow: ${props.theme.styles['box-shadow-navbar-redesign']};
        height: 100%;
    `}
`;

const PageHeaderContainer = styled.div`
    && {
        padding-left: 20px;
        padding-right: 20px;
        display: flex;
        flex-direction: row;
        justify-content: space-between;
        align-items: center;
        margin-bottom: 16px;
        gap: 16px;
        flex-wrap: wrap;
    }
`;

const DetailContent = styled.div`
    display: grid;
    grid-template-columns: minmax(400px, 2fr) minmax(320px, 1fr);
    gap: 16px;
    margin: 0 20px 20px 20px;
    height: calc(100% - 80px);
`;

const GraphPanel = styled.div`
    border: 1px solid #e4e7ec;
    border-radius: 8px;
    padding: 8px;
    min-height: 500px;
    background: #fafafa;
    .react-flow__handle {
        opacity: 0;
        pointer-events: none;
    }
    .react-flow__edge-path {
        stroke: #98a2b3;
        stroke-width: 1.2;
    }
`;

const NodeCard = styled.div<{ $statusColor?: string | null; $isTerminal?: boolean }>`
    min-width: 180px;
    max-width: 260px;
    border-radius: 10px;
    border: 1px solid ${(props) => props.$statusColor || '#d0d5dd'};
    background: #fff;
    padding: 10px 12px;
    box-shadow: ${(props) => (props.$isTerminal ? '0 2px 8px rgba(16, 24, 40, 0.12)' : 'none')};
    display: flex;
    align-items: center;
    gap: 10px;
    overflow: hidden;
`;

const NodeLink = styled.a`
    text-decoration: none;
    cursor: pointer;
    display: inline-flex;
    color: inherit;
    pointer-events: auto;
`;

const NodeIcon = styled.div<{ $bg: string }>`
    width: 28px;
    height: 28px;
    border-radius: 6px;
    background: ${(props) => props.$bg};
    color: #fff;
    font-size: 12px;
    font-weight: 700;
    display: flex;
    align-items: center;
    justify-content: center;
`;

const NodeMeta = styled.div`
    display: flex;
    flex-direction: column;
    gap: 2px;
`;

const NodeLabel = styled.div`
    font-weight: 600;
    font-size: 13px;
    color: #101828;
    max-width: 180px;
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
`;

const NodeType = styled.div`
    font-size: 11px;
    color: #667085;
    text-transform: capitalize;
`;

const StartEndNode = styled.div<{ $fill: string }>`
    width: 44px;
    height: 44px;
    border-radius: 999px;
    background: ${(props) => props.$fill};
    border: 1px solid #d0d5dd;
    display: flex;
    align-items: center;
    justify-content: center;
    font-size: 12px;
    font-weight: 700;
    color: #344054;
`;

const SidePanel = styled.div`
    display: flex;
    flex-direction: column;
    gap: 16px;
    min-height: 0;
    overflow-y: auto;
`;

const PanelCard = styled.div`
    border: 1px solid #e4e7ec;
    border-radius: 8px;
    padding: 12px;
    background: #ffffff;
`;

const PanelHeader = styled.div`
    display: flex;
    align-items: center;
    justify-content: space-between;
    margin-bottom: 8px;
`;

const TaskLogItem = styled.div`
    border: 1px solid #eaecf0;
    border-radius: 8px;
    padding: 10px;
    display: flex;
    flex-direction: column;
    gap: 8px;
`;

const TaskLogHeader = styled.div`
    display: flex;
    align-items: center;
    gap: 8px;
    flex-wrap: wrap;
`;

const SectionTitle = styled(Typography.Text)`
    font-weight: 600;
`;

const RunMeta = styled.div`
    display: flex;
    flex-direction: column;
    gap: 4px;
    margin-top: 8px;
`;

const LogBlock = styled.pre`
    background: #101828;
    color: #f2f4f7;
    padding: 12px;
    border-radius: 6px;
    max-height: 220px;
    overflow: auto;
    font-size: 12px;
`;

const useQueryParams = () => {
    const location = useLocation();
    return useMemo(() => new URLSearchParams(location.search), [location.search]);
};

const buildStatusMap = (statuses?: WorkflowStateStatus[]) => {
    const map = new Map<string, string>();
    (statuses || []).forEach((status) => {
        if (status?.stateName) {
            map.set(status.stateName, status.status || '');
        }
    });
    return map;
};

const nodeTypeStyles: Record<string, { label: string; color: string }> = {
    task: { label: 'Task', color: '#F2994A' },
    choice: { label: 'Choice', color: '#2D9CDB' },
    map: { label: 'Map', color: '#9B51E0' },
    parallel: { label: 'Parallel', color: '#BB6BD9' },
    pass: { label: 'Pass', color: '#27AE60' },
    wait: { label: 'Wait', color: '#56CCF2' },
    succeed: { label: 'Succeed', color: '#27AE60' },
    fail: { label: 'Fail', color: '#EB5757' },
};

const getNodeTypeStyle = (type?: string | null) => {
    const key = (type || '').toLowerCase();
    return nodeTypeStyles[key] || { label: type || 'State', color: '#98A2B3' };
};

const buildReactFlowGraph = (graph: WorkflowGraph | null | undefined, statusMap: Map<string, string>) => {
    if (!graph || !graph.nodes?.length) {
        return { nodes: [] as Node[], edges: [] as Edge[] };
    }

    const adjacency = new Map<string, string[]>();
    const reverseAdjacency = new Map<string, string[]>();
    graph.nodes.forEach((node) => {
        adjacency.set(node.id, []);
        reverseAdjacency.set(node.id, []);
    });
    graph.edges.forEach((edge) => {
        if (!adjacency.has(edge.from)) adjacency.set(edge.from, []);
        adjacency.get(edge.from)?.push(edge.to);
        if (!reverseAdjacency.has(edge.to)) reverseAdjacency.set(edge.to, []);
        reverseAdjacency.get(edge.to)?.push(edge.from);
    });

    const startNode = graph.startAt || graph.nodes[0].id;
    const mainPath: string[] = [];
    const visitedMain = new Set<string>();
    let current: string | undefined = startNode;
    while (current && !visitedMain.has(current)) {
        visitedMain.add(current);
        mainPath.push(current);
        const outgoing = graph.edges.filter((edge) => edge.from === current);
        if (!outgoing.length) break;
        const nextEdge =
            outgoing.find((edge) => edge.type === 'default') ||
            outgoing.find((edge) => edge.type === 'next') ||
            outgoing[0];
        current = nextEdge?.to;
    }

    const mainIndex = new Map<string, number>();
    mainPath.forEach((id, index) => mainIndex.set(id, index));

    const columnMap = new Map<string, number>();
    const depthMap = new Map<string, number>();
    const anchorMap = new Map<string, number>();
    let nextColumn = 1;

    const assignBranch = (root: string, column: number, anchorIndex: number) => {
        const queue: Array<{ id: string; depth: number }> = [{ id: root, depth: 1 }];
        while (queue.length) {
            const { id, depth } = queue.shift() as { id: string; depth: number };
            if (mainIndex.has(id)) continue;
            if (!columnMap.has(id)) {
                columnMap.set(id, column);
                depthMap.set(id, depth);
                anchorMap.set(id, anchorIndex);
            }
            const children = adjacency.get(id) || [];
            children.forEach((child) => {
                if (mainIndex.has(child)) return;
                if (!columnMap.has(child)) {
                    queue.push({ id: child, depth: depth + 1 });
                }
            });
        }
    };

    mainPath.forEach((id, index) => {
        const outgoing = graph.edges.filter((edge) => edge.from === id);
        outgoing.forEach((edge) => {
            if (mainIndex.has(edge.to) || columnMap.has(edge.to)) return;
            const column = nextColumn;
            nextColumn += 1;
            assignBranch(edge.to, column, index);
        });
    });

    graph.nodes.forEach((node) => {
        if (mainIndex.has(node.id)) return;
        if (!columnMap.has(node.id)) {
            columnMap.set(node.id, 1);
            depthMap.set(node.id, 1);
            anchorMap.set(node.id, 0);
        }
    });

    const outgoingCounts = new Map<string, number>();
    graph.nodes.forEach((node) => outgoingCounts.set(node.id, 0));
    graph.edges.forEach((edge) => {
        outgoingCounts.set(edge.from, (outgoingCounts.get(edge.from) || 0) + 1);
    });
    const terminalNodes = graph.nodes.filter((node) => (outgoingCounts.get(node.id) || 0) === 0).map((n) => n.id);

    const positions = new Map<string, { x: number; y: number }>();
    const xSpacing = 260;
    const ySpacing = 140;
    const levels: number[] = [];

    graph.nodes.forEach((node) => {
        let column = 0;
        let level = 0;
        if (mainIndex.has(node.id)) {
            column = 0;
            level = (mainIndex.get(node.id) || 0) * 2;
        } else {
            column = columnMap.get(node.id) || 1;
            const anchor = anchorMap.get(node.id) || 0;
            const depth = depthMap.get(node.id) || 1;
            level = anchor * 2 + depth;
        }
        positions.set(node.id, { x: column * xSpacing, y: level * ySpacing });
        levels.push(level);
    });

    const startId = '__start__';
    const endId = '__end__';
    const minLevel = levels.length ? Math.min(...levels) : 0;
    const maxLevel = levels.length ? Math.max(...levels) : 0;
    const startLevel = (mainIndex.get(startNode) || 0) * 2 - 1;
    positions.set(startId, { x: 0, y: (startLevel || minLevel - 1) * ySpacing });
    if (terminalNodes.length) {
        positions.set(endId, { x: 0, y: (maxLevel + 1) * ySpacing });
    }

    const nodes: Node[] = graph.nodes.map((node) => {
        const status = statusMap.get(node.id);
        const statusColor = status ? getStatusColor(status) : '#D0D5DD';
        const typeStyle = getNodeTypeStyle(node.type);
        const card = (
            <NodeCard $statusColor={statusColor}>
                <NodeIcon $bg={typeStyle.color}>{typeStyle.label.slice(0, 2).toUpperCase()}</NodeIcon>
                <NodeMeta>
                    <NodeLabel title={node.label || node.id}>{node.label || node.id}</NodeLabel>
                    <NodeType>{typeStyle.label}</NodeType>
                </NodeMeta>
            </NodeCard>
        );
        const label = node.resourceUrl ? (
            <NodeLink
                href={node.resourceUrl}
                target="_blank"
                rel="noopener noreferrer"
                className="nodrag nopan"
                role="link"
                onPointerDown={(event) => {
                    event.stopPropagation();
                }}
                onClick={(event) => {
                    event.preventDefault();
                    event.stopPropagation();
                    window.open(node.resourceUrl as string, '_blank', 'noopener,noreferrer');
                }}
            >
                {card}
            </NodeLink>
        ) : (
            card
        );

        return {
            id: node.id,
            data: {
                label,
                resourceUrl: node.resourceUrl,
            },
            position: positions.get(node.id) || { x: 0, y: 0 },
            sourcePosition: Position.Bottom,
            targetPosition: Position.Top,
            draggable: false,
            selectable: false,
            style: {
                background: 'transparent',
                border: 'none',
                cursor: node.resourceUrl ? 'pointer' : 'default',
            },
        };
    });

    const edges: Edge[] = graph.edges.map((edge, index) => {
        const label =
            edge.type === 'choice'
                ? 'Choice'
                : edge.type === 'default'
                ? 'Default'
                : edge.type === 'iterator'
                ? 'Iterator'
                : undefined;
        return {
            id: `${edge.from}-${edge.to}-${index}`,
            source: edge.from,
            target: edge.to,
            type: 'step',
            animated: false,
            label,
            labelBgPadding: [6, 4],
            labelBgBorderRadius: 6,
            labelBgStyle: { fill: '#ffffff' },
        };
    });

    nodes.push({
        id: startId,
        data: { label: <StartEndNode $fill="#FDF2E9">Start</StartEndNode> },
        position: positions.get(startId) || { x: 0, y: 0 },
        sourcePosition: Position.Bottom,
        targetPosition: Position.Top,
        draggable: false,
        selectable: false,
        style: { background: 'transparent', border: 'none' },
    });

    edges.push({
        id: `${startId}-${startNode}-start`,
        source: startId,
        target: startNode,
        type: 'step',
        style: { stroke: '#667085' },
    });

    if (terminalNodes.length) {
        nodes.push({
            id: endId,
            data: { label: <StartEndNode $fill="#FDEBD0">End</StartEndNode> },
            position: positions.get(endId) || { x: 0, y: 0 },
            sourcePosition: Position.Bottom,
            targetPosition: Position.Top,
            draggable: false,
            selectable: false,
            style: { background: 'transparent', border: 'none' },
        });
        terminalNodes.forEach((terminal, index) => {
            edges.push({
                id: `${terminal}-${endId}-end-${index}`,
                source: terminal,
                target: endId,
                type: 'step',
                style: { stroke: '#667085' },
            });
        });
    }

    return { nodes, edges };
};

export default function IngestionWorkflowDetailPage() {
    const isShowNavBarRedesign = useShowNavBarRedesign();
    const query = useQueryParams();
    const workflowId = query.get('workflowId') || '';
    const provider = query.get('provider') || DEFAULT_WORKFLOW_PROVIDER;

    const [detail, setDetail] = useState<WorkflowDetailResponse | null>(null);
    const [detailLoading, setDetailLoading] = useState(false);
    const [detailError, setDetailError] = useState<string | null>(null);
    const [selectedExecutionArn, setSelectedExecutionArn] = useState<string | null>(null);
    const [executionDetail, setExecutionDetail] = useState<WorkflowExecutionDetailResponse | null>(null);
    const [executionLoading, setExecutionLoading] = useState(false);
    const [logsRequested, setLogsRequested] = useState(false);

    const fetchDetail = useCallback(async () => {
        if (!workflowId) {
            setDetailError('Missing workflow id.');
            return;
        }
        setDetailLoading(true);
        setDetailError(null);
        try {
            const params = new URLSearchParams();
            params.set('provider', provider);
            params.set('workflowId', workflowId);
            params.set('limit', String(DEFAULT_LIMIT));
            const response = await fetch(resolveRuntimePath(`/api/ingestion/workflows/detail?${params.toString()}`));
            const payload = (await response.json()) as WorkflowDetailResponse;
            if (!response.ok) {
                throw new Error(payload?.error || `Request failed (${response.status})`);
            }
            if (payload?.error) {
                setDetailError(payload.error);
            }
            setDetail(payload);
        } catch (e: any) {
            setDetailError(e?.message || 'Failed to load workflow detail');
            setDetail(null);
        } finally {
            setDetailLoading(false);
        }
    }, [provider, workflowId]);

    const runWorkflow = useCallback(async () => {
        if (!workflowId) return;
        try {
            const response = await fetch(resolveRuntimePath('/api/ingestion/workflows/run'), {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ provider, workflowId }),
            });
            const payload = await response.json().catch(() => ({}));
            if (!response.ok || payload?.status === 'failed') {
                throw new Error(payload?.message || payload?.error || `Run failed (${response.status})`);
            }
            message.success(`Ingestion started (${payload?.runId || 'ok'})`);
        } catch (e) {
            message.error((e as Error)?.message || 'Failed to start ingestion');
        } finally {
            fetchDetail();
        }
    }, [fetchDetail, provider, workflowId]);

    const selectedExecution: WorkflowExecutionOverview | undefined = useMemo(() => {
        if (!detail?.executions?.length) return undefined;
        return detail.executions.find((execution) => execution.arn === selectedExecutionArn) || detail.executions[0];
    }, [detail, selectedExecutionArn]);

    const fetchExecutionDetail = useCallback(
        async (includeLogs: boolean) => {
            if (!selectedExecution?.arn) return;
            if (includeLogs) {
                setLogsRequested(true);
            }
            setExecutionLoading(true);
            try {
                const params = new URLSearchParams();
                params.set('provider', provider);
                params.set('executionArn', selectedExecution.arn);
                params.set('maxEvents', '500');
                if (includeLogs) {
                    params.set('includeLogs', 'true');
                    params.set('logLimit', '200');
                }
                const response = await fetch(
                    resolveRuntimePath(`/api/ingestion/workflows/execution?${params.toString()}`),
                );
                const payload = (await response.json()) as WorkflowExecutionDetailResponse;
                if (!response.ok) {
                    throw new Error(payload?.error || `Request failed (${response.status})`);
                }
                setExecutionDetail(payload);
            } catch (e) {
                // swallow and keep last detail
            } finally {
                setExecutionLoading(false);
            }
        },
        [provider, selectedExecution?.arn],
    );

    useEffect(() => {
        fetchDetail();
    }, [fetchDetail]);

    useEffect(() => {
        if (!selectedExecutionArn && detail?.executions?.length) {
            setSelectedExecutionArn(detail.executions[0].arn);
        }
    }, [detail, selectedExecutionArn]);

    useEffect(() => {
        if (selectedExecution?.arn) {
            setLogsRequested(false);
        }
    }, [selectedExecution?.arn]);

    useEffect(() => {
        if (!selectedExecution?.arn) return;
        fetchExecutionDetail(true);
    }, [fetchExecutionDetail, selectedExecution?.arn]);

    useEffect(() => {
        if (!selectedExecution?.arn || !executionDetail?.status) return;
        if (isFailureStatus(executionDetail.status) && !logsRequested) {
            fetchExecutionDetail(true);
        }
    }, [executionDetail?.status, fetchExecutionDetail, logsRequested, selectedExecution?.arn]);

    useEffect(() => {
        if (!selectedExecution?.arn) return;
        if (!executionDetail || !isRunningStatus(executionDetail.status)) return;
        const intervalId = window.setInterval(() => {
            fetchExecutionDetail(false);
        }, POLL_INTERVAL_MS);
        return () => window.clearInterval(intervalId);
    }, [executionDetail?.status, fetchExecutionDetail, selectedExecution?.arn]);

    const statusMap = useMemo(
        () => buildStatusMap(executionDetail?.stateStatuses),
        [executionDetail?.stateStatuses],
    );
    const { nodes, edges } = useMemo(
        () => buildReactFlowGraph(detail?.graph, statusMap),
        [detail?.graph, statusMap],
    );
    const handleNodeClick = useCallback((event: React.MouseEvent, node: Node) => {
        const resourceUrl = (node.data as { resourceUrl?: string | null } | undefined)?.resourceUrl;
        if (resourceUrl) {
            event.stopPropagation();
            window.open(resourceUrl, '_blank', 'noopener,noreferrer');
        }
    }, []);

    return (
        <PageContainer $isShowNavBarRedesign={isShowNavBarRedesign}>
            <PageHeaderContainer>
                <PageTitle
                    title={detail?.name || 'Workflow Detail'}
                    subTitle="Real-time execution view of ingestion workflows"
                />
                <div style={{ display: 'flex', gap: 8 }}>
                    <Button onClick={fetchDetail} icon={<ReloadOutlined />} loading={detailLoading}>
                        Refresh
                    </Button>
                    <Button onClick={runWorkflow} icon={<PlayCircleOutlined />} disabled={!workflowId}>
                        Run ingestion
                    </Button>
                </div>
            </PageHeaderContainer>
            <DetailContent>
                <GraphPanel>
                    {detailError && <Alert type="error" message={detailError} showIcon style={{ marginBottom: 12 }} />}
                    {!detailLoading && !detail?.graph?.nodes?.length && (
                        <Empty description="No workflow graph available" />
                    )}
                    {detail?.graph?.nodes?.length ? (
                        <ReactFlow
                            nodes={nodes}
                            edges={edges}
                            fitView
                            nodesConnectable={false}
                            nodesDraggable={false}
                            onNodeClick={handleNodeClick}
                            noPanClassName="nopan"
                        >
                            <Background />
                            <Controls />
                        </ReactFlow>
                    ) : null}
                </GraphPanel>
                <SidePanel>
                    <PanelCard>
                        <PanelHeader>
                            <SectionTitle>Runs</SectionTitle>
                            <Typography.Text type="secondary">
                                {detail?.executions?.length || 0} total
                            </Typography.Text>
                        </PanelHeader>
                        {!detail?.executions?.length && <Empty description="No executions found" />}
                        {detail?.executions?.length ? (
                            <List
                                dataSource={detail.executions}
                                renderItem={(execution) => (
                                    <List.Item
                                        style={{
                                            cursor: 'pointer',
                                            borderRadius: 6,
                                            padding: '8px 6px',
                                            background:
                                                execution.arn === selectedExecution?.arn ? '#F2F4F7' : 'transparent',
                                        }}
                                        onClick={() => setSelectedExecutionArn(execution.arn)}
                                    >
                                        <div style={{ width: '100%' }}>
                                            <div style={{ display: 'flex', justifyContent: 'space-between' }}>
                                                <Tag color={getStatusColor(execution.status)}>{execution.status}</Tag>
                                                <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                                                    {execution.startTime ? toRelativeTimeString(execution.startTime) : '-'}
                                                </Typography.Text>
                                            </div>
                                            <Typography.Text style={{ fontSize: 12 }}>
                                                {execution.name || execution.arn}
                                            </Typography.Text>
                                            {execution.error && (
                                                <Typography.Text type="danger" style={{ fontSize: 12 }}>
                                                    {execution.error}
                                                </Typography.Text>
                                            )}
                                        </div>
                                    </List.Item>
                                )}
                            />
                        ) : null}
                    </PanelCard>
                    <PanelCard>
                        <PanelHeader>
                            <SectionTitle>Run Details</SectionTitle>
                            {executionLoading && <Typography.Text type="secondary">Loading…</Typography.Text>}
                        </PanelHeader>
                        {selectedExecution ? (
                            <>
                                <Tag color={getStatusColor(executionDetail?.status || selectedExecution.status)}>
                                    {executionDetail?.status || selectedExecution.status || 'UNKNOWN'}
                                </Tag>
                                <RunMeta>
                                    <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                                        Started:{' '}
                                        {selectedExecution.startTime ? (
                                            <Tooltip title={formatDateTime(selectedExecution.startTime)}>
                                                {toRelativeTimeString(selectedExecution.startTime)}
                                            </Tooltip>
                                        ) : (
                                            '-'
                                        )}
                                    </Typography.Text>
                                    <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                                        Duration:{' '}
                                        {selectedExecution.durationMs != null
                                            ? formatDuration(selectedExecution.durationMs)
                                            : '-'}
                                    </Typography.Text>
                                    {executionDetail?.error && (
                                        <Typography.Text type="danger" style={{ fontSize: 12 }}>
                                            {executionDetail.error}
                                        </Typography.Text>
                                    )}
                                    {executionDetail?.cause && (
                                        <Typography.Text style={{ fontSize: 12 }}>
                                            {executionDetail.cause}
                                        </Typography.Text>
                                    )}
                                </RunMeta>
                            </>
                        ) : (
                            <Empty description="Select a run to see details" />
                        )}
                    </PanelCard>
                    <PanelCard>
                        <PanelHeader>
                            <SectionTitle>Task Logs</SectionTitle>
                            <Typography.Text type="secondary">
                                {executionDetail?.taskLogs?.length || 0} tasks
                            </Typography.Text>
                        </PanelHeader>
                        {!executionDetail?.taskLogs?.length && <Empty description="No task logs available" />}
                        {executionDetail?.taskLogs?.length
                            ? executionDetail.taskLogs.map((task: WorkflowTaskLog) => (
                                  <TaskLogItem key={task.stateName || task.resource || task.logGroup || 'task-log'}>
                                      <TaskLogHeader>
                                          <Tag color={getStatusColor(task.status)}>{task.status || 'UNKNOWN'}</Tag>
                                          <Typography.Text>{task.stateName || 'Unnamed task'}</Typography.Text>
                                          {task.resourceType && (
                                              <Tag color="blue">{task.resourceType.replace('_', ' ')}</Tag>
                                          )}
                                      </TaskLogHeader>
                                      <div style={{ display: 'flex', gap: 12, flexWrap: 'wrap' }}>
                                          {task.resourceUrl && (
                                              <Typography.Link href={task.resourceUrl} target="_blank">
                                                  Open compute
                                              </Typography.Link>
                                          )}
                                          {task.logUrl && (
                                              <Typography.Link href={task.logUrl} target="_blank">
                                                  Open logs
                                              </Typography.Link>
                                          )}
                                      </div>
                                      {task.logsError && (
                                          <Alert type="warning" message={task.logsError} showIcon />
                                      )}
                                      {!task.logs?.length && !task.logsError && (
                                          <Empty description="No logs available" />
                                      )}
                                      {task.logs?.length ? (
                                          <LogBlock>
                                              {task.logs
                                                  .map((log) => {
                                                      const ts = log.timestamp ? formatDateTime(log.timestamp) : '';
                                                      return `${ts} ${log.message || ''}`.trim();
                                                  })
                                                  .join('\n')}
                                          </LogBlock>
                                      ) : null}
                                  </TaskLogItem>
                              ))
                            : null}
                    </PanelCard>
                </SidePanel>
            </DetailContent>
        </PageContainer>
    );
}
