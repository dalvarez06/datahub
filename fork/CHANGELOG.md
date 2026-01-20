# Fork Changelog (tag-view vs upstream/master)

This file tracks changes on the `tag-view` branch relative to `upstream/master` to make rebases and upstream pulls easier.

## Summary
- 8 commits ahead (2026-01-09 → 2026-01-19).
- Main themes: ingestion trigger + workflow visibility, lineage table by filter, hybrid dbt/Athena datasets, local dev ergonomics.

## Changelog

### Ingestion trigger + workflow visibility (2026-01-09, 2026-01-16, 2026-01-19)
- Added dataset header “Run ingestion” button and backend endpoint `POST /api/ingestion/run` that triggers Step Functions or Lambda without exposing AWS creds to the browser.
  - Why: enable on-demand ingestion from the UI while keeping credentials server-side.
  - Key files:
    - `metadata-service/graphql-servlet-impl/src/main/java/com/datahub/graphql/ingestion/IngestionLambdaController.java`
    - `datahub-web-react/src/app/entity/dataset/profile/RunIngestionButton.tsx`
    - `DATAHUB_UI_LAMBDA_INGESTION.md`
    - `INGESTION_TRIGGER_NOTES.md`
- Added ingestion overview UI for workflow status + run controls.
  - Why: give operators a single view of ingestion workflow health, latest runs, and quick actions.
  - Key files:
    - `datahub-web-react/src/app/ingestV2/overview/IngestionOverviewPage.tsx`
    - `datahub-web-react/src/app/ingestV2/overview/types.ts`
    - `datahub-web-react/src/app/ingestV2/overview/workflowProviders.ts`
    - `datahub-web-react/src/app/SearchRoutes.tsx`
    - `datahub-web-react/src/conf/Global.ts`
- Added workflow detail page with execution graph + CloudWatch log pull.
  - Why: drill into individual workflow runs, visualize steps, and read logs in-app.
  - Key files:
    - `datahub-web-react/src/app/ingestV2/overview/IngestionWorkflowDetailPage.tsx`
    - `metadata-service/graphql-servlet-impl/src/main/java/com/datahub/graphql/ingestion/IngestionLambdaController.java`
    - `metadata-service/graphql-servlet-impl/build.gradle`

### Lineage table by filter + multi-root graph (2026-01-10, 2026-01-11)
- Added GraphQL `lineageSummary` query + resolver to fetch 1-hop upstream/downstream summaries.
  - Why: fast table view without pulling full lineage graphs for every row.
  - Key files:
    - `datahub-graphql-core/src/main/resources/search.graphql`
    - `datahub-graphql-core/src/main/java/com/linkedin/datahub/graphql/resolvers/lineage/LineageSummaryResolver.java`
    - `datahub-graphql-core/src/main/java/com/linkedin/datahub/graphql/GmsGraphQLEngine.java`
- Added `/lineage-table` page with filters, inline summaries, drawer for full lineage, and optional multi-root graph view.
  - Why: see lineage for all filtered assets in a compact table and optionally a combined graph view.
  - Key files:
    - `datahub-web-react/src/app/lineageTable/LineageByFilterPage.tsx`
    - `datahub-web-react/src/app/lineageTable/MultiRootLineageGraph.tsx`
    - `datahub-web-react/src/graphql/search.graphql`
- Readability and layout improvements in lineage V3 graph and embedded search components.
  - Why: clearer multi-root visualization and better table UX.
  - Key files:
    - `datahub-web-react/src/app/lineageV3/*`
    - `datahub-web-react/src/app/entity*/shared/components/styled/search/*`
    - `datahub-web-react/src/app/entity/shared/tabs/Lineage/ImpactAnalysis.tsx`
- Notes/doc: `LINEAGE_TABLE_NOTES.md` (design rationale + merge anchors).

### Hybrid dbt/Athena datasets (2026-01-15, 2026-01-16)
- Hybrid dataset approach documented and wired into ingestion scripts.
  - Why: represent dbt logic + Athena physical tables in one “hybrid” dataset entity without replacing existing nodes.
  - Key files:
    - `HYBRID_DATASET_NOTES.md`
    - `CLEAR_DB_AND_HYBRID_INGEST.md`
    - `metadata-ingestion/src/datahub/ingestion/source/dbt/dbt_common.py`
- UI nav updates to surface hybrid view(s).
  - Why: make the hybrid datasets discoverable in UI navigation.
  - Key files:
    - `datahub-web-react/src/app/homeV2/layout/NavLinksMenu.tsx`
    - `datahub-web-react/src/app/homeV2/layout/navBarRedesign/NavSidebar.tsx`

### Local dev & ops ergonomics (2026-01-09 → 2026-01-16)
- Updated local docker overrides + restart script.
  - Why: smoother local stack lifecycle for the new ingestion/lineage features.
  - Key files:
    - `docker/quickstart/docker-compose.override.local.yml`
    - `scripts/restart_datahub_stack.sh`
    - `docker/profiles/docker-compose.gms.yml`
    - `docker/datahub-gms/env/docker.env`
- Added operator notes for repeatable setup and context.
  - Why: make it easier to re-run or hand off work in the fork.
  - Key files:
    - `DATAHUB_UI_LAMBDA_INGESTION.md`
    - `INGESTION_TRIGGER_NOTES.md`
    - `LINEAGE_TABLE_NOTES.md`
    - `CLEAR_DB_AND_HYBRID_INGEST.md`
    - `HYBRID_DATASET_NOTES.md`

### Minor UX tweaks
- Shorter labels in structured property table view.
  - Key files:
    - `datahub-web-react/src/app/entity/shared/tabs/Properties/StructuredPropertyValue.tsx`
    - `datahub-web-react/src/app/entityV2/shared/tabs/Properties/StructuredPropertyValue.tsx`

## Commit Timeline
- 2026-01-09 `5f4cc2af0f`: ingestion trigger + shorter table names
- 2026-01-10 `de51cd31cf`: lineage table by filter (initial)
- 2026-01-11 `9a065ddd01`: lineage readability + multi-root graph improvements
- 2026-01-12 `71ed33a7a9`: local compose/restart tweaks
- 2026-01-15 `4bac9f1574`: hybrid view nav + notes
- 2026-01-16 `4b213887f6`: hybrid node ingestion updates
- 2026-01-16 `87761ae38c`: ingestion overview page
- 2026-01-19 `56bc10cd5f`: enhanced workflow detail/log views

## Merge Watchlist (likely conflicts when pulling upstream)
- GraphQL schema/resolver
  - `datahub-graphql-core/src/main/resources/search.graphql`
  - `datahub-graphql-core/src/main/java/com/linkedin/datahub/graphql/resolvers/lineage/LineageSummaryResolver.java`
  - `datahub-graphql-core/src/main/java/com/linkedin/datahub/graphql/GmsGraphQLEngine.java`
- GMS ingestion controller + deps
  - `metadata-service/graphql-servlet-impl/src/main/java/com/datahub/graphql/ingestion/IngestionLambdaController.java`
  - `metadata-service/graphql-servlet-impl/build.gradle`
  - `metadata-service/graphql-servlet-impl/gradle.lockfile`
- Search + lineage UI components
  - `datahub-web-react/src/app/entity*/shared/components/styled/search/*`
  - `datahub-web-react/src/app/lineageV3/*`
- Ingestion overview/detail UI
  - `datahub-web-react/src/app/ingestV2/overview/*`
  - `datahub-web-react/src/app/SearchRoutes.tsx`
  - `datahub-web-react/src/conf/Global.ts`
- Local compose + scripts
  - `docker/quickstart/docker-compose.override.local.yml`
  - `scripts/restart_datahub_stack.sh`
- Ingestion hybrid changes
  - `metadata-ingestion/src/datahub/ingestion/source/dbt/dbt_common.py`
