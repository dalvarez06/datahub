# Lineage Table by Filter - Implementation Notes

## Goal
- Provide a table view that lists all assets matching current filters (tags, platform, entity types, etc).
- Show 1-hop upstream/downstream summaries per row and allow expanding to full lineage in a table view.

## What was implemented

### Backend (GraphQL)
- Added a new GraphQL query `lineageSummary` that returns a 1-hop summary for a list of URNs.
- Implemented `LineageSummaryResolver` using `SiblingGraphService.getLineage` with depth=1 and a configurable sample count.
- Registered the resolver in `GmsGraphQLEngine`.
- Authorization-aware: restricted relationships are mapped to `Restricted` entities.

### Frontend (Web)
- Added `lineageSummary` query to `search.graphql` (used by generated hooks).
- New `/lineage-table` page built on `EmbeddedListSearchSection` to reuse the existing search + filter bar.
- Each search result row renders a lineage action panel:
  - Upstream/downstream counts.
  - Preview names from 1-hop sample relationships.
  - Clicking the arrow opens a Drawer with existing table lineage (`ImpactAnalysis`) showing full lineage.
- Drawer defaults to show degrees 1, 2, 3+ for multi-hop lineage.
- Works for both themes (`useIsThemeV2`) by selecting the v1/v2 search + lineage components.

## Files changed (by purpose)

### Backend
- `datahub-graphql-core/src/main/resources/search.graphql`
  - Added `lineageSummary` query + `LineageSummary*` input/types.
- `datahub-graphql-core/src/main/java/com/linkedin/datahub/graphql/resolvers/lineage/LineageSummaryResolver.java`
  - New resolver implementation.
- `datahub-graphql-core/src/main/java/com/linkedin/datahub/graphql/GmsGraphQLEngine.java`
  - Import + dataFetcher registration for `lineageSummary`.

### Frontend
- `datahub-web-react/src/graphql/search.graphql`
  - Added `query lineageSummary` (used by generated hook).
- `datahub-web-react/src/app/lineageTable/LineageByFilterPage.tsx`
  - New page: filters + summary rows + lineage drawer.
- `datahub-web-react/src/conf/Global.ts`
  - Added `PageRoutes.LINEAGE_TABLE = '/lineage-table'`.
- `datahub-web-react/src/app/SearchRoutes.tsx`
  - Mounted the new route.
- `datahub-web-react/src/app/entity/shared/components/styled/search/EmbeddedListSearchSection.tsx`
  - Added `entityAction` prop passthrough (v1).
- `datahub-web-react/src/app/entityV2/shared/components/styled/search/EmbeddedListSearchSection.tsx`
  - Added `entityAction` prop passthrough (v2).
- `datahub-web-react/src/app/entity/shared/tabs/Lineage/ImpactAnalysis.tsx`
  - Added `defaultFilters` prop for customizing default hop filters (v1).

## How it works (flow)
- The page reads search query/filters from URL via `EmbeddedListSearchSection`.
- A custom `useGetSearchResults` captures the current page of search results (URNs + entity map).
- `useLineageSummaryQuery` fetches 1-hop summary for those URNs.
- The row action panel renders counts + preview names.
- Clicking an arrow opens a Drawer with `ImpactAnalysis` (table lineage) for full upstream/downstream lineage.

## Merge conflict notes
If conflicts happen, preserve the following anchors:
- Backend schema: `lineageSummary` query + `LineageSummary*` types in
  `datahub-graphql-core/src/main/resources/search.graphql`.
- Backend resolver registration in `GmsGraphQLEngine`:
  - Import `LineageSummaryResolver`.
  - Data fetcher entry: `.dataFetcher("lineageSummary", new LineageSummaryResolver(...))`.
- New resolver file:
  `datahub-graphql-core/src/main/java/com/linkedin/datahub/graphql/resolvers/lineage/LineageSummaryResolver.java`.
- Frontend query: `query lineageSummary` in
  `datahub-web-react/src/graphql/search.graphql`.
- New page + route:
  `datahub-web-react/src/app/lineageTable/LineageByFilterPage.tsx`,
  `datahub-web-react/src/conf/Global.ts`,
  `datahub-web-react/src/app/SearchRoutes.tsx`.
- Search component extensions:
  `EmbeddedListSearchSection.tsx` (v1 + v2) `entityAction` prop.
- ImpactAnalysis (v1) `defaultFilters` support:
  `datahub-web-react/src/app/entity/shared/tabs/Lineage/ImpactAnalysis.tsx`.

## Next steps
1. Run backend GraphQL codegen (requires Java runtime):
   - `./gradlew :datahub-graphql-core:graphqlCodegen`
2. (Optional) Add a nav entry or a CTA pointing to `/lineage-table`.
3. Decide whether to pass time range, `includeGhostEntities`, or `separateSiblings` into `lineageSummary`.
4. Add analytics events if needed for page view and arrow clicks.
5. Evaluate if summary should include more fields or a larger sample size.

## Changing functionality
- Change 1-hop sample size:
  - Update `DEFAULT_SAMPLE_COUNT` in
    `datahub-web-react/src/app/lineageTable/LineageByFilterPage.tsx`.
- Change preview content:
  - Update `query lineageSummary` in `datahub-web-react/src/graphql/search.graphql`,
    then adjust `buildRelationshipPreview` in `LineageByFilterPage.tsx`.
- Change default hop depth in drawer:
  - Update `FULL_LINEAGE_FILTERS` in `LineageByFilterPage.tsx`.
- Use time range / ghost entity settings:
  - Thread params into the `useLineageSummaryQuery` input (start/end time, includeGhostEntities, separateSiblings).

## Testing
- Frontend codegen:
  - `yarn --cwd datahub-web-react generate` (already ran).
- Backend codegen:
  - `./gradlew :datahub-graphql-core:graphqlCodegen` (pending; Java runtime missing).
