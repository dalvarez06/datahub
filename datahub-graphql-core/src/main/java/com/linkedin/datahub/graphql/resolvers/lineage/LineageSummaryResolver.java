package com.linkedin.datahub.graphql.resolvers.lineage;

import static com.linkedin.datahub.graphql.resolvers.ResolverUtils.bindArgument;
import static com.linkedin.datahub.graphql.types.mappers.MapperUtils.mapPath;

import com.datahub.authorization.AuthorizationConfiguration;
import com.linkedin.common.UrnArrayArray;
import com.linkedin.common.urn.Urn;
import com.linkedin.common.urn.UrnUtils;
import com.linkedin.data.template.SetMode;
import com.linkedin.datahub.graphql.QueryContext;
import com.linkedin.datahub.graphql.authorization.AuthorizationUtils;
import com.linkedin.datahub.graphql.concurrency.GraphQLConcurrencyUtils;
import com.linkedin.datahub.graphql.generated.Entity;
import com.linkedin.datahub.graphql.generated.EntityType;
import com.linkedin.datahub.graphql.generated.LineageRelationship;
import com.linkedin.datahub.graphql.generated.LineageSummary;
import com.linkedin.datahub.graphql.generated.LineageSummaryDirection;
import com.linkedin.datahub.graphql.generated.LineageSummaryInput;
import com.linkedin.datahub.graphql.generated.LineageSummaryResult;
import com.linkedin.datahub.graphql.generated.Restricted;
import com.linkedin.datahub.graphql.types.common.mappers.UrnToEntityMapper;
import com.linkedin.metadata.graph.EntityLineageResult;
import com.linkedin.metadata.graph.LineageDirection;
import com.linkedin.metadata.graph.SiblingGraphService;
import graphql.schema.DataFetcher;
import graphql.schema.DataFetchingEnvironment;
import io.datahubproject.metadata.services.RestrictedService;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class LineageSummaryResolver implements DataFetcher<CompletableFuture<LineageSummaryResult>> {

  private static final int DEFAULT_SAMPLE_COUNT = 3;

  private final SiblingGraphService _siblingGraphService;
  private final RestrictedService _restrictedService;
  private final AuthorizationConfiguration _authorizationConfiguration;

  public LineageSummaryResolver(
      final SiblingGraphService siblingGraphService,
      final RestrictedService restrictedService,
      final AuthorizationConfiguration authorizationConfiguration) {
    _siblingGraphService = siblingGraphService;
    _restrictedService = restrictedService;
    _authorizationConfiguration = authorizationConfiguration;
  }

  @Override
  public CompletableFuture<LineageSummaryResult> get(DataFetchingEnvironment environment) {
    final QueryContext context = environment.getContext();
    final LineageSummaryInput input =
        bindArgument(environment.getArgument("input"), LineageSummaryInput.class);

    final List<String> urnStrings = input.getUrns();
    final int sampleCount =
        Optional.ofNullable(input.getSampleCount()).orElse(DEFAULT_SAMPLE_COUNT);
    final boolean separateSiblings = Boolean.TRUE.equals(input.getSeparateSiblings());
    final boolean includeGhostEntities = Boolean.TRUE.equals(input.getIncludeGhostEntities());

    final Long startTimeMillis = input.getStartTimeMillis();
    final Long endTimeMillis = input.getEndTimeMillis();

    return GraphQLConcurrencyUtils.supplyAsync(
        () -> {
          LineageSummaryResult result = new LineageSummaryResult();
          List<LineageSummary> summaries = new ArrayList<>();

          for (String urnStr : urnStrings) {
            try {
              Urn urn = UrnUtils.getUrn(urnStr);
              LineageSummary summary = new LineageSummary();
              summary.setUrn(urnStr);

              LineageSummaryDirection upstream =
                  getSummaryDirection(
                      context,
                      urn,
                      LineageDirection.UPSTREAM,
                      sampleCount,
                      separateSiblings,
                      includeGhostEntities,
                      startTimeMillis,
                      endTimeMillis);
              LineageSummaryDirection downstream =
                  getSummaryDirection(
                      context,
                      urn,
                      LineageDirection.DOWNSTREAM,
                      sampleCount,
                      separateSiblings,
                      includeGhostEntities,
                      startTimeMillis,
                      endTimeMillis);

              summary.setUpstream(upstream);
              summary.setDownstream(downstream);
              summaries.add(summary);
            } catch (Exception e) {
              log.error("Failed to fetch lineage summary for {}", urnStr, e);
              throw new RuntimeException(
                  String.format("Failed to fetch lineage summary for %s", urnStr), e);
            }
          }

          result.setResults(summaries);
          return result;
        },
        this.getClass().getSimpleName(),
        "get");
  }

  private LineageSummaryDirection getSummaryDirection(
      QueryContext context,
      Urn urn,
      LineageDirection direction,
      int sampleCount,
      boolean separateSiblings,
      boolean includeGhostEntities,
      Long startTimeMillis,
      Long endTimeMillis) {
    EntityLineageResult lineageResult =
        _siblingGraphService.getLineage(
            context
                .getOperationContext()
                .withSearchFlags(
                    searchFlags -> searchFlags.setIncludeSoftDeleted(includeGhostEntities))
                .withLineageFlags(
                    flags ->
                        flags
                            .setStartTimeMillis(startTimeMillis, SetMode.REMOVE_IF_NULL)
                            .setEndTimeMillis(endTimeMillis, SetMode.REMOVE_IF_NULL)),
            urn,
            direction,
            0,
            Math.max(sampleCount, 0),
            1,
            separateSiblings,
            includeGhostEntities,
            new HashSet<>());

    LineageSummaryDirection summaryDirection = new LineageSummaryDirection();
    summaryDirection.setTotal(Optional.ofNullable(lineageResult.getTotal()).orElse(0));
    summaryDirection.setRelationships(mapRelationships(context, urn, lineageResult));
    return summaryDirection;
  }

  private List<LineageRelationship> mapRelationships(
      QueryContext context, Urn rootUrn, EntityLineageResult lineageResult) {
    if (lineageResult == null || lineageResult.getRelationships() == null) {
      return Collections.emptyList();
    }

    Set<Urn> restrictedUrns = new HashSet<>();
    lineageResult
        .getRelationships()
        .forEach(
            rel -> {
              if (_authorizationConfiguration.getView().isEnabled()
                  && !AuthorizationUtils.canViewRelationship(
                      context.getOperationContext(), rel.getEntity(), rootUrn)) {
                restrictedUrns.add(rel.getEntity());
              }
            });

    return lineageResult.getRelationships().stream()
        .map(rel -> mapRelationship(context, rel, restrictedUrns))
        .collect(Collectors.toList());
  }

  private LineageRelationship mapRelationship(
      QueryContext context,
      com.linkedin.metadata.graph.LineageRelationship lineageRelationship,
      Set<Urn> restrictedUrns) {
    LineageRelationship result = new LineageRelationship();
    if (restrictedUrns.contains(lineageRelationship.getEntity())) {
      Restricted restrictedEntity = new Restricted();
      restrictedEntity.setType(EntityType.RESTRICTED);
      String restrictedUrnString =
          _restrictedService.encryptRestrictedUrn(lineageRelationship.getEntity()).toString();
      restrictedEntity.setUrn(restrictedUrnString);
      result.setEntity(restrictedEntity);
    } else {
      Entity partialEntity = UrnToEntityMapper.map(context, lineageRelationship.getEntity());
      if (partialEntity != null) {
        result.setEntity(partialEntity);
      }
    }

    result.setType(lineageRelationship.getType());
    result.setDegree(lineageRelationship.getDegree());
    if (lineageRelationship.hasCreatedOn()) {
      result.setCreatedOn(lineageRelationship.getCreatedOn());
    }
    if (lineageRelationship.hasCreatedActor()) {
      final Urn createdActor = lineageRelationship.getCreatedActor();
      result.setCreatedActor(UrnToEntityMapper.map(context, createdActor));
    }
    if (lineageRelationship.hasUpdatedOn()) {
      result.setUpdatedOn(lineageRelationship.getUpdatedOn());
    }
    if (lineageRelationship.hasUpdatedActor()) {
      final Urn updatedActor = lineageRelationship.getUpdatedActor();
      result.setUpdatedActor(UrnToEntityMapper.map(context, updatedActor));
    }
    result.setIsManual(lineageRelationship.hasIsManual() && lineageRelationship.isIsManual());
    if (lineageRelationship.getPaths() != null) {
      UrnArrayArray paths = lineageRelationship.getPaths();
      result.setPaths(
          paths.stream().map(path -> mapPath(context, path)).collect(Collectors.toList()));
    }

    return result;
  }
}
