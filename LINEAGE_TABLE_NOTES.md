# Lineage Table by Filter - Implementation Notes

## Goal
- Provide a table view that lists all assets matching current filters (tags, platform, entity types, etc).
- Show 1-hop upstream/downstream summaries per row and allow expanding to full lineage in a table view.
- Provide an optional multi-root lineage graph view for all entities matching the current filters (all roots on one graph, no pagination).

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
- Added a Graph view toggle that renders a combined node graph for all filtered entities (multi-root lineage).
- Graph view uses a separate, high-count search to avoid pagination splitting roots across pages.
- Pagination is disabled for the lineage-table list view only (other pages unchanged).
- Graph roots are vertically spaced for readability.
- Graph container height increased to 100vh for a taller canvas.
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
  - New page: filters + summary rows + lineage drawer + graph view toggle.
  - Graph container height set to 100vh, with higher min-height.
  - Graph view fetches a large, unpaginated result set for roots.
  - List pagination disabled for this view only.
- `datahub-web-react/src/app/lineageTable/MultiRootLineageGraph.tsx`
  - Multi-root lineage graph implementation (client-side aggregation).
  - Root URNs passed into lineage context to order/lay out roots.
- `datahub-web-react/src/conf/Global.ts`
  - Added `PageRoutes.LINEAGE_TABLE = '/lineage-table'`.
- `datahub-web-react/src/app/SearchRoutes.tsx`
  - Mounted the new route.
- `datahub-web-react/src/app/entity/shared/components/styled/search/EmbeddedListSearchSection.tsx`
  - Added `entityAction` prop passthrough (v1).
- `datahub-web-react/src/app/entityV2/shared/components/styled/search/EmbeddedListSearchSection.tsx`
  - Added `entityAction` prop passthrough (v2).
- `datahub-web-react/src/app/entity/shared/components/styled/search/EmbeddedListSearch.tsx`
  - Added `disablePagination` support (v1).
- `datahub-web-react/src/app/entityV2/shared/components/styled/search/EmbeddedListSearch.tsx`
  - Added `disablePagination` support (v2).
- `datahub-web-react/src/app/entity/shared/components/styled/search/EmbeddedListSearchResults.tsx`
  - Added `disablePagination` support (v1).
- `datahub-web-react/src/app/entityV2/shared/components/styled/search/EmbeddedListSearchResults.tsx`
  - Added `disablePagination` support + `hidePagination` passthrough (v2).
- `datahub-web-react/src/app/entity/shared/tabs/Lineage/ImpactAnalysis.tsx`
  - Added `defaultFilters` prop for customizing default hop filters (v1).
- `datahub-web-react/src/app/lineageV3/useComputeGraph/NodeBuilder.ts`
  - Root ordering + vertical spacing for multi-root layout.
- `datahub-web-react/src/app/lineageV3/useComputeGraph/computeImpactAnalysisGraph.ts`
  - Supports multi-root ordering + hidden-node filtering with root arrays.
- `datahub-web-react/src/app/lineageV3/useComputeGraph/filterNodes.ts`
  - Supports multi-root hide/show logic.
- `datahub-web-react/src/app/lineageV3/useComputeGraph/orderNodes.ts`
  - Supports multi-root ordering.
- `datahub-web-react/src/app/lineageV3/useComputeGraph/getDisplayedNodes.ts`
  - Supports multi-root display order.
- `datahub-web-react/src/app/lineageV3/common.ts`
  - Lineage context extended with `rootUrns`.

## How it works (flow)
- The page reads search query/filters from URL via `EmbeddedListSearchSection`.
- A custom `useGetSearchResults` captures the current page of search results (URNs + entity map).
- `useLineageSummaryQuery` fetches 1-hop summary for those URNs.
- The row action panel renders counts + preview names.
- Clicking an arrow opens a Drawer with `ImpactAnalysis` (table lineage) for full upstream/downstream lineage.
- Graph view uses the filtered entity list as roots, fetches lineage for each root, merges nodes/edges, and renders a combined graph.
- Root list is taken from the unpaginated graph query, so all tagged assets appear at once.

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
- Multi-root graph:
  `datahub-web-react/src/app/lineageTable/MultiRootLineageGraph.tsx`.
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
- Change graph root fetch size:
  - Update `GRAPH_VIEW_PAGE_SIZE` in
    `datahub-web-react/src/app/lineageTable/LineageByFilterPage.tsx`.
- Change preview content:
  - Update `query lineageSummary` in `datahub-web-react/src/graphql/search.graphql`,
    then adjust `buildRelationshipPreview` in `LineageByFilterPage.tsx`.
- Change default hop depth in drawer:
  - Update `FULL_LINEAGE_FILTERS` in `LineageByFilterPage.tsx`.
- Change graph height:
  - Update `GraphContainer` in
    `datahub-web-react/src/app/lineageTable/LineageByFilterPage.tsx`.
- Use time range / ghost entity settings:
  - Thread params into the `useLineageSummaryQuery` input (start/end time, includeGhostEntities, separateSiblings).

## Testing
- Frontend codegen:
  - `yarn --cwd datahub-web-react generate` (already ran).
- Backend codegen:
  - `./gradlew :datahub-graphql-core:graphqlCodegen` (pending; Java runtime missing).
