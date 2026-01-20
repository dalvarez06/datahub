import { PlayCircleOutlined } from '@ant-design/icons';
import { Button, PageTitle, Tooltip } from '@components';
import { Alert, Empty, Input, Select, Table, Tag, Typography, message } from 'antd';
import type { ColumnsType } from 'antd/es/table';
import React, { useCallback, useEffect, useMemo, useState } from 'react';
import { Link } from 'react-router-dom';
import styled from 'styled-components';

import { formatDateTime } from '@app/ingestV2/shared/components/columns/DateTimeColumn';
import { formatDuration, toRelativeTimeString } from '@app/shared/time/timeUtils';
import { useShowNavBarRedesign } from '@app/useShowNavBarRedesign';
import { PageRoutes } from '@conf/Global';
import { resolveRuntimePath } from '@utils/runtimeBasePath';

import { WorkflowExecutionOverview, WorkflowOverview, WorkflowOverviewResponse } from './types';
import { DEFAULT_WORKFLOW_PROVIDER } from './workflowProviders';

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

const PageContentContainer = styled.div`
    display: flex;
    flex-direction: column;
    gap: 16px;
    flex: 1;
    margin: 0 20px 20px 20px;
    height: calc(100% - 80px);
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

const ControlsRow = styled.div`
    display: flex;
    align-items: center;
    gap: 12px;
    flex-wrap: wrap;
`;

const SummaryRow = styled.div`
    display: flex;
    align-items: center;
    gap: 16px;
    flex-wrap: wrap;
`;

const SummaryItem = styled.div`
    display: flex;
    align-items: baseline;
    gap: 8px;
`;

const SummaryLabel = styled(Typography.Text)`
    color: #667085;
`;

const SummaryValue = styled(Typography.Text)`
    font-weight: 600;
`;

const StatusDots = styled.div`
    display: flex;
    align-items: center;
    gap: 4px;
    flex-wrap: nowrap;
`;

const StatusDot = styled.span<{ $color: string }>`
    width: 8px;
    height: 8px;
    border-radius: 50%;
    display: inline-block;
    background-color: ${(props) => props.$color};
`;

const ExecutionList = styled.div`
    display: grid;
    gap: 8px;
`;

const ExecutionRow = styled.div`
    display: grid;
    grid-template-columns: 140px 140px 1fr 120px;
    gap: 12px;
    align-items: center;
`;

const ExecutionCell = styled.div`
    font-size: 12px;
    color: #475467;
`;

const ExecutionName = styled(Typography.Text)`
    font-size: 12px;
    color: #1d2939;
`;

const DEFAULT_LIMIT = 15;
const REFRESH_INTERVAL_MS = 60_000;

const STATUS_COLOR_MAP: Record<string, string> = {
    SUCCEEDED: '#12B76A',
    FAILED: '#F04438',
    TIMED_OUT: '#F79009',
    ABORTED: '#D92D20',
    RUNNING: '#2E90FA',
};

const getStatusColor = (status?: string | null) => {
    if (!status) return '#98A2B3';
    return STATUS_COLOR_MAP[status] || '#98A2B3';
};

const isFailureStatus = (status?: string | null) =>
    status === 'FAILED' || status === 'TIMED_OUT' || status === 'ABORTED';

const getLatestExecution = (executions: WorkflowExecutionOverview[]) => executions?.[0];

const getFailureCount = (executions: WorkflowExecutionOverview[]) =>
    executions?.filter((execution) => isFailureStatus(execution.status)).length || 0;

type WorkflowRow = WorkflowOverview & {
    key: string;
    latest?: WorkflowExecutionOverview;
    failureCount: number;
};

export default function IngestionOverviewPage() {
    const isShowNavBarRedesign = useShowNavBarRedesign();
    const [limit, setLimit] = useState(DEFAULT_LIMIT);
    const [query, setQuery] = useState('');
    const [data, setData] = useState<WorkflowOverviewResponse | null>(null);
    const [loading, setLoading] = useState(false);
    const [error, setError] = useState<string | null>(null);

    const runWorkflow = useCallback(async (workflowId: string, provider?: string | null) => {
        if (!workflowId) {
            message.error('Missing workflow id');
            return;
        }
        try {
            const response = await fetch(resolveRuntimePath('/api/ingestion/workflows/run'), {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({
                    provider: provider || DEFAULT_WORKFLOW_PROVIDER,
                    workflowId,
                }),
            });
            const payload = await response.json().catch(() => ({}));
            if (!response.ok || payload?.status === 'failed') {
                message.error(payload?.message || payload?.error || `Run failed (${response.status})`);
                return;
            }
            message.success(`Ingestion started (${payload?.runId || 'ok'})`);
        } catch (e: any) {
            message.error(e?.message || 'Failed to start ingestion');
        }
    }, []);

    const fetchOverview = useCallback(
        async (forceRefresh?: boolean) => {
            setLoading(true);
            setError(null);
            try {
                const params = new URLSearchParams();
                params.set('provider', DEFAULT_WORKFLOW_PROVIDER);
                params.set('limit', String(limit));
                if (forceRefresh) {
                    params.set('refresh', 'true');
                }
                const response = await fetch(
                    resolveRuntimePath(`/api/ingestion/workflows?${params.toString()}`),
                );
                const payload = (await response.json()) as WorkflowOverviewResponse;
                if (!response.ok) {
                    throw new Error(payload?.error || `Request failed (${response.status})`);
                }
                if (payload?.error) {
                    setError(payload.error);
                }
                setData(payload);
            } catch (e: any) {
                setError(e?.message || 'Failed to load workflow overview');
                setData(null);
            } finally {
                setLoading(false);
            }
        },
        [limit],
    );

    useEffect(() => {
        fetchOverview();
        const intervalId = setInterval(() => fetchOverview(), REFRESH_INTERVAL_MS);
        return () => clearInterval(intervalId);
    }, [fetchOverview]);

    const filteredStateMachines = useMemo(() => {
        const workflows = data?.workflows || [];
        if (!query) return workflows;
        const lowered = query.toLowerCase();
        return workflows.filter((machine) => machine.name.toLowerCase().includes(lowered));
    }, [data, query]);

    const rows = useMemo<WorkflowRow[]>(() => {
        return filteredStateMachines
            .map((machine) => {
                const executions = machine.executions || [];
                return {
                    key: machine.id,
                    ...machine,
                    latest: getLatestExecution(executions),
                    failureCount: getFailureCount(executions),
                };
            })
            .sort((a, b) => {
                const aTime = a.latest?.startTime || 0;
                const bTime = b.latest?.startTime || 0;
                return bTime - aTime;
            });
    }, [filteredStateMachines]);

    const totalFailures = useMemo(() => {
        return filteredStateMachines.reduce((count, machine) => {
            return count + getFailureCount(machine.executions || []);
        }, 0);
    }, [filteredStateMachines]);

    const columns: ColumnsType<WorkflowRow> = [
        {
            title: 'Workflow',
            dataIndex: 'name',
            key: 'name',
            render: (_: string, record: WorkflowOverview) => (
                <div>
                    <Typography.Text strong>
                        <Link
                            to={`${PageRoutes.INGESTION_OVERVIEW_DETAIL}?provider=${
                                record.provider || DEFAULT_WORKFLOW_PROVIDER
                            }&workflowId=${encodeURIComponent(record.id)}`}
                        >
                            {record.name}
                        </Link>
                    </Typography.Text>
                    <div>
                        <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                            {record.type || 'Unknown'} · {record.status || 'Unknown'}
                        </Typography.Text>
                    </div>
                </div>
            ),
        },
        {
            title: 'Latest status',
            dataIndex: 'latest',
            key: 'latest',
            render: (_: WorkflowExecutionOverview | undefined, record: WorkflowRow) => {
                const status = record.latest?.status || 'NO RUNS';
                return <Tag color={getStatusColor(status)}>{status}</Tag>;
            },
        },
        {
            title: 'Last run',
            dataIndex: 'latest',
            key: 'lastRun',
            render: (_: WorkflowExecutionOverview | undefined, record: WorkflowRow) => {
                const lastStart = record.latest?.startTime;
                if (!lastStart) return '-';
                const relativeTime = toRelativeTimeString(lastStart) || '-';
                const fullTime = formatDateTime(lastStart);
                return (
                    <Tooltip title={fullTime}>
                        <Typography.Text>{relativeTime}</Typography.Text>
                    </Tooltip>
                );
            },
        },
        {
            title: 'Failures (last 15)',
            dataIndex: 'failureCount',
            key: 'failureCount',
            render: (value: number) => (
                <Typography.Text style={{ color: value > 0 ? '#F04438' : undefined }}>{value}</Typography.Text>
            ),
        },
        {
            title: 'Recent runs',
            dataIndex: 'executions',
            key: 'recent',
            render: (executions: WorkflowExecutionOverview[]) => (
                <StatusDots>
                    {(executions || []).map((execution) => (
                        <Tooltip
                            key={execution.arn}
                            title={`${execution.status || 'UNKNOWN'} · ${
                                execution.startTime ? formatDateTime(execution.startTime) : 'Unknown time'
                            }`}
                        >
                            <StatusDot $color={getStatusColor(execution.status)} />
                        </Tooltip>
                    ))}
                </StatusDots>
            ),
        },
        {
            title: 'Actions',
            key: 'actions',
            render: (_: unknown, record: WorkflowRow) => (
                <div style={{ display: 'flex', gap: 8 }}>
                    <Button
                        size="sm"
                        onClick={(event) => {
                            event.stopPropagation();
                            runWorkflow(record.id, record.provider);
                        }}
                        icon={<PlayCircleOutlined />}
                    >
                        Run
                    </Button>
                    <Link
                        to={`${PageRoutes.INGESTION_OVERVIEW_DETAIL}?provider=${
                            record.provider || DEFAULT_WORKFLOW_PROVIDER
                        }&workflowId=${encodeURIComponent(record.id)}`}
                    >
                        Details
                    </Link>
                </div>
            ),
        },
    ];

    return (
        <PageContainer $isShowNavBarRedesign={isShowNavBarRedesign}>
            <PageHeaderContainer>
                <PageTitle title="Ingestion Overview" subTitle="Monitor workflow executions across ingestion jobs" />
                <ControlsRow>
                    <Input
                        placeholder="Filter by name"
                        value={query}
                        onChange={(event) => setQuery(event.target.value)}
                        allowClear
                        style={{ width: 220 }}
                    />
                    <Select
                        value={limit}
                        onChange={(value) => setLimit(value)}
                        options={[5, 10, 15, 20, 30, 50].map((value) => ({
                            value,
                            label: `Last ${value} runs`,
                        }))}
                        style={{ width: 140 }}
                    />
                    <Button onClick={() => fetchOverview(true)} loading={loading}>
                        Refresh
                    </Button>
                </ControlsRow>
            </PageHeaderContainer>
            <PageContentContainer>
                <SummaryRow>
                    <SummaryItem>
                        <SummaryLabel>Total workflows</SummaryLabel>
                        <SummaryValue>{data?.totalWorkflows ?? 0}</SummaryValue>
                    </SummaryItem>
                    <SummaryItem>
                        <SummaryLabel>Failures in view</SummaryLabel>
                        <SummaryValue style={{ color: totalFailures > 0 ? '#F04438' : undefined }}>
                            {totalFailures}
                        </SummaryValue>
                    </SummaryItem>
                    <SummaryItem>
                        <SummaryLabel>Region</SummaryLabel>
                        <SummaryValue>{data?.region || 'default'}</SummaryValue>
                    </SummaryItem>
                    <SummaryItem>
                        <SummaryLabel>Updated</SummaryLabel>
                        <SummaryValue>
                            {data?.generatedAt ? toRelativeTimeString(data.generatedAt) : '—'}
                        </SummaryValue>
                    </SummaryItem>
                </SummaryRow>
                {error && <Alert type="error" message={error} showIcon />}
                {!loading && rows.length === 0 && <Empty description="No workflows found" />}
                <Table
                    dataSource={rows}
                    columns={columns}
                    loading={loading}
                    pagination={false}
                    expandable={{
                        expandedRowRender: (record: WorkflowRow) => {
                            if (!record.executions?.length) {
                                return <Typography.Text type="secondary">No executions found.</Typography.Text>;
                            }
                            return (
                                <ExecutionList>
                                    {record.executions.map((execution: WorkflowExecutionOverview) => {
                                        const start = execution.startTime;
                                        const duration =
                                            execution.durationMs != null
                                                ? formatDuration(execution.durationMs)
                                                : undefined;
                                        return (
                                            <ExecutionRow key={execution.arn}>
                                                <ExecutionCell>
                                                    <Tag color={getStatusColor(execution.status)}>
                                                        {execution.status || 'UNKNOWN'}
                                                    </Tag>
                                                </ExecutionCell>
                                                <ExecutionCell>
                                                    {start ? (
                                                        <Tooltip title={formatDateTime(start)}>
                                                            <span>{toRelativeTimeString(start) || '-'}</span>
                                                        </Tooltip>
                                                    ) : (
                                                        '-'
                                                    )}
                                                </ExecutionCell>
                                                <ExecutionName>{execution.name || execution.arn}</ExecutionName>
                                                <ExecutionCell>{duration || '-'}</ExecutionCell>
                                            </ExecutionRow>
                                        );
                                    })}
                                </ExecutionList>
                            );
                        },
                        rowExpandable: (record) => record.executions?.length > 0,
                    }}
                />
            </PageContentContainer>
        </PageContainer>
    );
}
