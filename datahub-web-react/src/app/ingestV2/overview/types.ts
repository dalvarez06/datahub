export type StepFunctionsOverviewResponse = {
    region?: string | null;
    generatedAt: number;
    executionLimit: number;
    totalStateMachines: number;
    error?: string | null;
    stateMachines: StateMachineOverview[];
};

export type StateMachineOverview = {
    arn: string;
    name: string;
    status?: string | null;
    type?: string | null;
    createdAt?: number | null;
    error?: string | null;
    executions: ExecutionOverview[];
};

export type ExecutionOverview = {
    arn: string;
    name?: string | null;
    status?: string | null;
    startTime?: number | null;
    stopTime?: number | null;
    durationMs?: number | null;
};
