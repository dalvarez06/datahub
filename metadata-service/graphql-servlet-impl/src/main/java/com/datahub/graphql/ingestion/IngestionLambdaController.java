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
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
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
import software.amazon.awssdk.services.cloudwatchlogs.CloudWatchLogsClient;
import software.amazon.awssdk.services.cloudwatchlogs.model.FilterLogEventsRequest;
import software.amazon.awssdk.services.cloudwatchlogs.model.FilterLogEventsResponse;
import software.amazon.awssdk.services.cloudwatchlogs.model.FilteredLogEvent;
import software.amazon.awssdk.services.ecs.EcsClient;
import software.amazon.awssdk.services.ecs.EcsClientBuilder;
import software.amazon.awssdk.services.ecs.model.ContainerDefinition;
import software.amazon.awssdk.services.ecs.model.DescribeTaskDefinitionRequest;
import software.amazon.awssdk.services.ecs.model.DescribeTaskDefinitionResponse;
import software.amazon.awssdk.services.ecs.model.LogConfiguration;
import software.amazon.awssdk.services.ecs.model.TaskDefinition;
import software.amazon.awssdk.services.lambda.LambdaClient;
import software.amazon.awssdk.services.lambda.model.InvokeRequest;
import software.amazon.awssdk.services.lambda.model.InvokeResponse;
import software.amazon.awssdk.services.lambda.model.InvocationType;
import software.amazon.awssdk.services.sfn.SfnClient;
import software.amazon.awssdk.services.sfn.SfnClientBuilder;
import software.amazon.awssdk.services.sfn.model.DescribeExecutionRequest;
import software.amazon.awssdk.services.sfn.model.DescribeExecutionResponse;
import software.amazon.awssdk.services.sfn.model.DescribeStateMachineRequest;
import software.amazon.awssdk.services.sfn.model.DescribeStateMachineResponse;
import software.amazon.awssdk.services.sfn.model.ExecutionListItem;
import software.amazon.awssdk.services.sfn.model.GetExecutionHistoryRequest;
import software.amazon.awssdk.services.sfn.model.GetExecutionHistoryResponse;
import software.amazon.awssdk.services.sfn.model.HistoryEvent;
import software.amazon.awssdk.services.sfn.model.HistoryEventType;
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
  private static final int DEFAULT_EVENT_LIMIT = 500;
  private static final int MAX_EVENT_LIMIT = 1000;
  private static final int DEFAULT_LOG_LIMIT = 200;
  private static final int MAX_LOG_LIMIT = 500;
  private static final long LOG_WINDOW_PADDING_MS = 120_000L;
  private static final long STEP_FUNCTIONS_CACHE_TTL_MS = 60_000L;
  private static final AtomicReference<CachedStepFunctionsResponse> STEP_FUNCTIONS_CACHE =
      new AtomicReference<>();
  private static final Map<String, CachedWorkflowOverviewResponse> WORKFLOW_CACHE =
      new ConcurrentHashMap<>();

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
    WorkflowProviderAdapter adapter = resolveProviderAdapter(WorkflowProvider.AWS_STEP_FUNCTIONS);
    WorkflowOverviewResponse workflows = listWorkflowsInternal(adapter, limit, refresh);
    StepFunctionsOverviewResponse response = new StepFunctionsOverviewResponse();
    response.setExecutionLimit(workflows.getExecutionLimit());
    response.setGeneratedAt(workflows.getGeneratedAt());
    response.setRegion(workflows.getRegion());
    response.setError(workflows.getError());
    response.setTotalStateMachines(workflows.getTotalWorkflows());
    List<StateMachineOverview> stateMachines = new ArrayList<>();
    for (WorkflowOverview workflow : workflows.getWorkflows()) {
      StateMachineOverview machine = new StateMachineOverview();
      machine.setArn(workflow.getId());
      machine.setName(workflow.getName());
      machine.setStatus(workflow.getStatus());
      machine.setType(workflow.getType());
      machine.setCreatedAt(workflow.getCreatedAt());
      machine.setError(workflow.getError());
      List<ExecutionOverview> executions = new ArrayList<>();
      for (WorkflowExecutionOverview execution : workflow.getExecutions()) {
        executions.add(mapExecutionOverview(execution));
      }
      machine.setExecutions(executions);
      stateMachines.add(machine);
    }
    response.setStateMachines(stateMachines);
    if (StringUtils.isNotBlank(workflows.getError())) {
      return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(response);
    }
    return ResponseEntity.ok(response);
  }

  @GetMapping(value = "/workflows", produces = MediaType.APPLICATION_JSON_VALUE)
  public ResponseEntity<WorkflowOverviewResponse> listWorkflows(
      @RequestParam(value = "provider", required = false) String provider,
      @RequestParam(value = "limit", required = false) Integer limit,
      @RequestParam(value = "refresh", required = false) Boolean refresh) {
    WorkflowProvider workflowProvider = WorkflowProvider.from(provider);
    WorkflowProviderAdapter adapter = resolveProviderAdapter(workflowProvider);
    if (!adapter.isSupported()) {
      return ResponseEntity.status(HttpStatus.BAD_REQUEST)
          .body(WorkflowOverviewResponse.error(adapter.getUnsupportedMessage()));
    }
    WorkflowOverviewResponse response = listWorkflowsInternal(adapter, limit, refresh);
    if (StringUtils.isNotBlank(response.getError())) {
      return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(response);
    }
    return ResponseEntity.ok(response);
  }

  @GetMapping(value = "/workflows/detail", produces = MediaType.APPLICATION_JSON_VALUE)
  public ResponseEntity<WorkflowDetailResponse> getWorkflowDetail(
      @RequestParam(value = "provider", required = false) String provider,
      @RequestParam(value = "workflowId") String workflowId,
      @RequestParam(value = "limit", required = false) Integer limit) {
    WorkflowProvider workflowProvider = WorkflowProvider.from(provider);
    WorkflowProviderAdapter adapter = resolveProviderAdapter(workflowProvider);
    if (!adapter.isSupported()) {
      return ResponseEntity.status(HttpStatus.BAD_REQUEST)
          .body(WorkflowDetailResponse.error(adapter.getUnsupportedMessage()));
    }
    if (StringUtils.isBlank(workflowId)) {
      return ResponseEntity.status(HttpStatus.BAD_REQUEST)
          .body(WorkflowDetailResponse.error("workflowId is required"));
    }
    WorkflowDetailResponse response =
        getWorkflowDetailInternal(adapter, workflowId, normalizeExecutionLimit(limit));
    if (StringUtils.isNotBlank(response.getError())) {
      return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(response);
    }
    return ResponseEntity.ok(response);
  }

  @GetMapping(value = "/workflows/execution", produces = MediaType.APPLICATION_JSON_VALUE)
  public ResponseEntity<WorkflowExecutionDetailResponse> getWorkflowExecution(
      @RequestParam(value = "provider", required = false) String provider,
      @RequestParam(value = "executionArn") String executionArn,
      @RequestParam(value = "maxEvents", required = false) Integer maxEvents,
      @RequestParam(value = "includeLogs", required = false) Boolean includeLogs,
      @RequestParam(value = "logLimit", required = false) Integer logLimit) {
    WorkflowProvider workflowProvider = WorkflowProvider.from(provider);
    WorkflowProviderAdapter adapter = resolveProviderAdapter(workflowProvider);
    if (!adapter.isSupported()) {
      return ResponseEntity.status(HttpStatus.BAD_REQUEST)
          .body(WorkflowExecutionDetailResponse.error(adapter.getUnsupportedMessage()));
    }
    if (StringUtils.isBlank(executionArn)) {
      return ResponseEntity.status(HttpStatus.BAD_REQUEST)
          .body(WorkflowExecutionDetailResponse.error("executionArn is required"));
    }
    WorkflowExecutionDetailResponse response =
        getWorkflowExecutionDetailInternal(
            adapter,
            executionArn,
            normalizeEventLimit(maxEvents),
            Boolean.TRUE.equals(includeLogs),
            normalizeLogLimit(logLimit));
    if (StringUtils.isNotBlank(response.getError())) {
      return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(response);
    }
    return ResponseEntity.ok(response);
  }

  @PostMapping(value = "/workflows/run", produces = MediaType.APPLICATION_JSON_VALUE)
  public ResponseEntity<WorkflowRunResponse> runWorkflow(@RequestBody WorkflowRunRequest request) {
    if (request == null) {
      return ResponseEntity.status(HttpStatus.BAD_REQUEST)
          .body(WorkflowRunResponse.failed("workflowId is required"));
    }
    WorkflowProvider workflowProvider = WorkflowProvider.from(request.getProvider());
    WorkflowProviderAdapter adapter = resolveProviderAdapter(workflowProvider);
    if (!adapter.isSupported()) {
      return ResponseEntity.status(HttpStatus.BAD_REQUEST)
          .body(WorkflowRunResponse.failed(adapter.getUnsupportedMessage()));
    }
    if (StringUtils.isBlank(request.getWorkflowId())) {
      return ResponseEntity.status(HttpStatus.BAD_REQUEST)
          .body(WorkflowRunResponse.failed("workflowId is required"));
    }
    return runWorkflowInternal(adapter, request);
  }

  private WorkflowOverviewResponse listWorkflowsInternal(
      WorkflowProviderAdapter adapter, Integer limit, Boolean refresh) {
    int executionLimit = normalizeExecutionLimit(limit);
    boolean bypassCache = Boolean.TRUE.equals(refresh);
    long now = System.currentTimeMillis();

    String cacheKey = adapter.getProvider().getId() + ":" + executionLimit;
    CachedWorkflowOverviewResponse cached = WORKFLOW_CACHE.get(cacheKey);
    if (!bypassCache
        && cached != null
        && now - cached.getGeneratedAt() < STEP_FUNCTIONS_CACHE_TTL_MS) {
      return cached.getResponse();
    }

    WorkflowOverviewResponse response = new WorkflowOverviewResponse();
    response.setProvider(adapter.getProvider().getId());
    response.setExecutionLimit(executionLimit);
    response.setGeneratedAt(now);
    response.setRegion(adapter.getRegion());
    response.setWorkflows(Collections.emptyList());

    if (!adapter.isSupported()) {
      response.setError(adapter.getUnsupportedMessage());
      response.setWorkflows(Collections.emptyList());
      response.setTotalWorkflows(0);
      WORKFLOW_CACHE.put(cacheKey, new CachedWorkflowOverviewResponse(now, response));
      return response;
    }

    try {
      List<WorkflowOverview> workflows = adapter.listWorkflows(executionLimit);
      response.setWorkflows(workflows);
      response.setTotalWorkflows(workflows.size());
    } catch (Exception e) {
      log.warn("Failed to load workflow overview for {}", adapter.getProvider().getId(), e);
      response.setError("Failed to load workflow overview");
      response.setWorkflows(Collections.emptyList());
      response.setTotalWorkflows(0);
    }

    WORKFLOW_CACHE.put(cacheKey, new CachedWorkflowOverviewResponse(now, response));
    return response;
  }

  private List<WorkflowOverview> loadStepFunctionsOverview(int executionLimit) {
    List<WorkflowOverview> results = new ArrayList<>();
    try (SfnClient sfnClient = buildSfnClient()) {
      String nextToken = null;
      do {
        ListStateMachinesResponse listResponse =
            sfnClient.listStateMachines(
                ListStateMachinesRequest.builder().maxResults(1000).nextToken(nextToken).build());
        for (StateMachineListItem item : listResponse.stateMachines()) {
          results.add(loadWorkflowOverview(sfnClient, item, executionLimit));
        }
        nextToken = listResponse.nextToken();
      } while (nextToken != null);
    }
    return results;
  }

  private WorkflowOverview loadWorkflowOverview(
      SfnClient sfnClient, StateMachineListItem item, int executionLimit) {
    WorkflowOverview overview = new WorkflowOverview();
    overview.setProvider(WorkflowProvider.AWS_STEP_FUNCTIONS.getId());
    overview.setId(item.stateMachineArn());
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
      List<WorkflowExecutionOverview> executions = new ArrayList<>();
      for (ExecutionListItem execution : executionsResponse.executions()) {
        executions.add(mapExecutionOverview(execution));
      }
      overview.setExecutions(executions);
    } catch (Exception e) {
      log.warn("Failed to load executions for {}", item.stateMachineArn(), e);
      overview.setError("Failed to load executions");
      overview.setExecutions(Collections.emptyList());
    }
    return overview;
  }

  private WorkflowDetailResponse getWorkflowDetailInternal(
      WorkflowProviderAdapter adapter, String workflowId, int executionLimit) {
    return adapter.getWorkflowDetail(workflowId, executionLimit);
  }

  private WorkflowDetailResponse loadAwsWorkflowDetail(String workflowId, int executionLimit) {
    WorkflowDetailResponse response = new WorkflowDetailResponse();
    response.setProvider(WorkflowProvider.AWS_STEP_FUNCTIONS.getId());
    response.setId(workflowId);
    response.setExecutions(Collections.emptyList());

    try (SfnClient sfnClient = buildSfnClient()) {
      DescribeStateMachineResponse describe =
          sfnClient.describeStateMachine(
              DescribeStateMachineRequest.builder().stateMachineArn(workflowId).build());
      response.setName(describe.name());
      response.setType(describe.type() != null ? describe.type().toString() : null);
      response.setStatus(describe.status() != null ? describe.status().toString() : null);
      response.setCreatedAt(
          describe.creationDate() != null ? describe.creationDate().toEpochMilli() : null);
      response.setDefinition(describe.definition());
      response.setGraph(
          buildWorkflowGraph(
              describe.definition(), resolveRegionName(), WorkflowProvider.AWS_STEP_FUNCTIONS));

      ListExecutionsResponse executionsResponse =
          sfnClient.listExecutions(
              ListExecutionsRequest.builder()
                  .stateMachineArn(workflowId)
                  .maxResults(executionLimit)
                  .build());
      List<WorkflowExecutionOverview> executions = new ArrayList<>();
      for (ExecutionListItem execution : executionsResponse.executions()) {
        WorkflowExecutionOverview overview = mapExecutionOverview(execution);
        if (isFailureStatus(overview.getStatus())) {
          try {
            DescribeExecutionResponse detail =
                sfnClient.describeExecution(
                    DescribeExecutionRequest.builder().executionArn(execution.executionArn()).build());
            overview.setError(detail.error());
            overview.setCause(detail.cause());
          } catch (Exception e) {
            log.warn("Failed to describe execution {}", execution.executionArn(), e);
          }
        }
        executions.add(overview);
      }
      response.setExecutions(executions);
    } catch (Exception e) {
      log.warn("Failed to load workflow detail for {}", workflowId, e);
      response.setError("Failed to load workflow detail");
    }
    return response;
  }

  private WorkflowExecutionDetailResponse getWorkflowExecutionDetailInternal(
      WorkflowProviderAdapter adapter,
      String executionArn,
      int maxEvents,
      boolean includeLogs,
      int logLimit) {
    return adapter.getExecutionDetail(executionArn, maxEvents, includeLogs, logLimit);
  }

  private WorkflowExecutionDetailResponse loadAwsWorkflowExecutionDetail(
      String executionArn, int maxEvents, boolean includeLogs, int logLimit) {
    WorkflowExecutionDetailResponse response = new WorkflowExecutionDetailResponse();
    response.setProvider(WorkflowProvider.AWS_STEP_FUNCTIONS.getId());
    response.setExecutionArn(executionArn);
    response.setStateStatuses(Collections.emptyList());
    response.setLogs(Collections.emptyList());
    response.setTaskLogs(Collections.emptyList());

    try (SfnClient sfnClient = buildSfnClient()) {
      DescribeExecutionResponse describe =
          sfnClient.describeExecution(
              DescribeExecutionRequest.builder().executionArn(executionArn).build());
      response.setStatus(describe.status() != null ? describe.status().toString() : null);
      response.setStartTime(
          describe.startDate() != null ? describe.startDate().toEpochMilli() : null);
      response.setStopTime(describe.stopDate() != null ? describe.stopDate().toEpochMilli() : null);
      if (response.getStartTime() != null && response.getStopTime() != null) {
        response.setDurationMs(response.getStopTime() - response.getStartTime());
      }
      response.setError(describe.error());
      response.setCause(describe.cause());

      List<HistoryEvent> events = new ArrayList<>();
      String nextToken = null;
      do {
        int remaining = Math.max(0, maxEvents - events.size());
        if (remaining == 0) {
          break;
        }
        GetExecutionHistoryResponse historyResponse =
            sfnClient.getExecutionHistory(
                GetExecutionHistoryRequest.builder()
                    .executionArn(executionArn)
                    .maxResults(Math.min(remaining, 1000))
                    .nextToken(nextToken)
                    .includeExecutionData(includeLogs)
                    .reverseOrder(false)
                    .build());
        events.addAll(historyResponse.events());
        nextToken = historyResponse.nextToken();
      } while (nextToken != null);

      Map<String, WorkflowStateStatus> statuses =
          buildStateStatuses(events, response.getStatus());
      response.setStateStatuses(new ArrayList<>(statuses.values()));

      if (includeLogs) {
        DescribeStateMachineResponse stateMachine =
            sfnClient.describeStateMachine(
                DescribeStateMachineRequest.builder()
                    .stateMachineArn(describe.stateMachineArn())
                    .build());
        WorkflowLogsResult logsResult =
            fetchExecutionLogs(describe, stateMachine, logLimit, response.getStartTime());
        response.setLogs(logsResult.getLogs());
        response.setLogsError(logsResult.getError());
        response.setLogsUrl(logsResult.getLogUrl());
        WorkflowGraph graph =
            buildWorkflowGraph(
                stateMachine.definition(), resolveRegionName(), WorkflowProvider.AWS_STEP_FUNCTIONS);
        response.setTaskLogs(fetchTaskLogs(describe, graph, statuses, logLimit));
      }
    } catch (Exception e) {
      log.warn("Failed to load workflow execution detail for {}", executionArn, e);
      response.setError("Failed to load workflow execution detail");
    }
    return response;
  }

  private ResponseEntity<WorkflowRunResponse> runWorkflowInternal(
      WorkflowProviderAdapter adapter, WorkflowRunRequest request) {
    return adapter.runWorkflow(request);
  }

  private ResponseEntity<WorkflowRunResponse> runAwsWorkflow(WorkflowRunRequest request) {
    String payload = buildWorkflowPayload(request.getInput());
    if (payload == null) {
      return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
          .body(WorkflowRunResponse.failed("Failed to serialize workflow payload"));
    }

    try (SfnClient sfnClient = buildSfnClient()) {
      StartExecutionRequest.Builder builder =
          StartExecutionRequest.builder()
              .stateMachineArn(request.getWorkflowId())
              .input(payload);
      if (StringUtils.isNotBlank(request.getName())) {
        builder.name(request.getName());
      }
      StartExecutionResponse response = sfnClient.startExecution(builder.build());
      String runId =
          StringUtils.isNotBlank(response.executionArn())
              ? response.executionArn()
              : "sfn-" + Instant.now().toString();
      return ResponseEntity.ok(WorkflowRunResponse.started(runId));
    } catch (Exception e) {
      log.warn("Failed to start workflow {}", request.getWorkflowId(), e);
      return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
          .body(WorkflowRunResponse.failed("Workflow invocation failed"));
    }
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

  private String buildWorkflowPayload(String input) {
    if (StringUtils.isNotBlank(input)) {
      return input;
    }
    String actorUrn = resolveActorUrn();
    try {
      Map<String, String> payload = new HashMap<>();
      payload.put("actorUrn", actorUrn);
      payload.put("reason", "manual run");
      return OBJECT_MAPPER.writeValueAsString(payload);
    } catch (IOException e) {
      log.warn("Failed to serialize workflow payload", e);
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

  private CloudWatchLogsClient buildCloudWatchLogsClient() {
    String regionName = resolveRegionName();

    software.amazon.awssdk.services.cloudwatchlogs.CloudWatchLogsClientBuilder builder =
        CloudWatchLogsClient.builder();
    if (StringUtils.isNotBlank(regionName)) {
      builder.region(Region.of(regionName));
    }
    return builder.build();
  }

  private EcsClient buildEcsClient() {
    String regionName = resolveRegionName();

    EcsClientBuilder builder = EcsClient.builder();
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

  private int normalizeEventLimit(Integer limit) {
    if (limit == null) {
      return DEFAULT_EVENT_LIMIT;
    }
    if (limit < 1) {
      return 1;
    }
    if (limit > MAX_EVENT_LIMIT) {
      return MAX_EVENT_LIMIT;
    }
    return limit;
  }

  private int normalizeLogLimit(Integer limit) {
    if (limit == null) {
      return DEFAULT_LOG_LIMIT;
    }
    if (limit < 1) {
      return 1;
    }
    if (limit > MAX_LOG_LIMIT) {
      return MAX_LOG_LIMIT;
    }
    return limit;
  }

  private boolean isFailureStatus(String status) {
    if (StringUtils.isBlank(status)) {
      return false;
    }
    return "FAILED".equals(status) || "TIMED_OUT".equals(status) || "ABORTED".equals(status);
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

  private WorkflowExecutionOverview mapExecutionOverview(ExecutionListItem execution) {
    WorkflowExecutionOverview overview = new WorkflowExecutionOverview();
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

  private ExecutionOverview mapExecutionOverview(WorkflowExecutionOverview execution) {
    ExecutionOverview overview = new ExecutionOverview();
    overview.setArn(execution.getArn());
    overview.setName(execution.getName());
    overview.setStatus(execution.getStatus());
    overview.setStartTime(execution.getStartTime());
    overview.setStopTime(execution.getStopTime());
    overview.setDurationMs(execution.getDurationMs());
    return overview;
  }

  private WorkflowGraph buildWorkflowGraph(
      String definitionJson, String regionName, WorkflowProvider provider) {
    WorkflowGraph graph = new WorkflowGraph();
    graph.setNodes(Collections.emptyList());
    graph.setEdges(Collections.emptyList());
    if (StringUtils.isBlank(definitionJson)) {
      return graph;
    }
    try {
      Map<String, Object> definition =
          OBJECT_MAPPER.readValue(definitionJson, new TypeReference<Map<String, Object>>() {});
      String startAt = definition.get("StartAt") instanceof String ? (String) definition.get("StartAt") : null;
      graph.setStartAt(startAt);
      Map<String, Object> states = asMap(definition.get("States"));
      if (states == null || states.isEmpty()) {
        return graph;
      }
      WorkflowGraphBuilder builder = new WorkflowGraphBuilder(regionName, provider);
      builder.parseStates(states);
      graph.setNodes(new ArrayList<>(builder.nodes.values()));
      graph.setEdges(builder.edges);
      return graph;
    } catch (Exception e) {
      log.warn("Failed to parse workflow definition", e);
      return graph;
    }
  }

  private Map<String, WorkflowStateStatus> buildStateStatuses(
      List<HistoryEvent> events, String executionStatus) {
    Map<String, WorkflowStateStatus> statuses = new LinkedHashMap<>();
    String lastEntered = null;

    for (HistoryEvent event : events) {
      if (event == null || event.type() == null) {
        continue;
      }
      HistoryEventType type = event.type();
      String stateName = extractStateName(event);
      Long timestamp = event.timestamp() != null ? event.timestamp().toEpochMilli() : null;

      switch (type) {
        case TASK_STATE_ENTERED:
        case CHOICE_STATE_ENTERED:
        case PARALLEL_STATE_ENTERED:
        case MAP_STATE_ENTERED:
        case PASS_STATE_ENTERED:
        case WAIT_STATE_ENTERED:
          lastEntered = stateName;
          WorkflowStateStatus entered = markStateStatus(statuses, stateName, "RUNNING", timestamp);
          if (entered != null && timestamp != null && entered.getStartTime() == null) {
            entered.setStartTime(timestamp);
          }
          break;
        case SUCCEED_STATE_ENTERED:
          lastEntered = stateName;
          WorkflowStateStatus succeeded = markStateStatus(statuses, stateName, "SUCCEEDED", timestamp);
          if (succeeded != null && timestamp != null && succeeded.getStartTime() == null) {
            succeeded.setStartTime(timestamp);
          }
          break;
        case FAIL_STATE_ENTERED:
          lastEntered = stateName;
          WorkflowStateStatus failed = markStateStatus(statuses, stateName, "FAILED", timestamp);
          if (failed != null && timestamp != null && failed.getStartTime() == null) {
            failed.setStartTime(timestamp);
          }
          break;
        case TASK_STATE_EXITED:
        case CHOICE_STATE_EXITED:
        case PARALLEL_STATE_EXITED:
        case MAP_STATE_EXITED:
        case PASS_STATE_EXITED:
        case WAIT_STATE_EXITED:
          if (stateName != null && stateName.equals(lastEntered)) {
            lastEntered = null;
          }
          WorkflowStateStatus exited = ensureStateStatus(statuses, stateName);
          if (exited != null) {
            if (StringUtils.isBlank(exited.getStatus()) || "RUNNING".equals(exited.getStatus())) {
              exited.setStatus("SUCCEEDED");
            }
            if (timestamp != null) {
              exited.setEndTime(timestamp);
              exited.setLastUpdated(timestamp);
            }
          }
          break;
        default:
          break;
      }
    }

    if (isFailureStatus(executionStatus) && StringUtils.isNotBlank(lastEntered)) {
      markStateStatus(statuses, lastEntered, "FAILED", null);
    }
    return statuses;
  }

  private WorkflowStateStatus markStateStatus(
      Map<String, WorkflowStateStatus> statuses, String stateName, String status, Long timestamp) {
    if (StringUtils.isBlank(stateName)) {
      return null;
    }
    WorkflowStateStatus current = ensureStateStatus(statuses, stateName);
    if (current == null) {
      return null;
    }
    current.setStatus(status);
    if (timestamp != null) {
      current.setLastUpdated(timestamp);
    }
    return current;
  }

  private WorkflowStateStatus ensureStateStatus(
      Map<String, WorkflowStateStatus> statuses, String stateName) {
    if (StringUtils.isBlank(stateName)) {
      return null;
    }
    WorkflowStateStatus current = statuses.get(stateName);
    if (current == null) {
      current = new WorkflowStateStatus();
      current.setStateName(stateName);
      statuses.put(stateName, current);
    }
    return current;
  }

  private String extractStateName(HistoryEvent event) {
    if (event.stateEnteredEventDetails() != null) {
      return event.stateEnteredEventDetails().name();
    }
    if (event.stateExitedEventDetails() != null) {
      return event.stateExitedEventDetails().name();
    }
    return null;
  }

  private WorkflowLogsResult fetchExecutionLogs(
      DescribeExecutionResponse execution,
      DescribeStateMachineResponse stateMachine,
      int logLimit,
      Long startTimeMs) {
    WorkflowLogsResult result = new WorkflowLogsResult();
    result.setLogs(Collections.emptyList());

    if (execution == null || StringUtils.isBlank(execution.stateMachineArn())) {
      result.setError("Missing state machine ARN for logs lookup");
      return result;
    }

    if (stateMachine == null) {
      result.setError("Missing state machine configuration");
      return result;
    }

    String logGroupArn = null;
    if (stateMachine.loggingConfiguration() != null
        && stateMachine.loggingConfiguration().destinations() != null) {
      for (software.amazon.awssdk.services.sfn.model.LogDestination destination :
          stateMachine.loggingConfiguration().destinations()) {
        if (destination != null
            && destination.cloudWatchLogsLogGroup() != null
            && StringUtils.isNotBlank(destination.cloudWatchLogsLogGroup().logGroupArn())) {
          logGroupArn = destination.cloudWatchLogsLogGroup().logGroupArn();
          break;
        }
      }
    }

    String logGroupName = extractLogGroupName(logGroupArn);
    if (StringUtils.isBlank(logGroupName)) {
      result.setError("No CloudWatch Logs group configured");
      return result;
    }

    try (CloudWatchLogsClient logsClient = buildCloudWatchLogsClient()) {
      FilterLogEventsRequest.Builder builder =
          FilterLogEventsRequest.builder()
              .logGroupName(logGroupName)
              .limit(logLimit)
              .filterPattern(execution.executionArn());
      if (startTimeMs != null) {
        builder.startTime(startTimeMs);
      }
      if (execution.stopDate() != null) {
        builder.endTime(execution.stopDate().toEpochMilli());
      }
      FilterLogEventsResponse response = logsClient.filterLogEvents(builder.build());
      List<WorkflowLogEvent> events = new ArrayList<>();
      for (FilteredLogEvent event : response.events()) {
        WorkflowLogEvent logEvent = new WorkflowLogEvent();
        logEvent.setTimestamp(event.timestamp());
        logEvent.setMessage(event.message());
        events.add(logEvent);
      }
      result.setLogs(events);
      result.setLogUrl(
          buildCloudWatchLogsUrl(
              logGroupName,
              resolveRegionName(),
              startTimeMs,
              execution.stopDate() != null ? execution.stopDate().toEpochMilli() : null,
              execution.executionArn(),
              null));
    } catch (Exception e) {
      log.warn("Failed to fetch CloudWatch logs for {}", execution.executionArn(), e);
      result.setError("Failed to fetch CloudWatch logs");
    }
    return result;
  }

  private List<WorkflowTaskLog> fetchTaskLogs(
      DescribeExecutionResponse execution,
      WorkflowGraph graph,
      Map<String, WorkflowStateStatus> statuses,
      int logLimit) {
    if (graph == null || graph.getNodes() == null || graph.getNodes().isEmpty()) {
      return Collections.emptyList();
    }
    List<WorkflowNode> taskNodes = new ArrayList<>();
    for (WorkflowNode node : graph.getNodes()) {
      if (node == null) {
        continue;
      }
      if (StringUtils.isNotBlank(node.getResourceType()) && StringUtils.isNotBlank(node.getResource())) {
        taskNodes.add(node);
      }
    }
    if (taskNodes.isEmpty()) {
      return Collections.emptyList();
    }

    int perTaskLimit =
        Math.max(1, Math.min(logLimit, Math.max(1, logLimit / Math.max(1, taskNodes.size()))));
    List<WorkflowTaskLog> results = new ArrayList<>();

    boolean needsEcs = taskNodes.stream().anyMatch(node -> isEcsResource(node.getResourceType()));

    try (CloudWatchLogsClient logsClient = buildCloudWatchLogsClient();
        EcsClient ecsClient = needsEcs ? buildEcsClient() : null) {
      for (WorkflowNode node : taskNodes) {
        WorkflowStateStatus status = statuses.get(node.getId());
        WorkflowTaskLog taskLog = new WorkflowTaskLog();
        taskLog.setStateName(node.getId());
        taskLog.setStatus(status != null ? status.getStatus() : null);
        taskLog.setResourceType(node.getResourceType());
        taskLog.setResource(node.getResource());
        taskLog.setResourceUrl(node.getResourceUrl());
        taskLog.setLogs(Collections.emptyList());

        Long startTime = status != null ? status.getStartTime() : null;
        if (startTime == null) {
          startTime = execution.startDate() != null ? execution.startDate().toEpochMilli() : null;
        }
        Long endTime = status != null ? status.getEndTime() : null;
        if (endTime == null) {
          endTime = execution.stopDate() != null ? execution.stopDate().toEpochMilli() : null;
        }
        if (startTime != null) {
          startTime = Math.max(0L, startTime - LOG_WINDOW_PADDING_MS);
        }
        if (endTime != null) {
          endTime = endTime + LOG_WINDOW_PADDING_MS;
        }

        if ("lambda".equalsIgnoreCase(node.getResourceType())) {
          String functionName = resolveLambdaFunctionName(node.getResource());
          String logGroup = functionName != null ? "/aws/lambda/" + functionName : null;
          if (StringUtils.isBlank(logGroup)) {
            taskLog.setLogsError("No Lambda log group found");
          } else {
            taskLog.setLogGroup(logGroup);
            taskLog.setLogUrl(
                buildCloudWatchLogsUrl(
                    logGroup, resolveRegionName(), startTime, endTime, null, null));
            WorkflowLogsResult logResult =
                fetchLogEvents(
                    logsClient, logGroup, startTime, endTime, perTaskLimit, null, null);
            taskLog.setLogs(logResult.getLogs());
            taskLog.setLogsError(logResult.getError());
          }
        } else if (isEcsResource(node.getResourceType())) {
          EcsLogConfiguration config =
              ecsClient != null ? resolveEcsLogConfiguration(ecsClient, node.getResource()) : null;
          if (config == null || StringUtils.isBlank(config.getLogGroup())) {
            taskLog.setLogsError("No ECS log configuration found");
          } else {
            taskLog.setLogGroup(config.getLogGroup());
            taskLog.setLogStream(config.getLogStreamPrefix());
            taskLog.setLogUrl(
                buildCloudWatchLogsUrl(
                    config.getLogGroup(),
                    config.getRegion() != null ? config.getRegion() : resolveRegionName(),
                    startTime,
                    endTime,
                    null,
                    config.getLogStreamPrefix()));
            WorkflowLogsResult logResult =
                fetchLogEvents(
                    logsClient,
                    config.getLogGroup(),
                    startTime,
                    endTime,
                    perTaskLimit,
                    config.getLogStreamPrefix(),
                    null);
            taskLog.setLogs(logResult.getLogs());
            taskLog.setLogsError(logResult.getError());
          }
        }
        results.add(taskLog);
      }
    } catch (Exception e) {
      log.warn("Failed to fetch task logs for {}", execution.executionArn(), e);
    }
    return results;
  }

  private WorkflowLogsResult fetchLogEvents(
      CloudWatchLogsClient logsClient,
      String logGroupName,
      Long startTime,
      Long endTime,
      int limit,
      String logStreamPrefix,
      String filterPattern) {
    WorkflowLogsResult result = new WorkflowLogsResult();
    result.setLogs(Collections.emptyList());
    if (logsClient == null || StringUtils.isBlank(logGroupName)) {
      result.setError("Missing log group");
      return result;
    }
    try {
      FilterLogEventsRequest.Builder builder =
          FilterLogEventsRequest.builder().logGroupName(logGroupName).limit(limit);
      if (startTime != null) {
        builder.startTime(startTime);
      }
      if (endTime != null) {
        builder.endTime(endTime);
      }
      if (StringUtils.isNotBlank(logStreamPrefix)) {
        builder.logStreamNamePrefix(logStreamPrefix);
      }
      if (StringUtils.isNotBlank(filterPattern)) {
        builder.filterPattern(filterPattern);
      }
      FilterLogEventsResponse response = logsClient.filterLogEvents(builder.build());
      List<WorkflowLogEvent> events = new ArrayList<>();
      for (FilteredLogEvent event : response.events()) {
        WorkflowLogEvent logEvent = new WorkflowLogEvent();
        logEvent.setTimestamp(event.timestamp());
        logEvent.setMessage(event.message());
        events.add(logEvent);
      }
      result.setLogs(events);
    } catch (Exception e) {
      result.setError("Failed to fetch logs");
    }
    return result;
  }

  private boolean isEcsResource(String resourceType) {
    if (StringUtils.isBlank(resourceType)) {
      return false;
    }
    String normalized = resourceType.toLowerCase();
    return normalized.contains("ecs") || normalized.contains("fargate");
  }

  private String resolveLambdaFunctionName(String resource) {
    if (StringUtils.isBlank(resource)) {
      return null;
    }
    if (!resource.startsWith("arn:")) {
      return resource;
    }
    String[] parts = resource.split(":", 7);
    if (parts.length < 7) {
      return resource;
    }
    String rest = parts[6];
    if (rest.startsWith("function:")) {
      rest = rest.substring("function:".length());
    }
    int aliasIndex = rest.indexOf(':');
    if (aliasIndex >= 0) {
      rest = rest.substring(0, aliasIndex);
    }
    return rest;
  }

  private EcsLogConfiguration resolveEcsLogConfiguration(EcsClient ecsClient, String taskDefinition) {
    if (ecsClient == null || StringUtils.isBlank(taskDefinition)) {
      return null;
    }
    try {
      DescribeTaskDefinitionResponse response =
          ecsClient.describeTaskDefinition(
              DescribeTaskDefinitionRequest.builder().taskDefinition(taskDefinition).build());
      TaskDefinition definition = response.taskDefinition();
      if (definition == null || definition.containerDefinitions() == null) {
        return null;
      }
      for (ContainerDefinition container : definition.containerDefinitions()) {
        if (container == null || container.logConfiguration() == null) {
          continue;
        }
        LogConfiguration config = container.logConfiguration();
        if (config.options() == null) {
          continue;
        }
        String logGroup = config.options().get("awslogs-group");
        String streamPrefix = config.options().get("awslogs-stream-prefix");
        String region = config.options().get("awslogs-region");
        if (StringUtils.isNotBlank(logGroup)) {
          EcsLogConfiguration result = new EcsLogConfiguration();
          result.setLogGroup(logGroup);
          result.setLogStreamPrefix(streamPrefix);
          result.setRegion(region);
          return result;
        }
      }
    } catch (Exception e) {
      log.warn("Failed to describe ECS task definition {}", taskDefinition, e);
    }
    return null;
  }

  private String buildCloudWatchLogsUrl(
      String logGroupName,
      String region,
      Long startTime,
      Long endTime,
      String filterPattern,
      String logStreamPrefix) {
    if (StringUtils.isBlank(logGroupName)) {
      return null;
    }
    String resolvedRegion = StringUtils.isNotBlank(region) ? region : resolveRegionName();
    if (StringUtils.isBlank(resolvedRegion)) {
      resolvedRegion = "us-east-1";
    }
    String encodedGroup = encodeCloudWatchComponent(logGroupName);
    StringBuilder fragment = new StringBuilder("logsV2:log-groups/log-group/").append(encodedGroup);
    List<String> params = new ArrayList<>();
    if (startTime != null) {
      params.add("start=" + startTime);
    }
    if (endTime != null) {
      params.add("end=" + endTime);
    }
    if (StringUtils.isNotBlank(filterPattern)) {
      params.add("filterPattern=" + filterPattern);
    }
    if (StringUtils.isNotBlank(logStreamPrefix)) {
      params.add("logStreamNamePrefix=" + logStreamPrefix);
    }
    if (!params.isEmpty()) {
      String query = String.join("&", params);
      fragment.append("/log-events$3F").append(encodeCloudWatchComponent(query));
    }
    return String.format(
        "https://console.aws.amazon.com/cloudwatch/home?region=%s#%s",
        resolvedRegion,
        fragment);
  }

  private String encodeCloudWatchComponent(String value) {
    if (StringUtils.isBlank(value)) {
      return value;
    }
    String encoded = URLEncoder.encode(value, StandardCharsets.UTF_8);
    encoded = encoded.replace("+", "%20");
    return encoded.replace("%", "$");
  }

  private String extractLogGroupName(String logGroupArn) {
    if (StringUtils.isBlank(logGroupArn)) {
      return null;
    }
    String marker = ":log-group:";
    int idx = logGroupArn.indexOf(marker);
    if (idx < 0) {
      return null;
    }
    String remainder = logGroupArn.substring(idx + marker.length());
    int streamIdx = remainder.indexOf(":log-stream:");
    if (streamIdx >= 0) {
      remainder = remainder.substring(0, streamIdx);
    }
    return remainder;
  }

  @SuppressWarnings("unchecked")
  private Map<String, Object> asMap(Object value) {
    if (value instanceof Map) {
      return (Map<String, Object>) value;
    }
    return null;
  }

  private static class WorkflowGraphBuilder {
    private final String regionName;
    private final WorkflowProvider provider;
    private final Map<String, WorkflowNode> nodes = new LinkedHashMap<>();
    private final List<WorkflowEdge> edges = new ArrayList<>();
    private final Set<String> edgeKeys = new HashSet<>();

    WorkflowGraphBuilder(String regionName, WorkflowProvider provider) {
      this.regionName = regionName;
      this.provider = provider;
    }

    void parseStates(Map<String, Object> states) {
      if (states == null) {
        return;
      }
      for (Map.Entry<String, Object> entry : states.entrySet()) {
        String name = entry.getKey();
        Map<String, Object> state = asMapStatic(entry.getValue());
        String type = state != null && state.get("Type") instanceof String ? (String) state.get("Type") : null;
        addNode(name, type, state);
      }
      for (Map.Entry<String, Object> entry : states.entrySet()) {
        String name = entry.getKey();
        Map<String, Object> state = asMapStatic(entry.getValue());
        if (state == null) {
          continue;
        }
        String type = state.get("Type") instanceof String ? (String) state.get("Type") : null;
        String next = state.get("Next") instanceof String ? (String) state.get("Next") : null;
        boolean end = Boolean.TRUE.equals(state.get("End"));
        if ("Choice".equals(type)) {
          addChoiceEdges(name, state);
          continue;
        }
        if ("Parallel".equals(type)) {
          Set<String> terminals = addParallelEdges(name, state);
          if (next != null) {
            for (String terminal : terminals) {
              addEdge(terminal, next, "join");
            }
          } else if (end) {
            // parallel itself is terminal
          }
          continue;
        }
        if ("Map".equals(type)) {
          Set<String> terminals = addMapEdges(name, state);
          if (next != null) {
            for (String terminal : terminals) {
              addEdge(terminal, next, "next");
            }
          } else if (end) {
            // map itself is terminal
          }
          continue;
        }
        if (next != null) {
          addEdge(name, next, "next");
        }
      }
    }

    private void addNode(String id, String type) {
      addNode(id, type, null);
    }

    private void addNode(String id, String type, Map<String, Object> state) {
      if (StringUtils.isBlank(id)) {
        return;
      }
      if (!nodes.containsKey(id)) {
        WorkflowNode node = new WorkflowNode();
        node.setId(id);
        node.setLabel(id);
        node.setType(type);
        if ("Task".equals(type)) {
          WorkflowResource resource = extractTaskResource(state);
          if (resource != null) {
            node.setResource(resource.getResource());
            node.setResourceType(resource.getResourceType());
            node.setResourceUrl(resource.getResourceUrl());
          }
        }
        nodes.put(id, node);
      }
    }

    private void addEdge(String from, String to, String type) {
      if (StringUtils.isBlank(from) || StringUtils.isBlank(to)) {
        return;
      }
      String key = from + "->" + to + ":" + type;
      if (edgeKeys.contains(key)) {
        return;
      }
      WorkflowEdge edge = new WorkflowEdge();
      edge.setFrom(from);
      edge.setTo(to);
      edge.setType(type);
      edges.add(edge);
      edgeKeys.add(key);
    }

    private void addChoiceEdges(String from, Map<String, Object> state) {
      Object choicesObj = state.get("Choices");
      if (choicesObj instanceof List) {
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> choices = (List<Map<String, Object>>) choicesObj;
        for (Map<String, Object> choice : choices) {
          if (choice == null) continue;
          String next = choice.get("Next") instanceof String ? (String) choice.get("Next") : null;
          addEdge(from, next, "choice");
        }
      }
      String defaultNext = state.get("Default") instanceof String ? (String) state.get("Default") : null;
      addEdge(from, defaultNext, "default");
    }

    private Set<String> addParallelEdges(String from, Map<String, Object> state) {
      Set<String> terminals = new HashSet<>();
      Object branchesObj = state.get("Branches");
      if (!(branchesObj instanceof List)) {
        return terminals;
      }
      @SuppressWarnings("unchecked")
      List<Map<String, Object>> branches = (List<Map<String, Object>>) branchesObj;
      for (Map<String, Object> branch : branches) {
        if (branch == null) continue;
        String startAt = branch.get("StartAt") instanceof String ? (String) branch.get("StartAt") : null;
        Map<String, Object> branchStates = asMapStatic(branch.get("States"));
        if (startAt != null) {
          addEdge(from, startAt, "branch");
        }
        if (branchStates != null) {
          WorkflowGraphBuilder nested = new WorkflowGraphBuilder(regionName, provider);
          nested.parseStates(branchStates);
          nodes.putAll(nested.nodes);
          edges.addAll(nested.edges);
          edgeKeys.addAll(nested.edgeKeys);
          terminals.addAll(findTerminalStates(branchStates));
        }
      }
      return terminals;
    }

    private Set<String> addMapEdges(String from, Map<String, Object> state) {
      Set<String> terminals = new HashSet<>();
      Map<String, Object> iterator = asMapStatic(state.get("Iterator"));
      if (iterator == null) {
        iterator = asMapStatic(state.get("ItemProcessor"));
      }
      if (iterator == null) {
        return terminals;
      }
      String startAt = iterator.get("StartAt") instanceof String ? (String) iterator.get("StartAt") : null;
      Map<String, Object> iteratorStates = asMapStatic(iterator.get("States"));
      if (startAt != null) {
        addEdge(from, startAt, "iterator");
      }
      if (iteratorStates != null) {
        WorkflowGraphBuilder nested = new WorkflowGraphBuilder(regionName, provider);
        nested.parseStates(iteratorStates);
        nodes.putAll(nested.nodes);
        edges.addAll(nested.edges);
        edgeKeys.addAll(nested.edgeKeys);
        terminals.addAll(findTerminalStates(iteratorStates));
      }
      return terminals;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMapStatic(Object value) {
      if (value instanceof Map) {
        return (Map<String, Object>) value;
      }
      return null;
    }

    private Set<String> findTerminalStates(Map<String, Object> states) {
      Set<String> terminals = new HashSet<>();
      if (states == null) return terminals;
      for (Map.Entry<String, Object> entry : states.entrySet()) {
        Map<String, Object> state = asMapStatic(entry.getValue());
        if (state == null) continue;
        String type = state.get("Type") instanceof String ? (String) state.get("Type") : null;
        String next = state.get("Next") instanceof String ? (String) state.get("Next") : null;
        boolean end = Boolean.TRUE.equals(state.get("End"));
        if ("Choice".equals(type)) {
          continue;
        }
        if ("Parallel".equals(type)) {
          Set<String> branchTerminals = new HashSet<>();
          Object branchesObj = state.get("Branches");
          if (branchesObj instanceof List) {
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> branches = (List<Map<String, Object>>) branchesObj;
            for (Map<String, Object> branch : branches) {
              if (branch == null) continue;
              branchTerminals.addAll(findTerminalStates(asMapStatic(branch.get("States"))));
            }
          }
          if (!branchTerminals.isEmpty()) {
            terminals.addAll(branchTerminals);
          } else if (end || next == null) {
            terminals.add(entry.getKey());
          }
          continue;
        }
        if ("Map".equals(type)) {
          Set<String> iteratorTerminals = new HashSet<>();
          Map<String, Object> iterator = asMapStatic(state.get("Iterator"));
          if (iterator == null) {
            iterator = asMapStatic(state.get("ItemProcessor"));
          }
          iteratorTerminals.addAll(
              findTerminalStates(iterator != null ? asMapStatic(iterator.get("States")) : null));
          if (!iteratorTerminals.isEmpty()) {
            terminals.addAll(iteratorTerminals);
          } else if (end || next == null) {
            terminals.add(entry.getKey());
          }
          continue;
        }
        if (end || next == null) {
          terminals.add(entry.getKey());
        }
      }
      return terminals;
    }

    private WorkflowResource extractTaskResource(Map<String, Object> state) {
      if (state == null) {
        return null;
      }
      String resource = state.get("Resource") instanceof String ? (String) state.get("Resource") : null;
      if (StringUtils.isBlank(resource)) {
        return null;
      }
      if (provider != WorkflowProvider.AWS_STEP_FUNCTIONS) {
        return null;
      }
      if (resource.startsWith("arn:aws:states:::lambda:") || resource.startsWith("arn:aws:lambda:")) {
        return buildLambdaResource(resource, state);
      }
      if (resource.startsWith("arn:aws:states:::ecs:") || resource.startsWith("arn:aws:ecs:")) {
        return buildEcsResource(resource, state);
      }
      return null;
    }

    private WorkflowResource buildLambdaResource(String resource, Map<String, Object> state) {
      String resolvedResource = resource;
      if (resource.startsWith("arn:aws:states:::lambda:")) {
        Map<String, Object> parameters = asMapStatic(state.get("Parameters"));
        if (parameters != null) {
          Object functionName = parameters.get("FunctionName");
          if (functionName == null) {
            functionName = parameters.get("FunctionArn");
          }
          if (functionName instanceof String) {
            resolvedResource = (String) functionName;
          }
        }
      }
      if (StringUtils.isBlank(resolvedResource)) {
        return null;
      }
      WorkflowResource result = new WorkflowResource();
      result.setResource(resolvedResource);
      result.setResourceType("lambda");
      result.setResourceUrl(buildLambdaConsoleUrl(resolvedResource));
      return result;
    }

    private WorkflowResource buildEcsResource(String resource, Map<String, Object> state) {
      Map<String, Object> parameters = asMapStatic(state.get("Parameters"));
      String taskDefinition = getStringValue(parameters, "TaskDefinition");
      if (taskDefinition == null) {
        taskDefinition = getStringValue(parameters, "TaskDefinitionArn");
      }
      String cluster = getStringValue(parameters, "Cluster");
      String launchType = getStringValue(parameters, "LaunchType");

      String link = buildEcsConsoleUrl(taskDefinition, cluster);
      if (StringUtils.isBlank(link)) {
        return null;
      }
      WorkflowResource result = new WorkflowResource();
      result.setResource(taskDefinition != null ? taskDefinition : cluster);
      if (StringUtils.isNotBlank(launchType) && "FARGATE".equalsIgnoreCase(launchType)) {
        result.setResourceType("fargate_task");
      } else {
        result.setResourceType("ecs_task");
      }
      result.setResourceUrl(link);
      return result;
    }

    private String buildLambdaConsoleUrl(String resource) {
      if (StringUtils.isBlank(resource)) {
        return null;
      }
      String region = regionName;
      String functionName = resource;
      if (resource.startsWith("arn:")) {
        String[] parts = resource.split(":", 7);
        if (parts.length >= 7 && "lambda".equals(parts[2])) {
          region = parts[3];
          String rest = parts[6];
          if (rest.startsWith("function:")) {
            functionName = rest.substring("function:".length());
          } else {
            functionName = rest;
          }
          int aliasIndex = functionName.indexOf(':');
          if (aliasIndex >= 0) {
            functionName = functionName.substring(0, aliasIndex);
          }
        }
      }
      if (StringUtils.isBlank(region)) {
        region = "us-east-1";
      }
      return String.format(
          "https://console.aws.amazon.com/lambda/home?region=%s#/functions/%s",
          region,
          functionName);
    }

    private String buildEcsConsoleUrl(String taskDefinition, String cluster) {
      String region = regionName;
      String taskDefValue = taskDefinition;
      if (StringUtils.isNotBlank(taskDefValue)) {
        if (taskDefValue.startsWith("arn:")) {
          region = extractArnRegion(taskDefValue, region);
          taskDefValue = extractTaskDefinitionName(taskDefValue);
        }
      }
      String clusterValue = cluster;
      if (StringUtils.isNotBlank(clusterValue)) {
        if (clusterValue.startsWith("arn:")) {
          region = extractArnRegion(clusterValue, region);
          clusterValue = extractClusterName(clusterValue);
        }
      }
      if (StringUtils.isBlank(region)) {
        region = "us-east-1";
      }
      if (StringUtils.isNotBlank(taskDefValue)) {
        String encoded = urlEncode(taskDefValue);
        return String.format(
            "https://console.aws.amazon.com/ecs/home?region=%s#/taskDefinitions/%s",
            region,
            encoded);
      }
      if (StringUtils.isNotBlank(clusterValue)) {
        String encoded = urlEncode(clusterValue);
        return String.format(
            "https://console.aws.amazon.com/ecs/home?region=%s#/clusters/%s/tasks",
            region,
            encoded);
      }
      return null;
    }

    private String extractArnRegion(String arn, String fallback) {
      if (StringUtils.isBlank(arn) || !arn.startsWith("arn:")) {
        return fallback;
      }
      String[] parts = arn.split(":", 6);
      if (parts.length >= 4 && StringUtils.isNotBlank(parts[3])) {
        return parts[3];
      }
      return fallback;
    }

    private String extractTaskDefinitionName(String taskDefinition) {
      if (StringUtils.isBlank(taskDefinition)) {
        return null;
      }
      if (!taskDefinition.startsWith("arn:")) {
        return taskDefinition;
      }
      String[] parts = taskDefinition.split(":", 6);
      if (parts.length < 6) {
        return taskDefinition;
      }
      String resource = parts[5];
      int slashIndex = resource.indexOf('/');
      if (slashIndex >= 0 && slashIndex < resource.length() - 1) {
        return resource.substring(slashIndex + 1);
      }
      return resource;
    }

    private String extractClusterName(String cluster) {
      if (StringUtils.isBlank(cluster)) {
        return null;
      }
      if (!cluster.startsWith("arn:")) {
        return cluster;
      }
      String[] parts = cluster.split(":", 6);
      if (parts.length < 6) {
        return cluster;
      }
      String resource = parts[5];
      int slashIndex = resource.indexOf('/');
      if (slashIndex >= 0 && slashIndex < resource.length() - 1) {
        return resource.substring(slashIndex + 1);
      }
      return resource;
    }

    private String urlEncode(String value) {
      if (StringUtils.isBlank(value)) {
        return value;
      }
      return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private String getStringValue(Map<String, Object> map, String key) {
      if (map == null || key == null) {
        return null;
      }
      Object value = map.get(key);
      return value instanceof String ? (String) value : null;
    }
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

  private enum WorkflowProvider {
    AWS_STEP_FUNCTIONS("aws_stepfunctions"),
    GCP_WORKFLOWS("gcp_workflows");

    private final String id;

    WorkflowProvider(String id) {
      this.id = id;
    }

    public String getId() {
      return id;
    }

    public static WorkflowProvider from(String value) {
      if (StringUtils.isBlank(value)) {
        return AWS_STEP_FUNCTIONS;
      }
      String normalized = value.trim().toLowerCase();
      if ("aws".equals(normalized)
          || "aws-stepfunctions".equals(normalized)
          || "aws_stepfunctions".equals(normalized)
          || "stepfunctions".equals(normalized)
          || "step-functions".equals(normalized)) {
        return AWS_STEP_FUNCTIONS;
      }
      if ("gcp".equals(normalized)
          || "gcp-workflows".equals(normalized)
          || "gcp_workflows".equals(normalized)
          || "cloud-workflows".equals(normalized)
          || "cloud_workflows".equals(normalized)
          || "cloudworkflow".equals(normalized)
          || "cloud-workflow".equals(normalized)) {
        return GCP_WORKFLOWS;
      }
      return null;
    }
  }

  private WorkflowProviderAdapter resolveProviderAdapter(WorkflowProvider provider) {
    if (provider == null) {
      return new UnsupportedWorkflowProviderAdapter(null, "Unsupported workflow provider");
    }
    if (provider == WorkflowProvider.AWS_STEP_FUNCTIONS) {
      return new AwsStepFunctionsAdapter();
    }
    if (provider == WorkflowProvider.GCP_WORKFLOWS) {
      return new UnsupportedWorkflowProviderAdapter(
          provider, "GCP workflows are not configured in this deployment");
    }
    return new UnsupportedWorkflowProviderAdapter(provider, "Unsupported workflow provider");
  }

  private interface WorkflowProviderAdapter {
    WorkflowProvider getProvider();

    default String getRegion() {
      return null;
    }

    default boolean isSupported() {
      return true;
    }

    default String getUnsupportedMessage() {
      return "Unsupported workflow provider";
    }

    List<WorkflowOverview> listWorkflows(int executionLimit) throws Exception;

    WorkflowDetailResponse getWorkflowDetail(String workflowId, int executionLimit);

    WorkflowExecutionDetailResponse getExecutionDetail(
        String executionArn, int maxEvents, boolean includeLogs, int logLimit);

    ResponseEntity<WorkflowRunResponse> runWorkflow(WorkflowRunRequest request);
  }

  private class AwsStepFunctionsAdapter implements WorkflowProviderAdapter {
    @Override
    public WorkflowProvider getProvider() {
      return WorkflowProvider.AWS_STEP_FUNCTIONS;
    }

    @Override
    public String getRegion() {
      return resolveRegionName();
    }

    @Override
    public List<WorkflowOverview> listWorkflows(int executionLimit) {
      return loadStepFunctionsOverview(executionLimit);
    }

    @Override
    public WorkflowDetailResponse getWorkflowDetail(String workflowId, int executionLimit) {
      return loadAwsWorkflowDetail(workflowId, executionLimit);
    }

    @Override
    public WorkflowExecutionDetailResponse getExecutionDetail(
        String executionArn, int maxEvents, boolean includeLogs, int logLimit) {
      return loadAwsWorkflowExecutionDetail(executionArn, maxEvents, includeLogs, logLimit);
    }

    @Override
    public ResponseEntity<WorkflowRunResponse> runWorkflow(WorkflowRunRequest request) {
      return runAwsWorkflow(request);
    }
  }

  private static class UnsupportedWorkflowProviderAdapter implements WorkflowProviderAdapter {
    private final WorkflowProvider provider;
    private final String message;

    private UnsupportedWorkflowProviderAdapter(WorkflowProvider provider, String message) {
      this.provider = provider;
      this.message = message;
    }

    @Override
    public WorkflowProvider getProvider() {
      return provider != null ? provider : WorkflowProvider.AWS_STEP_FUNCTIONS;
    }

    @Override
    public boolean isSupported() {
      return false;
    }

    @Override
    public String getUnsupportedMessage() {
      return message;
    }

    @Override
    public List<WorkflowOverview> listWorkflows(int executionLimit) {
      return Collections.emptyList();
    }

    @Override
    public WorkflowDetailResponse getWorkflowDetail(String workflowId, int executionLimit) {
      WorkflowDetailResponse response = WorkflowDetailResponse.error(message);
      response.setProvider(getProvider().getId());
      response.setId(workflowId);
      return response;
    }

    @Override
    public WorkflowExecutionDetailResponse getExecutionDetail(
        String executionArn, int maxEvents, boolean includeLogs, int logLimit) {
      WorkflowExecutionDetailResponse response = WorkflowExecutionDetailResponse.error(message);
      response.setProvider(getProvider().getId());
      response.setExecutionArn(executionArn);
      return response;
    }

    @Override
    public ResponseEntity<WorkflowRunResponse> runWorkflow(WorkflowRunRequest request) {
      return ResponseEntity.status(HttpStatus.BAD_REQUEST)
          .body(WorkflowRunResponse.failed(message));
    }
  }

  @Data
  private static class CachedWorkflowOverviewResponse {
    private final long generatedAt;
    private final WorkflowOverviewResponse response;
  }

  @Data
  public static class WorkflowOverviewResponse {
    private String provider;
    private String region;
    private long generatedAt;
    private int executionLimit;
    private int totalWorkflows;
    private String error;
    private List<WorkflowOverview> workflows;

    public static WorkflowOverviewResponse error(String message) {
      WorkflowOverviewResponse response = new WorkflowOverviewResponse();
      response.setError(message);
      response.setWorkflows(Collections.emptyList());
      response.setTotalWorkflows(0);
      return response;
    }
  }

  @Data
  public static class WorkflowOverview {
    private String provider;
    private String id;
    private String name;
    private String status;
    private String type;
    private Long createdAt;
    private String error;
    private List<WorkflowExecutionOverview> executions;
  }

  @Data
  public static class WorkflowExecutionOverview {
    private String arn;
    private String name;
    private String status;
    private Long startTime;
    private Long stopTime;
    private Long durationMs;
    private String error;
    private String cause;
  }

  @Data
  public static class WorkflowDetailResponse {
    private String provider;
    private String id;
    private String name;
    private String status;
    private String type;
    private Long createdAt;
    private String definition;
    private WorkflowGraph graph;
    private List<WorkflowExecutionOverview> executions;
    private String error;

    public static WorkflowDetailResponse error(String message) {
      WorkflowDetailResponse response = new WorkflowDetailResponse();
      response.setError(message);
      response.setExecutions(Collections.emptyList());
      WorkflowGraph graph = new WorkflowGraph();
      graph.setNodes(Collections.emptyList());
      graph.setEdges(Collections.emptyList());
      response.setGraph(graph);
      return response;
    }
  }

  @Data
  public static class WorkflowGraph {
    private String startAt;
    private List<WorkflowNode> nodes;
    private List<WorkflowEdge> edges;
  }

  @Data
  public static class WorkflowNode {
    private String id;
    private String label;
    private String type;
    private String resource;
    private String resourceType;
    private String resourceUrl;
  }

  @Data
  private static class WorkflowResource {
    private String resource;
    private String resourceType;
    private String resourceUrl;
  }

  @Data
  public static class WorkflowEdge {
    private String from;
    private String to;
    private String type;
  }

  @Data
  public static class WorkflowExecutionDetailResponse {
    private String provider;
    private String executionArn;
    private String status;
    private Long startTime;
    private Long stopTime;
    private Long durationMs;
    private String error;
    private String cause;
    private String logsError;
    private String logsUrl;
    private List<WorkflowStateStatus> stateStatuses;
    private List<WorkflowLogEvent> logs;
    private List<WorkflowTaskLog> taskLogs;

    public static WorkflowExecutionDetailResponse error(String message) {
      WorkflowExecutionDetailResponse response = new WorkflowExecutionDetailResponse();
      response.setError(message);
      response.setStateStatuses(Collections.emptyList());
      response.setLogs(Collections.emptyList());
      response.setTaskLogs(Collections.emptyList());
      return response;
    }
  }

  @Data
  public static class WorkflowStateStatus {
    private String stateName;
    private String status;
    private Long lastUpdated;
    private Long startTime;
    private Long endTime;
  }

  @Data
  public static class WorkflowLogEvent {
    private Long timestamp;
    private String message;
  }

  @Data
  public static class WorkflowTaskLog {
    private String stateName;
    private String status;
    private String resourceType;
    private String resource;
    private String resourceUrl;
    private String logGroup;
    private String logStream;
    private String logUrl;
    private String logsError;
    private List<WorkflowLogEvent> logs;
  }

  @Data
  public static class WorkflowRunRequest {
    private String provider;
    private String workflowId;
    private String input;
    private String name;
  }

  @Data
  public static class WorkflowRunResponse {
    private String runId;
    private String status;
    private String message;

    public static WorkflowRunResponse started(String runId) {
      WorkflowRunResponse response = new WorkflowRunResponse();
      response.setRunId(runId);
      response.setStatus("started");
      return response;
    }

    public static WorkflowRunResponse failed(String message) {
      WorkflowRunResponse response = new WorkflowRunResponse();
      response.setStatus("failed");
      response.setMessage(message);
      return response;
    }
  }

  @Data
  private static class WorkflowLogsResult {
    private String error;
    private String logUrl;
    private List<WorkflowLogEvent> logs;
  }

  @Data
  private static class EcsLogConfiguration {
    private String logGroup;
    private String logStreamPrefix;
    private String region;
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
