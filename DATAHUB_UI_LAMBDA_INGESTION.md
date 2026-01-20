# DataHub UI Lambda Trigger: Detailed Plan, Prompt, and Checklist

Goal
- Add a DataHub UI button that triggers an ingestion Lambda (or similar job) via a backend endpoint.
- Keep AWS credentials and Lambda details off the browser.
- Produce a repeatable, low-friction process for local development and future updates.

Scope
- Local dev setup using DataHub Docker Compose.
- Two valid architectures:
  - A) GMS endpoint (tight integration, rebuild datahub-gms)
  - B) Sidecar API (smaller change, rebuild datahub-frontend only)

Use this doc as:
- A prompt to guide implementation
- A checklist to ensure completion

---

## Quick Summary (for prompt reuse)

We need to add a DataHub UI button on the dataset page that triggers an ingestion Lambda. The browser must never see AWS creds or Lambda ARNs. Build a backend endpoint that calls Lambda using boto3 and return a run ID. The UI should call that endpoint with the dataset URN. Use a mapping from dataset URN (or dataset tags) to the Lambda name. Rebuild the frontend image and (if needed) gms. Update docker-compose to use the custom images. Include minimal auth and logging.

---

## Prerequisites

- DataHub running locally via Docker Compose.
- Access to DataHub monorepo source.
- AWS credentials available to the backend (env vars or local profile).
- A list of Lambda functions to invoke.
- Optional: a static mapping file from dataset URN to Lambda.

---

## Architecture Options

### Option A: GMS Endpoint (recommended for tight integration)
Pros:
- Uses DataHub auth context.
- Single backend service.
Cons:
- Requires rebuilding datahub-gms.

### Option B: Sidecar API (faster iteration)
Pros:
- Small change surface.
- Rebuild frontend only.
Cons:
- Separate service and routing.

Decision:
- Choose Option A if you want deeper integration with DataHub auth.
- Choose Option B if you want the minimal backend changes.

---

## Data Contract (UI -> Backend)

Endpoint:
- POST /api/ingestion/run

Request body:
- datasetUrn: string (DataHub dataset URN)
- jobUrn: string (DataHub data job URN, optional)
- optional: reason or trigger metadata

Response body:
- runId: string
- status: started | failed
- message: optional

Example:
```
POST /api/ingestion/run
{
  "datasetUrn": "urn:li:dataset:(urn:li:dataPlatform:athena,AwsDataCatalog.financial_data.us_stocks_sip_v2,PROD)",
  "jobUrn": "urn:li:dataJob:(urn:li:dataFlow:(airflow,example_flow,PROD),example_job)"
}

Response:
{
  "runId": "lambda-2025-01-01T12:34:56Z",
  "status": "started"
}
```

---

## Mapping Strategy (Dataset -> Lambda)

Pick one:
- Static mapping file: datasetUrn -> lambdaName
- Tag-based mapping: tag layer/source -> lambdaName
- Prefix mapping: database or schema -> lambdaName

Recommended for local:
- Static mapping file (simple to debug)

Example mapping (YAML or JSON):
```
{
  "urn:li:dataset:(urn:li:dataPlatform:athena,AwsDataCatalog.financial_data.us_stocks_sip_v2,PROD)": "earnings-lambda",
  "urn:li:dataset:(urn:li:dataPlatform:athena,AwsDataCatalog.financial_data.polygon_prices,PROD)": "polygon-lambda"
}
```

---

## Implementation Checklist

### 1) Source Setup
- [ ] Clone DataHub monorepo (or use existing clone)
- [ ] Check DataHub version/tag in use
- [ ] Identify Docker Compose file used locally

### 2) Backend Endpoint

If GMS endpoint:
- [ ] Locate GMS REST module
- [ ] Add new controller endpoint: POST /api/ingestion/run
- [ ] Load job ARN from DataJob structured properties (optional)
- [ ] Load dataset->lambda mapping from file or env (fallback)
- [ ] Use AWS SDK (Java) to invoke Step Functions or Lambda
- [ ] Return runId + status
- [ ] Add basic logging
- [ ] Configure env vars in GMS container (mapping + region)

If Sidecar API:
- [ ] Create minimal FastAPI/Flask service
- [ ] Expose POST /api/ingestion/run
- [ ] Load dataset->lambda mapping
- [ ] Use boto3 to invoke Lambda
- [ ] Return runId + status
- [ ] Add docker-compose service entry

### 3) Frontend Button
- [ ] Add UI button to dataset page or a new tab
- [ ] Confirm button only shows for relevant datasets
- [ ] Call the backend endpoint with dataset URN
- [ ] Display success/failure message to user

### 4) Docker Images
- [ ] Build custom datahub-frontend image
- [ ] If GMS endpoint: build custom datahub-gms image
- [ ] Update docker-compose to use custom images
- [ ] Restart DataHub stack

### 5) Validate
- [ ] Open dataset in DataHub UI
- [ ] Click “Run Ingestion” button
- [ ] Confirm Lambda invoked (CloudWatch logs)
- [ ] Confirm UI response indicates success

---

## Suggested File Layout

If GMS endpoint:
- datahub-gms/ (new controller and config)
- datahub-frontend/ (button + API client)
- docker-compose override (custom images)

If sidecar:
- services/ingestion-trigger/ (fastapi or flask)
- datahub-frontend/ (button + API client)
- docker-compose override (add sidecar + custom frontend)

---

## Minimal Security Model (Local)

- Do not expose AWS creds to the browser.
- Keep credentials in the backend container only.
- Validate the incoming dataset URN exists and matches mapping.

---

## GMS Env Vars (Local)

Set these on the `datahub-gms` container:

- DATAHUB_INGESTION_LAMBDA_MAPPING: inline JSON map of `datasetUrn -> lambdaName`
- DATAHUB_INGESTION_LAMBDA_MAPPING_FILE: path to JSON mapping file (if not using inline mapping)
- DATAHUB_INGESTION_LAMBDA_DEFAULT: default Lambda to use when no mapping exists
- DATAHUB_INGESTION_LAMBDA_REGION: AWS region (falls back to `AWS_REGION` or `AWS_DEFAULT_REGION`)
- DATAHUB_INGESTION_JOB_ARN_PROPERTY_URN: structured property URN on DataJobs that stores the Step Functions ARN

---

## Common Pitfalls

- Missing AWS creds in backend container
- Dataset URN mismatch between DataHub and mapping
- CORS issues when calling sidecar API from UI
- Not updating docker-compose to custom image tag

---

## Optional Enhancements

- Add a confirmation dialog before triggering
- Add a “last run” status on the dataset page
- Add role-based access to the trigger button

---

## Open Questions (fill in)

- Which DataHub version/tag? __________
- Which backend option? GMS or Sidecar? __________
- How will we map dataset -> Lambda? __________
- Where will the mapping live? file / env / db? __________
- AWS region? __________

---

## Next Action (choose one)

- If GMS endpoint: add a new controller + rebuild gms
- If sidecar: build a small API service and add to compose
