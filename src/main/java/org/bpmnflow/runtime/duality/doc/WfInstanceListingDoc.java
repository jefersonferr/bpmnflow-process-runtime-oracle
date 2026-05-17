package org.bpmnflow.runtime.duality.doc;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.LocalDateTime;

/**
 * Document record for the {@code wf_instance_listing_dv} Duality View.
 *
 * Contains the structural fields needed for WorkflowSummaryResponse directly
 * in the document: processKey, processName, and versionNumber come from the
 * joined bpmn_process_version and bpmn_process tables in the view definition.
 *
 * The current activity abbreviation is not embedded here — Oracle does not
 * allow explicit JOINs inside Duality View subcollections (ORA-40935). The
 * active activity is resolved in the service layer via a single batch query
 * against wf_instance_activity after the documents are fetched.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record WfInstanceListingDoc(
        @JsonProperty("_id")           Long id,
        @JsonProperty("externalId")    String externalId,
        @JsonProperty("status")        String status,
        @JsonProperty("processStatus") String processStatus,
        @JsonProperty("versionId")     Long versionId,
        @JsonProperty("versionNumber") Integer versionNumber,
        @JsonProperty("processKey")    String processKey,
        @JsonProperty("processName")   String processName,
        @JsonProperty("createdAt")     LocalDateTime createdAt,
        @JsonProperty("updatedAt")     LocalDateTime updatedAt,
        @JsonProperty("completedAt")   LocalDateTime completedAt
) {}