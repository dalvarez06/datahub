# DataHub Ingestion Trigger Notes (Local)

This doc captures the current state of the "Run ingestion" flow we added to DataHub so it can be resumed in a new session.

## Goal

- Add a dataset UI button that triggers ingestion.
- Use DataHub auth on the backend (GMS).
- Prefer Step Functions ARN stored on the DataJob entity (from OpenLineage job facet).
- Fall back to dataset->Lambda mapping if no job ARN is present.

## What Was Added

### Backend (GMS)

New endpoint:

- `POST /api/ingestion/run`

Implementation:

- `metadata-service/graphql-servlet-impl/src/main/java/com/datahub/graphql/ingestion/IngestionLambdaController.java`

Behavior:

1) Validates `datasetUrn`.
2) If `jobUrn` is provided, fetches the DataJob's structured properties and looks for the property URN set in `DATAHUB_INGESTION_JOB_ARN_PROPERTY_URN`.
3) If found, starts a Step Functions execution using that ARN.
4) If not found, falls back to dataset->Lambda mapping and invokes Lambda asynchronously.

### Frontend (UI)

Dataset header button:

- `datahub-web-react/src/app/entity/dataset/profile/RunIngestionButton.tsx`
- Uses `getDatasetRuns` to grab the latest run's parent job URN and POSTs it with the dataset URN to `/api/ingestion/run`.

Wiring:

- `datahub-web-react/src/app/entity/dataset/DatasetEntity.tsx` adds the button to the dataset header.
- `datahub-web-react/src/app/entity/shared/containers/profile/EntityProfile.tsx` and
  `datahub-web-react/src/app/entity/shared/containers/profile/header/EntityHeader.tsx` accept and render a custom header action button.

### Dependencies

Added AWS SDK v2 Step Functions:

- `metadata-service/graphql-servlet-impl/build.gradle`
- `metadata-service/graphql-servlet-impl/gradle.lockfile`

## Request Contract

Request body:

- `datasetUrn` (required)
- `jobUrn` (optional, DataJob URN from dataset run)
- `reason` (optional)

Response:

- `status`: `started` or `failed`
- `runId`: execution ARN or request id
- `message`: error details if failed

## Env Vars (GMS)

Used by the endpoint:

- `DATAHUB_INGESTION_JOB_ARN_PROPERTY_URN` (structured property URN storing the Step Functions ARN on DataJobs)
- `DATAHUB_INGESTION_LAMBDA_MAPPING` (inline JSON map of dataset URN -> lambda name)
- `DATAHUB_INGESTION_LAMBDA_MAPPING_FILE` (path to JSON mapping file)
- `DATAHUB_INGESTION_LAMBDA_DEFAULT` (default Lambda name fallback)
- `DATAHUB_INGESTION_LAMBDA_REGION` (AWS region; falls back to `AWS_REGION` or `AWS_DEFAULT_REGION`)

## OpenLineage Job Facet -> DataHub Structured Property (Pending)

We plan to send OL events with a custom job facet, e.g.

```json
"job": {
  "facets": {
    "aws": {
      "stepFunctionArn": "arn:aws:states:us-east-1:123456789012:stateMachine:earnings-ingest"
    }
  }
}
```

The missing part is mapping that facet into a DataHub structured property on the DataJob. That is done in the OpenLineage converter.

Relevant converter code:

- `metadata-integration/java/openlineage-converter/src/main/java/io/datahubproject/openlineage/converter/OpenLineageToDataHub.java`
  - Add logic in `convertJobToDataJob(...)` to extract `job.facets.aws.stepFunctionArn`.
- `metadata-integration/java/openlineage-converter/src/main/java/io/datahubproject/openlineage/dataset/DatahubJob.java`
  - Emit a `StructuredProperties` aspect on the DataJob with the ARN as a value.

Once mapped, GMS will pick it up using `DATAHUB_INGESTION_JOB_ARN_PROPERTY_URN`.

## Create Structured Property (Programmatic)

GraphQL mutation:

```graphql
mutation createStructuredProperty($input: CreateStructuredPropertyInput!) {
  createStructuredProperty(input: $input) {
    urn
  }
}
```

Variables:

```json
{
  "input": {
    "id": "awsStepFunctionArn",
    "qualifiedName": "awsStepFunctionArn",
    "displayName": "AWS Step Function ARN",
    "description": "Step Functions state machine ARN for this job",
    "immutable": false,
    "valueType": "urn:li:dataType:datahub.string",
    "cardinality": "SINGLE",
    "entityTypes": ["urn:li:entityType:datahub.dataJob"]
  }
}
```

Example curl:

```bash
curl -X POST 'http://localhost:8080/api/graphql' \
  -H 'Content-Type: application/json' \
  -H 'Authorization: Bearer <DATAHUB_ACCESS_TOKEN>' \
  -d '{
    "query": "mutation createStructuredProperty($input: CreateStructuredPropertyInput!) { createStructuredProperty(input: $input) { urn } }",
    "variables": {
      "input": {
        "id": "awsStepFunctionArn",
        "qualifiedName": "awsStepFunctionArn",
        "displayName": "AWS Step Function ARN",
        "description": "Step Functions state machine ARN for this job",
        "immutable": false,
        "valueType": "urn:li:dataType:datahub.string",
        "cardinality": "SINGLE",
        "entityTypes": ["urn:li:entityType:datahub.dataJob"]
      }
    }
  }'
```

## UI Behavior

- The dataset header shows a "Run ingestion" button.
- It uses the latest dataset run's parent DataJob URN (if present) and sends it to the backend.
- Success or error is shown via toast.

## Files Touched

- `metadata-service/graphql-servlet-impl/src/main/java/com/datahub/graphql/ingestion/IngestionLambdaController.java`
- `metadata-service/graphql-servlet-impl/build.gradle`
- `metadata-service/graphql-servlet-impl/gradle.lockfile`
- `datahub-web-react/src/app/entity/dataset/profile/RunIngestionButton.tsx`
- `datahub-web-react/src/app/entity/dataset/DatasetEntity.tsx`
- `datahub-web-react/src/app/entity/shared/containers/profile/EntityProfile.tsx`
- `datahub-web-react/src/app/entity/shared/containers/profile/header/EntityHeader.tsx`
- `DATAHUB_UI_LAMBDA_INGESTION.md`

## Next Steps

1) Implement the OpenLineage converter mapping for the `job.facets.aws.stepFunctionArn` -> DataJob structured property.
2) Rebuild and run GMS + frontend images, set env vars.
3) Verify by clicking the UI button and checking Step Functions executions.
