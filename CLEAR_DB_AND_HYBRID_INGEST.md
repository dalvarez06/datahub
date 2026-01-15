# Clear DataHub DB and re‑ingest for hybrid datasets

This resets the local DataHub quickstart database and re‑ingests dbt plus the hybrid nodes.

## 1) Clear the database
From `../datahub`:
```
docker compose -f docker/quickstart/docker-compose.quickstart.yml \
  -f docker/quickstart/docker-compose.override.local.yml down -v
```

## 2) Restart the stack
```
scripts/restart_datahub_stack.sh
```
Wait until GMS is healthy. The script prints “DataHub GMS is up.” when ready.

## 3) Re‑ingest dbt (baseline)
This uses the standard dbt ingestion config in `../money/ingestion/dbt_datahub.yml`.
```
uv run --with "acryl-datahub[dbt]" python ../money/scripts/ingest_dbt_datahub_latest.py
```

## 4) Ingest hybrid nodes
This creates the new hybrid dataset nodes under platform `dbt_athena`.
```
DATAHUB_HYBRID_PLATFORM=dbt_athena \
  uv run --with "acryl-datahub[dbt]" python ../money/scripts/ingest_dbt_datahub_hybrid.py
```

## Notes
- The money repo is always at `../money`.
- Hybrid nodes are separate entities; originals remain intact.
- If you want a different hybrid platform name, set `DATAHUB_HYBRID_PLATFORM`.
