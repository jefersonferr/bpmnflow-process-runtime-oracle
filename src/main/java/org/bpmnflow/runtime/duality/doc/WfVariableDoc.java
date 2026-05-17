package org.bpmnflow.runtime.duality.doc;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.LocalDateTime;

/**
 * Nested instance variable inside {@link WfProcessInstanceDoc}.
 *
 * The DDL of wf_process_instance_dv maps variable_id as 'id' (not '_id')
 * in the subcollection. Serialization uses Jackson ObjectMapper (not JSONB.toOSON)
 * to ensure @JsonProperty annotations are respected on write.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record WfVariableDoc(
        @JsonProperty("id")            Long id,
        @JsonProperty("variableKey")   String variableKey,
        @JsonProperty("variableType")  String variableType,
        @JsonProperty("variableValue") String variableValue,
        @JsonProperty("updatedAt")     LocalDateTime updatedAt
) {}