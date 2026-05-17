package org.bpmnflow.runtime.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.SQLIntegrityConstraintViolationException;

/**
 * Concurrent-safe variable upsert for the JPA path (dualityEnabled=false).
 *
 * Uses JdbcTemplate within the CALLER'S transaction — no REQUIRES_NEW.
 * REQUIRES_NEW causes connection pool starvation under high concurrency
 * because each subtransaction needs a second connection from the pool
 * while the parent transaction holds the first.
 *
 * Strategy: UPDATE-first, then INSERT on miss, then UPDATE again on collision.
 * All three statements run on the same connection/transaction as the caller.
 * The ORA-00001 from a concurrent INSERT is caught and handled locally —
 * it does NOT mark the transaction as rollback-only because we catch the
 * raw SQLException before Spring's exception translators touch it.
 *
 * Note: catching SQLException at the JdbcTemplate level (via StatementCallback)
 * bypasses PersistenceExceptionTranslationInterceptor, which is what was
 * poisoning the transaction in previous attempts.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class VariableUpsertHelper {

    private final JdbcTemplate jdbcTemplate;

    private static final String SQL_UPDATE = """
            UPDATE wf_instance_variable
               SET variable_type  = ?,
                   variable_value = ?,
                   updated_at     = SYSTIMESTAMP
             WHERE instance_id  = ?
               AND variable_key = ?
            """;

    private static final String SQL_INSERT = """
            INSERT INTO wf_instance_variable
                (instance_id, variable_key, variable_type, variable_value, created_at, updated_at)
            VALUES (?, ?, ?, ?, SYSTIMESTAMP, SYSTIMESTAMP)
            """;

    /**
     * Upserts a single variable within the caller's existing transaction.
     * Handles ORA-00001 (concurrent INSERT collision) without nested transactions.
     */
    public void upsert(Long instanceId, String key, String type, String value) {
        // Step 1: try UPDATE (atomic row-level lock)
        int updated = jdbcTemplate.update(SQL_UPDATE, type, value, instanceId, key);
        if (updated > 0) {
            return;  // row existed, updated successfully
        }

        // Step 2: row doesn't exist yet — try INSERT
        // Use execute() with StatementCallback to catch SQLException directly,
        // before Spring's PersistenceExceptionTranslationInterceptor wraps it
        // and marks the transaction as rollback-only.
        boolean inserted = jdbcTemplate.execute((java.sql.Connection conn) -> {
            try (var stmt = conn.prepareStatement(SQL_INSERT)) {
                stmt.setLong(1, instanceId);
                stmt.setString(2, key);
                stmt.setString(3, type);
                stmt.setString(4, value);
                stmt.executeUpdate();
                return true;
            } catch (SQLIntegrityConstraintViolationException e) {
                // ORA-00001: concurrent session beat us to the INSERT
                log.debug("Concurrent INSERT collision for instance={} key={} — retrying with UPDATE",
                        instanceId, key);
                return false;
            }
        });

        // Step 3: if INSERT collided, the row now exists — UPDATE will succeed
        if (!inserted) {
            jdbcTemplate.update(SQL_UPDATE, type, value, instanceId, key);
        }
    }
}