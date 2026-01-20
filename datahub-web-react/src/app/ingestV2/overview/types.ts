export type WorkflowOverviewResponse = {
    provider?: string | null;
    region?: string | null;
    generatedAt: number;
    executionLimit: number;
    totalWorkflows: number;
    error?: string | null;
    workflows: WorkflowOverview[];
};

export type WorkflowOverview = {
    provider?: string | null;
    id: string;
    name: string;
    status?: string | null;
    type?: string | null;
    createdAt?: number | null;
    error?: string | null;
    executions: WorkflowExecutionOverview[];
};

export type WorkflowExecutionOverview = {
    arn: string;
    name?: string | null;
    status?: string | null;
    startTime?: number | null;
    stopTime?: number | null;
    durationMs?: number | null;
    error?: string | null;
    cause?: string | null;
};

export type WorkflowDetailResponse = {
    provider?: string | null;
    id: string;
    name?: string | null;
    status?: string | null;
    type?: string | null;
    createdAt?: number | null;
    definition?: string | null;
    graph?: WorkflowGraph | null;
    executions: WorkflowExecutionOverview[];
    error?: string | null;
};

export type WorkflowGraph = {
    startAt?: string | null;
    nodes: WorkflowNode[];
    edges: WorkflowEdge[];
};

export type WorkflowNode = {
    id: string;
    label?: string | null;
    type?: string | null;
    resource?: string | null;
    resourceType?: string | null;
    resourceUrl?: string | null;
};

export type WorkflowEdge = {
    from: string;
    to: string;
    type?: string | null;
};

export type WorkflowExecutionDetailResponse = {
    provider?: string | null;
    executionArn: string;
    status?: string | null;
    startTime?: number | null;
    stopTime?: number | null;
    durationMs?: number | null;
    error?: string | null;
    cause?: string | null;
    logsError?: string | null;
    logsUrl?: string | null;
    stateStatuses: WorkflowStateStatus[];
    logs: WorkflowLogEvent[];
    taskLogs?: WorkflowTaskLog[] | null;
};

export type WorkflowStateStatus = {
    stateName: string;
    status?: string | null;
    lastUpdated?: number | null;
    startTime?: number | null;
    endTime?: number | null;
};

export type WorkflowLogEvent = {
    timestamp?: number | null;
    message?: string | null;
};

export type WorkflowTaskLog = {
    stateName: string;
    status?: string | null;
    resourceType?: string | null;
    resource?: string | null;
    resourceUrl?: string | null;
    logGroup?: string | null;
    logStream?: string | null;
    logUrl?: string | null;
    logsError?: string | null;
    logs?: WorkflowLogEvent[] | null;
};
