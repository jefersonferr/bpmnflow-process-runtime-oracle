package org.bpmnflow.runtime.duality.doc;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * Root document for the {@code wf_process_instance_dv} JSON Relational Duality View.
 *
 * <h3>ETag and optimistic concurrency control</h3>
 * Oracle injects a {@code _metadata} field into every Duality View document containing
 * an ETag hash. When updating a document, the {@code _metadata} field read from the
 * previous GET must be included in the UPDATE payload. If the ETag does not match the
 * current state of the document in the database, Oracle rejects the update with
 * ORA-40896 (concurrent modification detected).
 *
 * The {@code _metadata} field is preserved as a raw {@code Object} to round-trip it
 * unchanged from read to write. It must never be modified by the application.
 *
 * <h3>ETag extraction for HTTP headers</h3>
 * Use {@link #etagValue()} to obtain the raw ETag string for use in HTTP {@code ETag}
 * and {@code If-Match} response/request headers. The value is a 32-character hex hash.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record WfProcessInstanceDoc(
        @JsonProperty("_id")           Long id,
        @JsonProperty("_metadata")     Object metadata,
        @JsonProperty("externalId")    String externalId,
        @JsonProperty("status")        String status,
        @JsonProperty("processStatus") String processStatus,
        @JsonProperty("versionId")     Long versionId,
        @JsonProperty("createdAt")     LocalDateTime createdAt,
        @JsonProperty("updatedAt")     LocalDateTime updatedAt,
        @JsonProperty("completedAt")   LocalDateTime completedAt,
        @JsonProperty("activities")    List<WfActivityDoc> activities,
        @JsonProperty("variables")     List<WfVariableDoc> variables
) {
    /**
     * Extracts the raw ETag hex string from the Oracle-injected {@code _metadata} field.
     *
     * Oracle serializes {@code _metadata} as: {@code {"etag":"<32-hex>","asof":"<scn>"}}
     * Jackson deserializes this into a {@code LinkedHashMap<String, Object>}.
     *
     * Returns {@code null} if metadata is absent (e.g. immediately after INSERT before
     * Oracle has returned the generated document), or if the etag key is missing.
     */
    @SuppressWarnings("unchecked")
    public String etagValue() {
        if (metadata == null) return null;
        if (metadata instanceof Map<?,?> map) {
            Object etag = map.get("etag");
            return etag != null ? etag.toString() : null;
        }
        return null;
    }
    public List<WfActivityDoc> sortedActivities() {
        if (activities == null) return List.of();
        return activities.stream()
                .sorted(Comparator.comparingInt(a -> a.stepNumber() != null ? a.stepNumber() : 0))
                .toList();
    }

    public WfActivityDoc activeActivity() {
        if (activities == null) return null;
        return activities.stream()
                .filter(a -> "ACTIVE".equals(a.status()))
                .findFirst()
                .orElse(null);
    }

    public int stepCount() {
        return activities == null ? 0 : activities.size();
    }
}