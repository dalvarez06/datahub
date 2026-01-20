# Hybrid dbt/Athena datasets (dbt_athena)

## Goal
Create a single **hybrid dataset entity** that shows:
- dbt logic (View Definition, compiled SQL, dbt metadata)
- columns + column-level lineage (from dbt schema metadata)

We keep the original dbt and Athena nodes, and add a new **hybrid platform** so you can use the hybrid nodes in the UI without breaking existing ingestion flows.

## What was added
- `../money/ingestion/dbt_datahub_hybrid.yml`
  - A separate dbt ingestion config for the hybrid approach (kept alongside the original config).
- `../money/scripts/ingest_dbt_datahub_hybrid.py`
  - A script that creates **hybrid dataset nodes** on a new platform (`dbt_athena` by default).
  - It copies dbt aspects onto the hybrid entity:
    - `datasetProperties`
    - `schemaMetadata`
    - `viewProperties`
    - `globalTags`
    - `upstreamLineage` (rewritten to point at hybrid URNs)
    - plus standard `datasetKey`, `status`, `subTypes`, `browsePathsV2`, `dataPlatformInstance`

## How the hybrid entity is formed
The script reads the dbt manifest and catalog, then for each dbt model:
1. Resolves the dbt dataset URN (handles `AwsDataCatalog` vs `awsdatacatalog` casing).
2. Creates a **hybrid dataset URN** on platform `dbt_athena` with the target (Athena) name.
3. Copies dbt aspects onto the hybrid entity.
4. Rewrites lineage (and fine‑grained lineage) to point at hybrid URNs.

Result: the hybrid node contains dbt logic + columns in one place.

## Where to find the hybrid nodes
Hybrid nodes live under platform `dbt_athena`.
Example URN:
```
urn:li:dataset:(urn:li:dataPlatform:dbt_athena,awsathena://athena.us-west-2.amazonaws.com.awsdatacatalog.financial_data.options_average,DEV)
```

## Run order (hybrid)
1. Run dbt ingestion (original config).
2. Run hybrid ingestion script.

Commands:
```
uv run --with "acryl-datahub[dbt]" python ../money/scripts/ingest_dbt_datahub_latest.py
DATAHUB_HYBRID_PLATFORM=dbt_athena \
  uv run --with "acryl-datahub[dbt]" python ../money/scripts/ingest_dbt_datahub_hybrid.py
```

## Notes
- The hybrid platform name is configurable via `DATAHUB_HYBRID_PLATFORM`.
- Hybrid nodes are new entities, not true merges of dbt + Athena nodes.
- UI search: filter platform to `dbt_athena`.
