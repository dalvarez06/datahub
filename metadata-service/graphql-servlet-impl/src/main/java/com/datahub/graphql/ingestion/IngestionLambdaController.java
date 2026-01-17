package com.datahub.graphql.ingestion;

import com.datahub.authentication.Authentication;
import com.datahub.authentication.AuthenticationContext;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.ImmutableSet;
import com.linkedin.common.urn.Urn;
import com.linkedin.entity.EntityResponse;
import com.linkedin.entity.client.EntityClient;
import com.linkedin.metadata.Constants;
import com.linkedin.structured.StructuredProperties;
import com.linkedin.structured.PrimitivePropertyValue;
import com.linkedin.structured.StructuredPropertyValueAssignment;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import javax.annotation.Nonnull;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import io.datahubproject.metadata.context.OperationContext;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.lambda.LambdaClient;
import software.amazon.awssdk.services.lambda.model.InvokeRequest;
import software.amazon.awssdk.services.lambda.model.InvokeResponse;
import software.amazon.awssdk.services.lambda.model.InvocationType;
import software.amazon.awssdk.services.sfn.SfnClient;
import software.amazon.awssdk.services.sfn.SfnClientBuilder;
import software.amazon.awssdk.services.sfn.model.ExecutionListItem;
import software.amazon.awssdk.services.sfn.model.ListExecutionsRequest;
import software.amazon.awssdk.services.sfn.model.ListExecutionsResponse;
import software.amazon.awssdk.services.sfn.model.ListStateMachinesRequest;
import software.amazon.awssdk.services.sfn.model.ListStateMachinesResponse;
import software.amazon.awssdk.services.sfn.model.StateMachineListItem;
import software.amazon.awssdk.services.sfn.model.StartExecutionRequest;
import software.amazon.awssdk.services.sfn.model.StartExecutionResponse;
import software.amazon.awssdk.services.lambda.LambdaClientBuilder;

@Slf4j
@RestController
@RequestMapping("/api/ingestion")
public class IngestionLambdaController {

  private static final String JOB_ARN_PROPERTY_URN_ENV = "DATAHUB_INGESTION_JOB_ARN_PROPERTY_URN";
  private static final String MAPPING_ENV = "DATAHUB_INGESTION_LAMBDA_MAPPING";
  private static final String MAPPING_FILE_ENV = "DATAHUB_INGESTION_LAMBDA_MAPPING_FILE";
  private static final String DEFAULT_LAMBDA_ENV = "DATAHUB_INGESTION_LAMBDA_DEFAULT";
  private static final String REGION_ENV = "DATAHUB_INGESTION_LAMBDA_REGION";
  private static final String AWS_REGION_ENV = "AWS_REGION";
  private static final String AWS_DEFAULT_REGION_ENV = "AWS_DEFAULT_REGION";
  private static final int DEFAULT_EXECUTION_LIMIT = 15;
  private static final int MAX_EXECUTION_LIMIT = 50;
  private static final long STEP_FUNCTIONS_CACHE_TTL_MS = 60_000L;
  private static final AtomicReference<CachedStepFunctionsResponse> STEP_FUNCTIONS_CACHE =
      new AtomicReference<>();

  private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

  @Inject
  @Qualifier("entityClient")
  private EntityClient entityClient;

  @Nonnull
  @Inject
  @Named("systemOperationContext")
  private OperationContext systemOperationContext;

  @PostMapping(value = "/run", produces = MediaType.APPLICATION_JSON_VALUE)
  public ResponseEntity<IngestionRunResponse> run(@RequestBody IngestionRunRequest request) {
    if (request == null) {
      return ResponseEntity.status(HttpStatus.BAD_REQUEST)
          .body(IngestionRunResponse.failed("datasetUrn or jobUrn is required"));
    }

    boolean hasDatasetUrn = StringUtils.isNotBlank(request.getDatasetUrn());
    boolean hasJobUrn = StringUtils.isNotBlank(request.getJobUrn());
    if (!hasDatasetUrn && !hasJobUrn) {
      return ResponseEntity.status(HttpStatus.BAD_REQUEST)
          .body(IngestionRunResponse.failed("datasetUrn or jobUrn is required"));
    }

    if (hasDatasetUrn) {
      try {
        Urn.createFromString(request.getDatasetUrn());
      } catch (Exception e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
            .body(IngestionRunResponse.failed("datasetUrn is not a valid DataHub URN"));
      }
    }

    if (hasJobUrn) {
      try {
        Urn.createFromString(request.getJobUrn());
      } catch (Exception e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
            .body(IngestionRunResponse.failed("jobUrn is not a valid DataHub URN"));
      }
    }

    String jobArn = resolveJobArn(request.getJobUrn());
    if (StringUtils.isNotBlank(jobArn)) {
      return startStepFunction(request, jobArn);
    }

    if (!hasDatasetUrn) {
      return ResponseEntity.status(HttpStatus.BAD_REQUEST)
          .body(IngestionRunResponse.failed("datasetUrn is required when no job ARN is configured"));
    }

    Map<String, String> mapping = loadMapping();
    String lambdaName = mapping.get(request.getDatasetUrn());
    if (StringUtils.isBlank(lambdaName)) {
      lambdaName = System.getenv(DEFAULT_LAMBDA_ENV);
    }

    if (StringUtils.isBlank(lambdaName)) {
      return ResponseEntity.status(HttpStatus.NOT_FOUND)
          .body(IngestionRunResponse.failed("No Lambda mapping found for dataset"));
    }

    String payload = buildPayload(request, null);
    if (payload == null) {
      return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
          .body(IngestionRunResponse.failed("Failed to serialize Lambda payload"));
    }

    InvokeResponse response;
    try (LambdaClient lambdaClient = buildLambdaClient()) {
      InvokeRequest invokeRequest =
          InvokeRequest.builder()
              .functionName(lambdaName)
              .invocationType(InvocationType.EVENT)
              .payload(SdkBytes.fromString(payload, StandardCharsets.UTF_8))
              .build();
      response = lambdaClient.invoke(invokeRequest);
    } catch (Exception e) {
      log.warn("Failed to invoke Lambda {} for dataset {}", lambdaName, request.getDatasetUrn(), e);
      return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
          .body(IngestionRunResponse.failed("Lambda invocation failed"));
    }

    String runId =
        response.responseMetadata() != null && response.responseMetadata().requestId() != null
            ? response.responseMetadata().requestId()
            : "lambda-" + Instant.now().toString();
    String actorUrn = resolveActorUrn();
    log.info(
        "Triggered Lambda {} for dataset {} by {} with status {}",
        lambdaName,
        request.getDatasetUrn(),
        actorUrn,
        response.statusCode());

    return ResponseEntity.ok(IngestionRunResponse.started(runId));
  }

  @GetMapping(value = "/step-functions", produces = MediaType.APPLICATION_JSON_VALUE)
  public ResponseEntity<StepFunctionsOverviewResponse> listStepFunctions(
      @RequestParam(value = "limit", required = false) Integer limit,
      @RequestParam(value = "refresh", required = false) Boolean refresh) {
    int executionLimit = normalizeExecutionLimit(limit);
    boolean bypassCache = Boolean.TRUE.equals(refresh);
    long now = System.currentTimeMillis();

    CachedStepFunctionsResponse cached = STEP_FUNCTIONS_CACHE.get();
    if (!bypassCache
        && cached != null
        && cached.getExecutionLimit() == executionLimit
        && now - cached.getGeneratedAt() < STEP_FUNCTIONS_CACHE_TTL_MS) {
      return ResponseEntity.ok(cached.getResponse());
    }

    StepFunctionsOverviewResponse response = new StepFunctionsOverviewResponse();
    response.setExecutionLimit(executionLimit);
    response.setGeneratedAt(now);
    response.setRegion(resolveRegionName());
    response.setStateMachines(Collections.emptyList());

    List<StateMachineOverview> results = new ArrayList<>();
    try (SfnClient sfnClient = buildSfnClient()) {
      String nextToken = null;
      do {
        ListStateMachinesResponse listResponse =
            sfnClient.listStateMachines(
                ListStateMachinesRequest.builder().maxResults(1000).nextToken(nextToken).build());
        for (StateMachineListItem item : listResponse.stateMachines()) {
          results.add(loadStateMachineOverview(sfnClient, item, executionLimit));
        }
        nextToken = listResponse.nextToken();
      } while (nextToken != null);
    } catch (Exception e) {
      log.warn("Failed to load Step Functions overview", e);
      response.setError("Failed to load Step Functions overview");
      response.setStateMachines(Collections.emptyList());
      response.setTotalStateMachines(0);
      return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(response);
    }

    response.setStateMachines(results);
    response.setTotalStateMachines(results.size());
    STEP_FUNCTIONS_CACHE.set(new CachedStepFunctionsResponse(now, executionLimit, response));
    return ResponseEntity.ok(response);
  }

  private ResponseEntity<IngestionRunResponse> startStepFunction(
      IngestionRunRequest request, String jobArn) {
    String payload = buildPayload(request, jobArn);
    if (payload == null) {
      return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
          .body(IngestionRunResponse.failed("Failed to serialize Step Functions payload"));
    }

    StartExecutionResponse response;
    try (SfnClient sfnClient = buildSfnClient()) {
      StartExecutionRequest startRequest =
          StartExecutionRequest.builder().stateMachineArn(jobArn).input(payload).build();
      response = sfnClient.startExecution(startRequest);
    } catch (Exception e) {
      log.warn("Failed to start Step Functions {} for dataset {}", jobArn, request.getDatasetUrn(), e);
      return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
          .body(IngestionRunResponse.failed("Step Functions invocation failed"));
    }

    String runId =
        StringUtils.isNotBlank(response.executionArn())
            ? response.executionArn()
            : "sfn-" + Instant.now().toString();
    log.info(
        "Started Step Functions {} for dataset {} by {}",
        jobArn,
        request.getDatasetUrn(),
        resolveActorUrn());

    return ResponseEntity.ok(IngestionRunResponse.started(runId));
  }

  private String resolveJobArn(String jobUrn) {
    if (StringUtils.isBlank(jobUrn)) {
      return null;
    }

    String propertyUrn = System.getenv(JOB_ARN_PROPERTY_URN_ENV);
    if (StringUtils.isBlank(propertyUrn)) {
      log.warn("Job ARN property env var {} is not set", JOB_ARN_PROPERTY_URN_ENV);
      return null;
    }

    try {
      Urn parsedJobUrn = Urn.createFromString(jobUrn);
      EntityResponse response =
          entityClient.getV2(
              systemOperationContext,
              parsedJobUrn.getEntityType(),
              parsedJobUrn,
              ImmutableSet.of(Constants.STRUCTURED_PROPERTIES_ASPECT_NAME));

      if (response == null
          || response.getAspects() == null
          || !response.getAspects().containsKey(Constants.STRUCTURED_PROPERTIES_ASPECT_NAME)) {
        return null;
      }

      StructuredProperties structuredProperties =
          new StructuredProperties(
              response.getAspects().get(Constants.STRUCTURED_PROPERTIES_ASPECT_NAME).getValue().data());
      if (!structuredProperties.hasProperties()) {
        return null;
      }

      for (StructuredPropertyValueAssignment assignment : structuredProperties.getProperties()) {
        if (assignment == null
            || assignment.getPropertyUrn() == null
            || !assignment.getPropertyUrn().toString().equals(propertyUrn)) {
          continue;
        }
        if (assignment.getValues() == null || assignment.getValues().isEmpty()) {
          return null;
        }
        PrimitivePropertyValue value = assignment.getValues().get(0);
        if (value == null) {
          return null;
        }
        if (value.isString()) {
          return value.getString();
        }
        if (value.isDouble()) {
          return value.getDouble().toString();
        }
        return value.toString();
      }
    } catch (Exception e) {
      log.warn("Failed to resolve job ARN from job {}", jobUrn, e);
    }
    return null;
  }

  private Map<String, String> loadMapping() {
    String inlineMapping = System.getenv(MAPPING_ENV);
    if (StringUtils.isNotBlank(inlineMapping)) {
      return parseMapping(inlineMapping, "env");
    }

    String mappingFile = System.getenv(MAPPING_FILE_ENV);
    if (StringUtils.isNotBlank(mappingFile)) {
      try {
        String json = Files.readString(Path.of(mappingFile));
        return parseMapping(json, mappingFile);
      } catch (IOException e) {
        log.warn("Failed to read Lambda mapping file {}", mappingFile, e);
      }
    }
    return Collections.emptyMap();
  }

  private String buildPayload(IngestionRunRequest request, String jobArn) {
    String actorUrn = resolveActorUrn();
    try {
      Map<String, String> payload = new HashMap<>();
      if (StringUtils.isNotBlank(request.getDatasetUrn())) {
        payload.put("datasetUrn", request.getDatasetUrn());
      }
      payload.put("actorUrn", actorUrn);
      payload.put("reason", StringUtils.defaultString(request.getReason()));
      if (StringUtils.isNotBlank(request.getJobUrn())) {
        payload.put("jobUrn", request.getJobUrn());
      }
      if (StringUtils.isNotBlank(jobArn)) {
        payload.put("jobArn", jobArn);
      }
      return OBJECT_MAPPER.writeValueAsString(payload);
    } catch (IOException e) {
      log.warn("Failed to serialize payload for dataset {}", request.getDatasetUrn(), e);
      return null;
    }
  }

  private Map<String, String> parseMapping(String json, String source) {
    try {
      Map<String, String> mapping =
          OBJECT_MAPPER.readValue(json, new TypeReference<Map<String, String>>() {});
      return mapping == null ? Collections.emptyMap() : mapping;
    } catch (IOException e) {
      log.warn("Failed to parse Lambda mapping from {}", source, e);
      return Collections.emptyMap();
    }
  }

  private LambdaClient buildLambdaClient() {
    String regionName = resolveRegionName();

    LambdaClientBuilder builder = LambdaClient.builder();
    if (StringUtils.isNotBlank(regionName)) {
      builder.region(Region.of(regionName));
    }
    return builder.build();
  }

  private SfnClient buildSfnClient() {
    String regionName = resolveRegionName();

    SfnClientBuilder builder = SfnClient.builder();
    if (StringUtils.isNotBlank(regionName)) {
      builder.region(Region.of(regionName));
    }
    return builder.build();
  }

  private String resolveRegionName() {
    String regionName = System.getenv(REGION_ENV);
    if (StringUtils.isBlank(regionName)) {
      regionName = System.getenv(AWS_REGION_ENV);
    }
    if (StringUtils.isBlank(regionName)) {
      regionName = System.getenv(AWS_DEFAULT_REGION_ENV);
    }
    return regionName;
  }

  private int normalizeExecutionLimit(Integer limit) {
    if (limit == null) {
      return DEFAULT_EXECUTION_LIMIT;
    }
    if (limit < 1) {
      return 1;
    }
    if (limit > MAX_EXECUTION_LIMIT) {
      return MAX_EXECUTION_LIMIT;
    }
    return limit;
  }

  private StateMachineOverview loadStateMachineOverview(
      SfnClient sfnClient, StateMachineListItem item, int executionLimit) {
    StateMachineOverview overview = new StateMachineOverview();
    overview.setArn(item.stateMachineArn());
    overview.setName(item.name());
    overview.setStatus(null);
    overview.setType(item.type() != null ? item.type().toString() : null);
    overview.setCreatedAt(item.creationDate() != null ? item.creationDate().toEpochMilli() : null);
    overview.setExecutions(Collections.emptyList());

    try {
      ListExecutionsResponse executionsResponse =
          sfnClient.listExecutions(
              ListExecutionsRequest.builder()
                  .stateMachineArn(item.stateMachineArn())
                  .maxResults(executionLimit)
                  .build());
      List<ExecutionOverview> executions = new ArrayList<>();
      for (ExecutionListItem execution : executionsResponse.executions()) {
        executions.add(mapExecution(execution));
      }
      overview.setExecutions(executions);
    } catch (Exception e) {
      log.warn("Failed to load executions for {}", item.stateMachineArn(), e);
      overview.setError("Failed to load executions");
      overview.setExecutions(Collections.emptyList());
    }
    return overview;
  }

  private ExecutionOverview mapExecution(ExecutionListItem execution) {
    ExecutionOverview overview = new ExecutionOverview();
    overview.setArn(execution.executionArn());
    overview.setName(execution.name());
    overview.setStatus(execution.status() != null ? execution.status().toString() : null);
    if (execution.startDate() != null) {
      overview.setStartTime(execution.startDate().toEpochMilli());
    }
    if (execution.stopDate() != null) {
      overview.setStopTime(execution.stopDate().toEpochMilli());
      if (overview.getStartTime() != null) {
        overview.setDurationMs(overview.getStopTime() - overview.getStartTime());
      }
    }
    return overview;
  }

  private String resolveActorUrn() {
    Authentication authentication = AuthenticationContext.getAuthentication();
    if (authentication == null || authentication.getActor() == null) {
      return Constants.UNKNOWN_ACTOR;
    }
    try {
      return authentication.getActor().toUrnStr();
    } catch (Exception e) {
      return Constants.UNKNOWN_ACTOR;
    }
  }

  @Data
  public static class IngestionRunRequest {
    private String datasetUrn;
    private String jobUrn;
    private String reason;
  }

  @Data
  public static class IngestionRunResponse {
    private String runId;
    private String status;
    private String message;

    public static IngestionRunResponse started(String runId) {
      IngestionRunResponse response = new IngestionRunResponse();
      response.setRunId(runId);
      response.setStatus("started");
      return response;
    }

    public static IngestionRunResponse failed(String message) {
      IngestionRunResponse response = new IngestionRunResponse();
      response.setStatus("failed");
      response.setMessage(message);
      return response;
    }
  }

  @Data
  private static class CachedStepFunctionsResponse {
    private final long generatedAt;
    private final int executionLimit;
    private final StepFunctionsOverviewResponse response;
  }

  @Data
  public static class StepFunctionsOverviewResponse {
    private String region;
    private long generatedAt;
    private int executionLimit;
    private int totalStateMachines;
    private String error;
    private List<StateMachineOverview> stateMachines;
  }

  @Data
  public static class StateMachineOverview {
    private String arn;
    private String name;
    private String status;
    private String type;
    private Long createdAt;
    private String error;
    private List<ExecutionOverview> executions;
  }

  @Data
  public static class ExecutionOverview {
    private String arn;
    private String name;
    private String status;
    private Long startTime;
    private Long stopTime;
    private Long durationMs;
  }
}
