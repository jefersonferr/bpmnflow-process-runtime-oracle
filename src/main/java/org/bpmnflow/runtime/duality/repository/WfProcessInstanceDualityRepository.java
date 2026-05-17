package org.bpmnflow.runtime.duality.repository;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.oracle.spring.json.jsonb.JSONB;
import lombok.extern.slf4j.Slf4j;
import oracle.jdbc.OracleTypes;
import org.bpmnflow.runtime.duality.doc.WfProcessInstanceDoc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.util.List;
import java.util.Optional;

/**
 * Repository for {@code wf_process_instance_dv} using JdbcTemplate + JSONB.
 *
 * <h3>Read strategy</h3>
 * Uses {@code json_serialize(data returning clob)} to obtain the full document
 * as a JSON string including the {@code _id} field.
 *
 * <h3>Write strategy — INSERT with RETURNING</h3>
 * INSERT uses {@code RETURNING json_serialize(data returning clob) INTO ?} to
 * capture the complete document (including the Oracle-generated _id) in a single
 * round-trip. This avoids the need for a separate SELECT after INSERT.
 *
 * <h3>Write strategy — UPDATE</h3>
 * UPDATE passes the document as OSON bytes via {@code JSONB.toOSON()}.
 * Oracle reconciles the document against the underlying relational tables atomically.
 */
@Slf4j
@Repository
public class WfProcessInstanceDualityRepository {

    private static final String VIEW = "wf_process_instance_dv";

    private static final String FIND_BY_ID = """
            SELECT json_serialize(data returning clob) FROM %s v
            WHERE v.data."_id" = ?
            """.formatted(VIEW);

    private static final String FIND_BY_STATUS = """
            SELECT json_serialize(data returning clob) FROM %s v
            WHERE v.data.status = ?
            ORDER BY v.data.createdAt DESC
            OFFSET ? ROWS FETCH NEXT ? ROWS ONLY
            """.formatted(VIEW);

    private static final String FIND_BY_VERSION_ID = """
            SELECT json_serialize(data returning clob) FROM %s v
            WHERE v.data.versionId = ?
            ORDER BY v.data.createdAt DESC
            OFFSET ? ROWS FETCH NEXT ? ROWS ONLY
            """.formatted(VIEW);

    private static final String FIND_BY_VERSION_AND_STATUS = """
            SELECT json_serialize(data returning clob) FROM %s v
            WHERE v.data.versionId = ?
              AND v.data.status = ?
            ORDER BY v.data.createdAt DESC
            OFFSET ? ROWS FETCH NEXT ? ROWS ONLY
            """.formatted(VIEW);

    private static final String FIND_ALL = """
            SELECT json_serialize(data returning clob) FROM %s v
            ORDER BY v.data.createdAt DESC
            OFFSET ? ROWS FETCH NEXT ? ROWS ONLY
            """.formatted(VIEW);

    // INSERT RETURNING — uses OraclePreparedStatement.registerReturnParameter()
    // to capture the Oracle-generated _id in a single round-trip.
    // Pattern from: andersswanson.dev/2026/02/24/hands-on-crud-with-jdbc-and-json-relational-duality-views
    private static final String INSERT_RETURNING = """
            INSERT INTO %s (data) VALUES (?)
            RETURNING json_value(data, '$._id' returning number) INTO ?
            """.formatted(VIEW);

    private static final String UPDATE_BY_ID = """
            UPDATE %s v SET data = ?
            WHERE v.data."_id" = ?
            """.formatted(VIEW);

    private final JdbcTemplate jdbc;
    private final JSONB jsonb;
    private final ObjectMapper mapper;
    private final RowMapper<WfProcessInstanceDoc> rowMapper;

    public WfProcessInstanceDualityRepository(JdbcTemplate jdbc, JSONB jsonb) {
        this.jdbc   = jdbc;
        this.jsonb  = jsonb;
        this.mapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        this.rowMapper = (rs, rowNum) -> {
            String json = rs.getString(1);
            try {
                return mapper.readValue(json, WfProcessInstanceDoc.class);
            } catch (Exception e) {
                throw new RuntimeException("Failed to deserialize WfProcessInstanceDoc", e);
            }
        };
    }

    // ── Reads ───────────────────────────────────────────────────────────────

    public Optional<WfProcessInstanceDoc> findById(Long instanceId) {
        List<WfProcessInstanceDoc> result = jdbc.query(FIND_BY_ID, rowMapper, instanceId);
        log.debug("[jsonb] findById({}) -> {}", instanceId, result.isEmpty() ? "null" : "found");
        return result.stream().findFirst();
    }

    public List<WfProcessInstanceDoc> findByStatus(String status, int page, int size) {
        return jdbc.query(FIND_BY_STATUS, rowMapper, status, (long) page * size, size);
    }

    public List<WfProcessInstanceDoc> findByVersionId(Long versionId, int page, int size) {
        return jdbc.query(FIND_BY_VERSION_ID, rowMapper, versionId, (long) page * size, size);
    }

    public List<WfProcessInstanceDoc> findByVersionIdAndStatus(
            Long versionId, String status, int page, int size) {
        return jdbc.query(FIND_BY_VERSION_AND_STATUS, rowMapper,
                versionId, status, (long) page * size, size);
    }

    public List<WfProcessInstanceDoc> findAll(int page, int size) {
        return jdbc.query(FIND_ALL, rowMapper, (long) page * size, size);
    }

    // Non-paginated (backward compatibility)
    public List<WfProcessInstanceDoc> findByStatus(String status) {
        return findByStatus(status, 0, Integer.MAX_VALUE);
    }

    public List<WfProcessInstanceDoc> findByVersionId(Long versionId) {
        return findByVersionId(versionId, 0, Integer.MAX_VALUE);
    }

    public List<WfProcessInstanceDoc> findByVersionIdAndStatus(Long versionId, String status) {
        return findByVersionIdAndStatus(versionId, status, 0, Integer.MAX_VALUE);
    }

    // ── Writes ──────────────────────────────────────────────────────────────

    /**
     * Inserts a new document into the Duality View and returns the saved document
     * including the Oracle-generated _id.
     *
     * Uses INSERT ... RETURNING json_value(data, '$._id') with
     * OraclePreparedStatement.registerReturnParameter() to capture the generated
     * _id in a single round-trip — no separate SELECT needed.
     *
     * Pattern from Oracle's official sample:
     * andersswanson.dev/2026/02/24/hands-on-crud-with-jdbc-and-json-relational-duality-views
     */
    public WfProcessInstanceDoc insert(WfProcessInstanceDoc doc) {
        String json;
        try {
            json = mapper.writeValueAsString(doc);
        } catch (Exception e) {
            throw new RuntimeException("Failed to serialize WfProcessInstanceDoc", e);
        }
        final long[] generatedId = new long[]{-1};

        jdbc.execute((java.sql.Connection con) -> {
            oracle.jdbc.OraclePreparedStatement ps =
                    (oracle.jdbc.OraclePreparedStatement) con.prepareStatement(INSERT_RETURNING);
            ps.setString(1, json);
            ps.registerReturnParameter(2, OracleTypes.NUMBER);
            ps.executeUpdate();
            try (java.sql.ResultSet rs = ps.getReturnResultSet()) {
                if (rs.next()) {
                    generatedId[0] = rs.getLong(1);
                }
            }
            ps.close();
            return null;
        });

        if (generatedId[0] == -1) {
            throw new IllegalStateException("INSERT into Duality View returned no _id");
        }

        log.debug("[jsonb] insert -> id={}", generatedId[0]);
        return findById(generatedId[0]).orElseThrow(
                () -> new IllegalStateException("Document not found after INSERT: id=" + generatedId[0]));
    }

    /**
     * Updates an existing document in the Duality View.
     * Oracle reconciles the changes across underlying relational tables atomically.
     *
     * Uses Jackson ObjectMapper (not JSONB.toOSON) for serialization to ensure
     * @JsonProperty annotations are respected — in particular, _id fields in
     * subcollection items (WfActivityDoc, WfVariableDoc) must be serialized as
     * "_id", not "id" (the Java field name that JSONB would use).
     *
     * @deprecated Use {@link #update(WfProcessInstanceDoc, Long)} to enable
     *             ETag conflict detection (HTTP 412) on ORA-40896.
     */
    @Deprecated(since = "ETag OCC")
    public WfProcessInstanceDoc update(WfProcessInstanceDoc doc) {
        return update(doc, doc.id());
    }

    /**
     * Updates an existing document in the Duality View, translating ORA-40896 into
     * {@link org.bpmnflow.runtime.ETagConflictException} (→ HTTP 412 Precondition Failed).
     *
     * <p>Oracle raises ORA-40896 when the ETag embedded in the document's
     * {@code _metadata.etag} field does not match the currently stored ETag.
     * This happens when another session has modified the document between the
     * caller's last read and this write — the classic lost-update scenario.</p>
     *
     * <p>ORA-42699 is the equivalent error code in Oracle 23c (non-ai); both are
     * handled for compatibility.</p>
     *
     * @param doc        the document to write (must include _id and _metadata)
     * @param instanceId used for the exception message if an ETag conflict occurs
     */
    public WfProcessInstanceDoc update(WfProcessInstanceDoc doc, Long instanceId) {
        String json;
        try {
            json = mapper.writeValueAsString(doc);
        } catch (Exception e) {
            throw new RuntimeException("Failed to serialize WfProcessInstanceDoc", e);
        }
        try {
            int rows = jdbc.update(con -> {
                PreparedStatement ps = con.prepareStatement(UPDATE_BY_ID);
                ps.setString(1, json);
                ps.setLong(2, doc.id());
                return ps;
            });
            if (rows == 0) {
                throw new IllegalStateException("Document not found for update: id=" + doc.id());
            }
        } catch (org.springframework.dao.DataAccessException dae) {
            // Unwrap Oracle-specific ETag mismatch errors
            Throwable cause = dae.getMostSpecificCause();
            if (cause instanceof java.sql.SQLException sqle) {
                int code = sqle.getErrorCode();
                // ORA-40896 (26ai) and ORA-42699 (23c) signal ETag mismatch
                if (code == 40896 || code == 42699) {
                    throw new org.bpmnflow.runtime.ETagConflictException(
                            instanceId != null ? instanceId : doc.id(), dae);
                }
            }
            throw dae; // rethrow anything else
        }
        log.debug("[jsonb] update id={} -> ok", doc.id());
        return findById(doc.id()).orElseThrow(
                () -> new IllegalStateException("Document not found after update: " + doc.id()));
    }
}