export type WorkflowProviderConfig = {
    id: string;
    label: string;
    supportsRun: boolean;
    supportsLogs: boolean;
};

export const WORKFLOW_PROVIDERS: Record<string, WorkflowProviderConfig> = {
    aws_stepfunctions: {
        id: 'aws_stepfunctions',
        label: 'AWS Step Functions',
        supportsRun: true,
        supportsLogs: true,
    },
    gcp_workflows: {
        id: 'gcp_workflows',
        label: 'GCP Workflows',
        supportsRun: false,
        supportsLogs: false,
    },
};

export const DEFAULT_WORKFLOW_PROVIDER = 'aws_stepfunctions';

export const getWorkflowProviderConfig = (id?: string | null) =>
    WORKFLOW_PROVIDERS[id || DEFAULT_WORKFLOW_PROVIDER] || WORKFLOW_PROVIDERS[DEFAULT_WORKFLOW_PROVIDER];
