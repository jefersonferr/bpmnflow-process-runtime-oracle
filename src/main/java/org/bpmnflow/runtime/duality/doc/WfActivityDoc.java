package org.bpmnflow.runtime.duality.doc;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.LocalDateTime;

/**
 * Nested activity step inside {@link WfProcessInstanceDoc}.
 *
 * The DDL of wf_process_instance_dv maps inst_activity_id as 'id' (not '_id')
 * in the subcollection. Serialization uses Jackson ObjectMapper (not JSONB.toOSON)
 * to ensure @JsonProperty annotations are respected on write.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record WfActivityDoc(
        @JsonProperty("id")             Long id,
        @JsonProperty("activityId")     Long activityId,
        @JsonProperty("stepNumber")     Integer stepNumber,
        @JsonProperty("status")         String status,
        @JsonProperty("conclusionCode") String conclusionCode,
        @JsonProperty("startedAt")      LocalDateTime startedAt,
        @JsonProperty("completedAt")    LocalDateTime completedAt
) {}