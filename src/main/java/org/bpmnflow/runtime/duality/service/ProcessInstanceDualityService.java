package org.bpmnflow.runtime.duality.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.bpmnflow.model.RuleType;
import org.bpmnflow.runtime.ResourceNotFoundException;
import org.bpmnflow.runtime.duality.doc.WfActivityDoc;
import org.bpmnflow.runtime.duality.doc.WfInstanceListingDoc;
import org.bpmnflow.runtime.duality.doc.WfProcessInstanceDoc;
import org.bpmnflow.runtime.duality.doc.WfVariableDoc;
import org.bpmnflow.runtime.duality.repository.WfInstanceListingRepository;
import org.bpmnflow.runtime.duality.repository.WfProcessInstanceDualityRepository;
import org.bpmnflow.runtime.dto.*;
import org.bpmnflow.runtime.model.entity.*;
import org.bpmnflow.runtime.repository.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Process instance service backed by the Duality View repository.
 *
 * <p>Parallel implementation of {@code ProcessInstanceService}.
 * Toggle via {@code bpmnflow.duality.enabled} in application.yaml.
 *
 * <h3>Read path</h3>
 * Single-record reads use {@link WfProcessInstanceDualityRepository} via
 * {@code wf_process_instance_dv} — full document with activities and variables.
 * Listing uses {@link WfInstanceListingRepository} via {@code wf_instance_listing_dv}
 * with a batch query for active activities.
 *
 * <h3>Write path — native Duality View</h3>
 * All write operations (startProcess, completeActivity, setVariables) use the
 * Duality View directly via {@code INSERT} and {@code UPDATE} on
 * {@code wf_process_instance_dv}. Oracle decomposes the document into DML across
 * the underlying relational tables atomically.
 *
 * Structural lookups (process version, rules, activity definitions) still use JPA
 * because those tables are not part of the runtime Duality View.
 *
 * <h3>Transaction boundary</h3>
 * Because the Duality View write uses JdbcTemplate (separate JDBC connection from
 * the JPA EntityManager), write methods are not annotated with {@code @Transactional}.
 * Each JdbcTemplate operation commits immediately. Reads after writes see committed data.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProcessInstanceDualityService {

    private final WfProcessInstanceDualityRepository dualityRepo;
    private final WfInstanceListingRepository        listingRepo;
    private final BpmnProcessVersionRepository       versionRepo;
    private final BpmnRuleRepository                 ruleRepo;
    private final WfInstanceActivityRepository       instActivityRepo;
    private final BpmnActivityRepository             activityRepo;

    // -----------------------------------------------------------------------
    // ETag helpers — used by the controller layer
    // -----------------------------------------------------------------------

    /**
     * Returns the current ETag string for an instance without loading the full document graph.
     * Used by the controller to emit/refresh the {@code ETag} response header.
     *
     * Returns {@code null} if the instance doesn't exist or metadata is absent.
     */
    public String getETagForInstance(Long instanceId) {
        return dualityRepo.findById(instanceId)
                .map(WfProcessInstanceDoc::etagValue)
                .orElse(null);
    }

    /**
     * Returns the raw Duality View document for an instance.
     * Exposed so the controller can access the ETag before converting to the response DTO.
     */
    public WfProcessInstanceDoc getInstanceDoc(Long instanceId) {
        return dualityRepo.findById(instanceId)
                .orElseThrow(() -> new ResourceNotFoundException("Instance not found: " + instanceId));
    }

    /**
     * Converts a raw Duality View document to the REST response DTO.
     * Exposed as public to allow the controller to call it after fetching the doc
     * (to access the ETag before the conversion discards the metadata field).
     */
    public ProcessInstanceResponse buildResponseFromDoc(WfProcessInstanceDoc doc) {
        return buildResponseFromDocInternal(doc);
    }

    // -----------------------------------------------------------------------
    // Read operations
    // -----------------------------------------------------------------------

    public ProcessInstanceResponse getInstance(Long instanceId) {
        WfProcessInstanceDoc doc = dualityRepo.findById(instanceId)
                .orElseThrow(() -> new ResourceNotFoundException("Instance not found: " + instanceId));
        return buildResponseFromDocInternal(doc);
    }

    public List<WorkflowSummaryResponse> listInstances(
            String status, String processKey, int page, int size) {

        List<WfInstanceListingDoc> docs;

        if (processKey != null && status != null) {
            docs = listingRepo.findByProcessKeyAndStatus(
                    processKey, status.toUpperCase(), page, size);
        } else if (processKey != null) {
            docs = listingRepo.findByProcessKey(processKey, page, size);
        } else if (status != null) {
            docs = listingRepo.findByStatus(status.toUpperCase(), page, size);
        } else {
            docs = listingRepo.findAll(page, size);
        }

        if (docs.isEmpty()) return List.of();

        List<Long> instanceIds = docs.stream().map(WfInstanceListingDoc::id).toList();
        Map<Long, WfInstanceActivityEntity> activeByInstance =
                instActivityRepo.findActiveByInstanceIdIn(instanceIds).stream()
                        .collect(Collectors.toMap(
                                a -> a.getInstance().getInstanceId(),
                                a -> a));

        return docs.stream()
                .map(doc -> buildSummaryFromListingDoc(doc, activeByInstance.get(doc.id())))
                .collect(Collectors.toList());
    }

    // -----------------------------------------------------------------------
    // Write operations — native Duality View
    // -----------------------------------------------------------------------

    /**
     * Starts a new process instance by inserting a document into the Duality View.
     *
     * Oracle decomposes the INSERT into DML across wf_process_instance,
     * wf_instance_activity, and wf_instance_variable atomically.
     *
     * Structural lookups (version, rules, activity definitions) use JPA because
     * those tables are not part of the runtime Duality View.
     */
    public ProcessInstanceResponse startProcess(Long versionId, StartProcessRequest request) {
        // Structural lookups via JPA — read-only, no writes
        BpmnProcessVersionEntity version = versionRepo.findById(versionId)
                .orElseThrow(() -> new ResourceNotFoundException("Version not found: " + versionId));

        List<ProcessRuleEntity> entryRules = ruleRepo.findByVersion_VersionIdAndRuleType(
                versionId, RuleType.START_TO_TASK);
        if (entryRules.isEmpty()) {
            throw new IllegalStateException("No START_TO_TASK rule found for version " + versionId);
        }

        ProcessRuleEntity entryRule = entryRules.getFirst();
        ProcessActivityEntity firstAct = activityRepo
                .findByIdWithElement(entryRule.getTargetActivity().getActivityId())
                .orElseThrow(() -> new IllegalStateException(
                        "Activity not found: " + entryRule.getTargetActivity().getActivityId()));

        // Build initial variables from request
        List<WfVariableDoc> variableDocs = new ArrayList<>();
        if (request != null && request.getVariables() != null) {
            for (VariableRequest v : request.getVariables()) {
                VariableType type = v.getType() != null ? v.getType() : VariableType.STRING;
                type.validate(v.getValue());
                variableDocs.add(new WfVariableDoc(
                        null, v.getKey(), type.name(), v.getValue(), LocalDateTime.now()));
            }
        }

        // Build initial activity
        WfActivityDoc firstStep = new WfActivityDoc(
                null,
                firstAct.getActivityId(),
                1,
                ActivityStepStatus.ACTIVE.name(),
                null,
                LocalDateTime.now(),
                null);

        // Build the complete document — Oracle assigns the _id via sequence
        // _metadata is null for new documents (INSERT); Oracle generates it
        WfProcessInstanceDoc doc = new WfProcessInstanceDoc(
                null,
                null,
                request != null ? request.getExternalId() : null,
                InstanceStatus.ACTIVE.name(),
                entryRule.getProcessStatus(),
                versionId,
                LocalDateTime.now(),
                LocalDateTime.now(),
                null,
                List.of(firstStep),
                variableDocs);

        // Single INSERT — Oracle decomposes into DML across 3 tables atomically
        WfProcessInstanceDoc saved = dualityRepo.insert(doc);

        log.info("[dv-write] Started instance {} for version {} at '{}'",
                saved.id(), versionId, firstAct.getAbbreviation());

        return buildResponseFromDocInternal(saved);
    }

    /**
     * Completes the current activity — without ETag enforcement (backward compat).
     * Used by JPA fallback path and internal callers.
     */
    public ProcessInstanceResponse completeActivity(Long instanceId, CompleteActivityRequest request) {
        return completeActivity(instanceId, null, request);
    }

    /**
     * Completes the current activity with optional ETag enforcement.
     *
     * <p>If {@code clientEtag} is non-null, it is embedded in the document's
     * {@code _metadata.etag} field before the UPDATE. Oracle will reject the
     * write with ORA-40896 if the stored ETag differs — another session has
     * advanced the instance concurrently. The repository translates ORA-40896
     * into {@link org.bpmnflow.runtime.ETagConflictException}, which the
     * {@link org.bpmnflow.runtime.GlobalExceptionHandler} maps to HTTP 412.</p>
     *
     * <p>If {@code clientEtag} is null, the write proceeds without ETag check
     * (last-writer-wins semantics, same as the JPA path).</p>
     */
    public ProcessInstanceResponse completeActivity(Long instanceId,
                                                    String clientEtag,
                                                    CompleteActivityRequest request) {
        WfProcessInstanceDoc doc = dualityRepo.findById(instanceId)
                .orElseThrow(() -> new ResourceNotFoundException("Instance not found: " + instanceId));

        if ("COMPLETED".equals(doc.status())) {
            throw new IllegalStateException("Process instance is already completed");
        }

        WfActivityDoc activeStep = doc.activeActivity();
        if (activeStep == null) {
            throw new IllegalStateException("No active activity for instance " + instanceId);
        }

        // Load activity definition for validation and rule resolution
        ProcessActivityEntity currentActivity = activityRepo
                .findByIdWithElement(activeStep.activityId())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Activity not found: " + activeStep.activityId()));

        Long versionId = doc.versionId();
        String conclusionCode = request != null ? request.getConclusionCode() : null;
        boolean hasConclusions = !currentActivity.getConclusions().isEmpty();

        if (hasConclusions) {
            if (conclusionCode == null || conclusionCode.isBlank()) {
                List<String> available = currentActivity.getConclusions().stream()
                        .map(ProcessConclusionEntity::getCode).toList();
                throw new IllegalArgumentException(
                        "Activity '" + currentActivity.getAbbreviation() +
                                "' requires a conclusion. Available: " + available);
            }
            boolean valid = currentActivity.getConclusions().stream()
                    .anyMatch(c -> conclusionCode.equals(c.getCode()));
            if (!valid) {
                List<String> available = currentActivity.getConclusions().stream()
                        .map(ProcessConclusionEntity::getCode).toList();
                throw new IllegalArgumentException(
                        "Invalid conclusion '" + conclusionCode + "' for activity '" +
                                currentActivity.getAbbreviation() + "'. Available: " + available);
            }
        }

        // Resolve next rule
        List<ProcessRuleEntity> matchingRules = (conclusionCode != null && !conclusionCode.isBlank())
                ? ruleRepo.findMatchingRulesWithConclusion(
                versionId, currentActivity.getActivityId(), conclusionCode)
                : ruleRepo.findMatchingRulesWithoutConclusion(
                versionId, currentActivity.getActivityId());

        if (matchingRules.isEmpty()) {
            throw new IllegalStateException("No rule found for activity '" +
                    currentActivity.getAbbreviation() + "' with conclusion '" + conclusionCode + "'");
        }

        ProcessRuleEntity matchedRule = matchingRules.getFirst();
        boolean isEnd = matchedRule.getTargetActivity() == null
                || matchedRule.getRuleType().name().endsWith("_TO_END");

        // Apply state transition on the document in Java
        LocalDateTime now = LocalDateTime.now();

        // Complete current activity
        WfActivityDoc completedStep = new WfActivityDoc(
                activeStep.id(),
                activeStep.activityId(),
                activeStep.stepNumber(),
                ActivityStepStatus.COMPLETED.name(),
                conclusionCode,
                activeStep.startedAt(),
                now);

        // Build updated activities list
        List<WfActivityDoc> updatedActivities = doc.activities().stream()
                .map(a -> "ACTIVE".equals(a.status()) ? completedStep : a)
                .collect(Collectors.toCollection(ArrayList::new));

        // Merge new variables from request
        List<WfVariableDoc> updatedVariables = mergeVariables(doc.variables(), request);

        // Determine new instance status and processStatus
        String newInstanceStatus = isEnd ? InstanceStatus.COMPLETED.name() : InstanceStatus.ACTIVE.name();
        String newProcessStatus = matchedRule.getProcessStatus() != null
                ? matchedRule.getProcessStatus()
                : doc.processStatus();
        LocalDateTime completedAt = isEnd ? now : doc.completedAt();

        // Add next activity step if not end
        if (!isEnd) {
            ProcessActivityEntity nextAct = activityRepo
                    .findByIdWithElement(matchedRule.getTargetActivity().getActivityId())
                    .orElseThrow(() -> new IllegalStateException(
                            "Next activity not found: " + matchedRule.getTargetActivity().getActivityId()));

            WfActivityDoc nextStep = new WfActivityDoc(
                    null,
                    nextAct.getActivityId(),
                    activeStep.stepNumber() + 1,
                    ActivityStepStatus.ACTIVE.name(),
                    null,
                    now,
                    null);
            updatedActivities.add(nextStep);

            log.info("[dv-write] Instance {} advanced '{}' -> '{}' (conclusion: '{}')",
                    instanceId, currentActivity.getAbbreviation(),
                    nextAct.getAbbreviation(), conclusionCode);
        } else {
            log.info("[dv-write] Instance {} completed at step {}", instanceId, activeStep.stepNumber());
        }

        // Build updated document — use clientEtag when provided (OCC enforcement),
        // otherwise preserve the fetched _metadata (last-writer-wins, no OCC check).
        Object metadataToWrite = buildMetadataForWrite(doc.metadata(), clientEtag);

        WfProcessInstanceDoc updated = new WfProcessInstanceDoc(
                doc.id(),
                metadataToWrite,
                doc.externalId(),
                newInstanceStatus,
                newProcessStatus,
                doc.versionId(),
                doc.createdAt(),
                now,
                completedAt,
                updatedActivities,
                updatedVariables);

        // Single UPDATE — Oracle reconciles changes across wf_process_instance
        // and wf_instance_activity atomically. If clientEtag was provided and
        // is stale, Oracle raises ORA-40896 → ETagConflictException → HTTP 412.
        WfProcessInstanceDoc saved = dualityRepo.update(updated, instanceId);

        return buildResponseFromDocInternal(saved);
    }

    /**
     * Sets or updates instance variables — without ETag enforcement (backward compat).
     */
    public List<VariableResponse> setVariables(Long instanceId, List<VariableRequest> variables) {
        return setVariables(instanceId, null, variables);
    }

    /**
     * Sets or updates instance variables with optional ETag enforcement.
     *
     * <p>If {@code clientEtag} is non-null, Oracle verifies the ETag before applying
     * the UPDATE. A stale ETag causes ORA-40896 → HTTP 412.</p>
     */
    public List<VariableResponse> setVariables(Long instanceId,
                                               String clientEtag,
                                               List<VariableRequest> variables) {
        WfProcessInstanceDoc doc = dualityRepo.findById(instanceId)
                .orElseThrow(() -> new ResourceNotFoundException("Instance not found: " + instanceId));

        // Validate all variables before any write
        for (VariableRequest req : variables) {
            if (req.getKey() == null || req.getKey().isBlank()) {
                throw new IllegalArgumentException("Variable key must not be blank");
            }
            VariableType type = req.getType() != null ? req.getType() : VariableType.STRING;
            type.validate(req.getValue());
        }

        List<WfVariableDoc> updatedVariables = mergeVariables(doc.variables(), variables);

        Object metadataToWrite = buildMetadataForWrite(doc.metadata(), clientEtag);

        WfProcessInstanceDoc updated = new WfProcessInstanceDoc(
                doc.id(),
                metadataToWrite,
                doc.externalId(),
                doc.status(),
                doc.processStatus(),
                doc.versionId(),
                doc.createdAt(),
                LocalDateTime.now(),
                doc.completedAt(),
                doc.activities(),
                updatedVariables);

        // Single UPDATE — Oracle reconciles changes in wf_instance_variable atomically.
        // If clientEtag is stale, Oracle raises ORA-40896 → ETagConflictException → HTTP 412.
        WfProcessInstanceDoc saved = dualityRepo.update(updated, instanceId);

        if (saved.variables() == null) return List.of();
        return saved.variables().stream()
                .map(v -> {
                    VariableType type = VariableType.valueOf(v.variableType());
                    return VariableResponse.builder()
                            .key(v.variableKey())
                            .type(type)
                            .value(v.variableValue())
                            .convertedValue(type.convert(v.variableValue()))
                            .build();
                })
                .collect(Collectors.toList());
    }

    public List<VariableResponse> getVariables(Long instanceId) {
        WfProcessInstanceDoc doc = dualityRepo.findById(instanceId)
                .orElseThrow(() -> new ResourceNotFoundException("Instance not found: " + instanceId));

        if (doc.variables() == null) return List.of();

        return doc.variables().stream()
                .map(v -> {
                    VariableType type = VariableType.valueOf(v.variableType());
                    return VariableResponse.builder()
                            .key(v.variableKey())
                            .type(type)
                            .value(v.variableValue())
                            .convertedValue(type.convert(v.variableValue()))
                            .build();
                })
                .collect(Collectors.toList());
    }

    // -----------------------------------------------------------------------
    // Private helpers
    // -----------------------------------------------------------------------

    /**
     * Merges new variables from a request into the existing variable list.
     * Existing keys are overwritten; new keys are added.
     */
    private List<WfVariableDoc> mergeVariables(
            List<WfVariableDoc> existing, CompleteActivityRequest request) {
        if (request == null || request.getVariables() == null || request.getVariables().isEmpty()) {
            return existing != null ? existing : List.of();
        }
        return mergeVariables(existing, request.getVariables());
    }

    private List<WfVariableDoc> mergeVariables(
            List<WfVariableDoc> existing, List<VariableRequest> incoming) {
        if (incoming == null || incoming.isEmpty()) {
            return existing != null ? existing : List.of();
        }

        // Build map from existing variables by key for fast lookup
        Map<String, WfVariableDoc> byKey = existing == null
                ? new java.util.LinkedHashMap<>()
                : existing.stream().collect(Collectors.toMap(
                WfVariableDoc::variableKey,
                v -> v,
                (a, b) -> b,
                java.util.LinkedHashMap::new));

        for (VariableRequest req : incoming) {
            VariableType type = req.getType() != null ? req.getType() : VariableType.STRING;
            WfVariableDoc existing_v = byKey.get(req.getKey());
            // Preserve the id if updating an existing variable
            byKey.put(req.getKey(), new WfVariableDoc(
                    existing_v != null ? existing_v.id() : null,
                    req.getKey(),
                    type.name(),
                    req.getValue(),
                    LocalDateTime.now()));
        }

        return new ArrayList<>(byKey.values());
    }

    /**
     * Constructs the {@code _metadata} object to embed in the UPDATE payload.
     *
     * <p>When {@code clientEtag} is non-null, an override metadata map is built
     * containing only the {@code etag} field — Oracle uses this to perform the
     * ETag check atomically during the UPDATE. If the stored ETag differs from
     * {@code clientEtag}, Oracle raises ORA-40896.</p>
     *
     * <p>When {@code clientEtag} is null, the metadata from the last read is
     * preserved unchanged. Oracle still checks the ETag (the full _metadata
     * object is always sent), but the check will pass because the value matches
     * what Oracle just returned to us in the SELECT — unless a concurrent session
     * modified the document in the window between our SELECT and this UPDATE.</p>
     */
    @SuppressWarnings("unchecked")
    private Object buildMetadataForWrite(Object fetchedMetadata, String clientEtag) {
        if (clientEtag == null) {
            // No client-supplied ETag: round-trip the fetched metadata unchanged.
            // Oracle performs ETag check based on the etag value in this metadata object.
            return fetchedMetadata;
        }
        // Client supplied an ETag — construct a minimal metadata override
        // with only the etag field. Oracle will compare it against the stored value.
        java.util.Map<String, Object> override = new java.util.LinkedHashMap<>();
        override.put("etag", clientEtag);
        // Preserve asof if present — it's harmless and some Oracle versions expect it
        if (fetchedMetadata instanceof java.util.Map<?,?> m && m.containsKey("asof")) {
            override.put("asof", m.get("asof"));
        }
        return override;
    }

    private ProcessInstanceResponse buildResponseFromDocInternal(WfProcessInstanceDoc doc) {
        BpmnProcessVersionEntity version = versionRepo.findById(doc.versionId())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Version not found: " + doc.versionId()));

        WfActivityDoc activeActivityDoc = doc.activeActivity();
        ActivityStepResponse currentAct = null;

        if (activeActivityDoc != null) {
            ProcessActivityEntity act = activityRepo
                    .findByIdWithElement(activeActivityDoc.activityId())
                    .orElseThrow(() -> new ResourceNotFoundException(
                            "Activity not found: " + activeActivityDoc.activityId()));
            currentAct = ActivityStepResponse.builder()
                    .stepNumber(activeActivityDoc.stepNumber())
                    .activityId(act.getActivityId())
                    .elementBpmnId(act.getElement() != null ? act.getElement().getBpmnId() : null)
                    .abbreviation(act.getAbbreviation())
                    .activityName(act.getName())
                    .stageCode(act.getStageCode())
                    .laneName(act.getLaneName())
                    .status(activeActivityDoc.status())
                    .startedAt(activeActivityDoc.startedAt())
                    .availableConclusions(act.getConclusions().stream()
                            .map(c -> ActivityStepResponse.ConclusionOption.builder()
                                    .code(c.getCode()).name(c.getName()).build())
                            .collect(Collectors.toList()))
                    .build();
        }

        List<ProcessInstanceResponse.ActivityHistoryEntry> history = new ArrayList<>();
        for (WfActivityDoc a : doc.sortedActivities()) {
            ProcessActivityEntity act = activityRepo
                    .findByIdWithElement(a.activityId()).orElse(null);
            history.add(ProcessInstanceResponse.ActivityHistoryEntry.builder()
                    .stepNumber(a.stepNumber())
                    .elementBpmnId(act != null && act.getElement() != null
                            ? act.getElement().getBpmnId() : null)
                    .abbreviation(act != null ? act.getAbbreviation() : null)
                    .activityName(act != null ? act.getName() : null)
                    .status(a.status())
                    .conclusionCode(a.conclusionCode())
                    .startedAt(a.startedAt())
                    .completedAt(a.completedAt())
                    .build());
        }

        List<VariableResponse> variables = new ArrayList<>();
        if (doc.variables() != null) {
            for (WfVariableDoc v : doc.variables()) {
                VariableType type = VariableType.valueOf(v.variableType());
                variables.add(VariableResponse.builder()
                        .key(v.variableKey())
                        .type(type)
                        .value(v.variableValue())
                        .convertedValue(type.convert(v.variableValue()))
                        .build());
            }
        }

        return ProcessInstanceResponse.builder()
                .instanceId(doc.id())
                .externalId(doc.externalId())
                .instanceStatus(doc.status())
                .processStatus(doc.processStatus())
                .versionId(version.getVersionId())
                .versionNumber(version.getVersionNumber())
                .versionTag(version.getVersionTag())
                .processType(version.getProcessType())
                .processSubtype(version.getProcessSubtype())
                .currentActivity(currentAct)
                .activityHistory(history)
                .variables(variables)
                .createdAt(doc.createdAt())
                .updatedAt(doc.updatedAt())
                .completedAt(doc.completedAt())
                .build();
    }

    private WorkflowSummaryResponse buildSummaryFromListingDoc(
            WfInstanceListingDoc doc, WfInstanceActivityEntity activeStep) {
        String abbrev  = activeStep != null ? activeStep.getActivity().getAbbreviation() : null;
        String actName = activeStep != null ? activeStep.getActivity().getName() : null;
        return WorkflowSummaryResponse.builder()
                .instanceId(doc.id())
                .externalId(doc.externalId())
                .instanceStatus(doc.status())
                .processStatus(doc.processStatus())
                .versionId(doc.versionId())
                .versionNumber(doc.versionNumber())
                .processKey(doc.processKey())
                .processName(doc.processName())
                .currentActivityAbbreviation(abbrev)
                .currentActivityName(actName)
                .createdAt(doc.createdAt())
                .updatedAt(doc.updatedAt())
                .completedAt(doc.completedAt())
                .build();
    }
}