package org.bpmnflow.runtime.duality.repository;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import lombok.extern.slf4j.Slf4j;
import org.bpmnflow.runtime.duality.doc.WfInstanceListingDoc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * Repository for {@code wf_instance_listing_dv} — a read-only Duality View
 * purpose-built for the listing endpoint.
 *
 * The view includes processKey, processName and versionNumber directly in
 * the document. The current activity is resolved by the service via a
 * separate batch query against wf_instance_activity.
 */
@Slf4j
@Repository
public class WfInstanceListingRepository {

    private static final String VIEW = "wf_instance_listing_dv";

    private static final String FIND_ALL = """
            SELECT json_serialize(data returning clob) FROM %s v
            ORDER BY v.data.createdAt DESC
            OFFSET ? ROWS FETCH NEXT ? ROWS ONLY
            """.formatted(VIEW);

    private static final String FIND_BY_STATUS = """
            SELECT json_serialize(data returning clob) FROM %s v
            WHERE v.data.status = ?
            ORDER BY v.data.createdAt DESC
            OFFSET ? ROWS FETCH NEXT ? ROWS ONLY
            """.formatted(VIEW);

    private static final String FIND_BY_PROCESS_KEY = """
            SELECT json_serialize(data returning clob) FROM %s v
            WHERE v.data.processKey = ?
            ORDER BY v.data.createdAt DESC
            OFFSET ? ROWS FETCH NEXT ? ROWS ONLY
            """.formatted(VIEW);

    private static final String FIND_BY_PROCESS_KEY_AND_STATUS = """
            SELECT json_serialize(data returning clob) FROM %s v
            WHERE v.data.processKey = ?
              AND v.data.status = ?
            ORDER BY v.data.createdAt DESC
            OFFSET ? ROWS FETCH NEXT ? ROWS ONLY
            """.formatted(VIEW);

    private final JdbcTemplate jdbc;
    private final RowMapper<WfInstanceListingDoc> rowMapper;

    public WfInstanceListingRepository(JdbcTemplate jdbc) {
        ObjectMapper mapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

        this.jdbc = jdbc;
        this.rowMapper = (rs, rowNum) -> {
            try {
                return mapper.readValue(rs.getString(1), WfInstanceListingDoc.class);
            } catch (Exception e) {
                throw new RuntimeException("Failed to deserialize WfInstanceListingDoc", e);
            }
        };
    }

    public List<WfInstanceListingDoc> findAll(int page, int size) {
        log.debug("[listing-dv] findAll page={} size={}", page, size);
        return jdbc.query(FIND_ALL, rowMapper, (long) page * size, size);
    }

    public List<WfInstanceListingDoc> findByStatus(String status, int page, int size) {
        log.debug("[listing-dv] findByStatus({}) page={} size={}", status, page, size);
        return jdbc.query(FIND_BY_STATUS, rowMapper, status, (long) page * size, size);
    }

    public List<WfInstanceListingDoc> findByProcessKey(String processKey, int page, int size) {
        log.debug("[listing-dv] findByProcessKey({}) page={} size={}", processKey, page, size);
        return jdbc.query(FIND_BY_PROCESS_KEY, rowMapper, processKey, (long) page * size, size);
    }

    public List<WfInstanceListingDoc> findByProcessKeyAndStatus(
            String processKey, String status, int page, int size) {
        log.debug("[listing-dv] findByProcessKeyAndStatus({}, {}) page={} size={}",
                processKey, status, page, size);
        return jdbc.query(FIND_BY_PROCESS_KEY_AND_STATUS, rowMapper,
                processKey, status, (long) page * size, size);
    }
}