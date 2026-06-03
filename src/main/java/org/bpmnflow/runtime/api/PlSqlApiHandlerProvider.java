package org.bpmnflow.runtime.api;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import oracle.jdbc.OracleTypes;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.CallableStatement;
import java.sql.Clob;
import java.sql.Connection;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * {@link ApiHandlerProvider} that delegates HTTP calls to an Oracle PL/SQL
 * stored procedure via JDBC {@link CallableStatement}.
 *
 * <h2>Activation</h2>
 * <p>Activated by setting the following property in {@code application.yaml}:</p>
 * <pre>
 * bpmnflow:
 *   api-handler:
 *     provider: plsql
 * </pre>
 * <p>Registered by {@link ApiHandlerAutoConfiguration} with
 * {@code @ConditionalOnProperty}, which takes precedence over the
 * default {@link SpringApiHandlerProvider} ({@code @ConditionalOnMissingBean}).</p>
 *
 * <h2>Oracle prerequisites</h2>
 * <ul>
 *   <li>Oracle 19c+ (UTL_HTTP is available since Oracle 11g, but ACL management
 *       changed significantly in 12c+).</li>
 *   <li>Network ACL granting the schema {@code EXECUTE} on {@code UTL_HTTP} and
 *       {@code CONNECT} privilege to the target host:port combination.</li>
 *   <li>The stored procedure {@code BPMNFLOW_API_HANDLER.EXECUTE_API} must be
 *       compiled and accessible in the same schema used by the datasource.
 *       See {@code V009__plsql_api_handler.sql} for the DDL.</li>
 * </ul>
 *
 * <h2>Procedure contract</h2>
 * <pre>{@code
 * PROCEDURE EXECUTE_API(
 *     p_endpoint    IN  VARCHAR2,
 *     p_method      IN  VARCHAR2,
 *     p_payload     IN  CLOB,
 *     p_headers     IN  CLOB,        -- JSON object: {"Header-Name":"value"}
 *     p_response    OUT CLOB,        -- raw JSON response body
 *     p_status_code OUT NUMBER       -- HTTP status code (200, 400, 500, …)
 * );
 * }</pre>
 *
 * <h2>Placeholder resolution and output mapping</h2>
 * <p>Placeholder resolution ({@code ${var.name}}) and output mapping
 * ({@code connector.output.*} → JSON field extraction) are performed in Java,
 * identically to {@link SpringApiHandlerProvider}, keeping PL/SQL focused
 * exclusively on the HTTP transport.</p>
 *
 * <h2>Fail fast</h2>
 * <p>Any non-2xx HTTP status code returned by the procedure throws
 * {@link ApiHandlerException}, blocking the activity transition.
 * The process instance remains {@code ACTIVE} at the current step.</p>
 */
@Slf4j
public class PlSqlApiHandlerProvider implements ApiHandlerProvider {

    private static final Pattern PLACEHOLDER = Pattern.compile("\\$\\{var\\.([^}]+)}");

    /** Fully-qualified call string for the Oracle procedure. */
    private static final String CALL =
            "{ CALL BPMNFLOW_API_HANDLER.EXECUTE_API(?, ?, ?, ?, ?, ?) }";

    private final JdbcTemplate  jdbcTemplate;
    private final ObjectMapper  objectMapper;

    public PlSqlApiHandlerProvider(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    // ---------------------------------------------------------------
    // ApiHandlerProvider
    // ---------------------------------------------------------------

    @Override
    public Map<String, String> execute(ApiHandlerContext context) {
        log.info("[{}] Executing API call via PL/SQL: {} {} (instance={})",
                providerName(), context.getMethod(), context.getEndpoint(),
                context.getInstanceId());

        String resolvedPayload = resolve(context.getPayloadTemplate(), context);
        String headersJson     = buildHeadersJson(context);

        ProcedureResult result = callProcedure(
                context.getEndpoint(),
                context.getMethod(),
                resolvedPayload,
                headersJson,
                context.getActivityAbbreviation());

        log.info("[{}] PL/SQL API call succeeded: {} {} → HTTP {} (instance={})",
                providerName(), context.getMethod(), context.getEndpoint(),
                result.statusCode(), context.getInstanceId());

        return mapResponse(result.responseBody(), context);
    }

    @Override
    public String providerName() {
        return "PlSqlApiHandlerProvider";
    }

    // ---------------------------------------------------------------
    // Procedure call
    // ---------------------------------------------------------------

    /**
     * Executes the Oracle stored procedure and returns the raw response body
     * and HTTP status code.
     *
     * <p>Uses a JDBC {@link CallableStatement} directly (not
     * {@link JdbcTemplate#call}) so that Oracle {@code CLOB} parameters can be
     * bound precisely — Spring's {@code SimpleJdbcCall} does not support
     * {@code OracleTypes.CLOB} OUT parameters reliably across driver versions.</p>
     */
    private ProcedureResult callProcedure(String endpoint, String method,
                                          String payload, String headersJson,
                                          String activityAbbreviation) {
        return jdbcTemplate.execute((Connection conn) -> {
            try (CallableStatement cs = conn.prepareCall(CALL)) {

                // IN parameters
                cs.setString(1, endpoint);
                cs.setString(2, method.toUpperCase());

                if (payload != null && !payload.isBlank()) {
                    Clob payloadClob = conn.createClob();
                    payloadClob.setString(1, payload);
                    cs.setClob(3, payloadClob);
                } else {
                    cs.setNull(3, OracleTypes.CLOB);
                }

                Clob headersClob = conn.createClob();
                headersClob.setString(1, headersJson);
                cs.setClob(4, headersClob);

                // OUT parameters
                cs.registerOutParameter(5, OracleTypes.CLOB);   // p_response
                cs.registerOutParameter(6, OracleTypes.NUMBER); // p_status_code

                cs.execute();

                Clob  responseClob = cs.getClob(5);
                int   statusCode   = cs.getInt(6);
                String responseBody = responseClob != null
                        ? responseClob.getSubString(1, (int) responseClob.length())
                        : null;

                if (statusCode < 200 || statusCode >= 300) {
                    throw new ApiHandlerException(
                            "API call failed for activity '%s': %s %s returned HTTP %d via PL/SQL. Body: %s"
                                    .formatted(activityAbbreviation, method, endpoint,
                                            statusCode, responseBody));
                }

                return new ProcedureResult(responseBody, statusCode);

            } catch (ApiHandlerException ex) {
                throw ex;
            } catch (Exception ex) {
                throw new ApiHandlerException(
                        "PL/SQL API call failed for activity '%s': %s %s. Cause: %s"
                                .formatted(activityAbbreviation, method, endpoint,
                                        ex.getMessage()),
                        ex);
            }
        });
    }

    // ---------------------------------------------------------------
    // Private helpers
    // ---------------------------------------------------------------

    /**
     * Resolves {@code ${var.name}} placeholders in a template string.
     * Unknown placeholders are left unchanged and logged as warnings.
     */
    private String resolve(String template, ApiHandlerContext context) {
        if (template == null || template.isBlank()) return null;

        Matcher       matcher = PLACEHOLDER.matcher(template);
        StringBuilder result  = new StringBuilder();

        while (matcher.find()) {
            String varName = matcher.group(1);
            String value   = context.getInstanceVariables().get(varName);
            if (value == null) {
                log.warn("[{}] Placeholder '${{var.{}}}' not found in instance variables " +
                                "(activity={}, instance={})",
                        providerName(), varName,
                        context.getActivityAbbreviation(), context.getInstanceId());
                value = matcher.group(0);
            }
            matcher.appendReplacement(result, Matcher.quoteReplacement(value));
        }
        matcher.appendTail(result);

        return result.toString();
    }

    /**
     * Serialises the context headers (with placeholder resolution) to a JSON
     * object string for the {@code p_headers IN CLOB} parameter.
     */
    private String buildHeadersJson(ApiHandlerContext context) {
        Map<String, String> resolved = new HashMap<>();
        if (context.getHeaders() != null) {
            context.getHeaders().forEach((k, v) ->
                    resolved.put(k, resolve(v, context)));
        }
        try {
            return objectMapper.writeValueAsString(resolved);
        } catch (Exception ex) {
            log.warn("[{}] Failed to serialise headers to JSON, using empty object", providerName());
            return "{}";
        }
    }

    /**
     * Extracts output variables from the JSON response body using the
     * {@code variableName} of each output mapping as the JSON field key.
     *
     * <p>The {@code jsonPath} field of {@link ApiHandlerContext.OutputMapping}
     * contains the raw Groovy/Spin script from {@code <camunda:outputParameter>},
     * which is not a standard JSONPath expression. This provider ignores the
     * script and looks up the response field by {@code variableName} directly,
     * matching the Camunda convention where the output variable name equals the
     * JSON response field name.</p>
     */
    private Map<String, String> mapResponse(String responseBody, ApiHandlerContext context) {
        Map<String, String> result = new HashMap<>();

        if (context.getOutputMappings() == null || context.getOutputMappings().isEmpty()) {
            log.debug("[{}] No output mappings for activity '{}' (instance={})",
                    providerName(), context.getActivityAbbreviation(), context.getInstanceId());
            return result;
        }

        if (responseBody == null || responseBody.isBlank()) {
            log.warn("[{}] Response body is empty — all output mappings will be null " +
                            "(activity={}, instance={})",
                    providerName(), context.getActivityAbbreviation(), context.getInstanceId());
            context.getOutputMappings().forEach(m -> result.put(m.variableName(), null));
            return result;
        }

        JsonNode root;
        try {
            root = objectMapper.readTree(responseBody);
        } catch (Exception ex) {
            throw new ApiHandlerException(
                    "Failed to parse PL/SQL API response as JSON for activity '%s'. Body: %s"
                            .formatted(context.getActivityAbbreviation(), responseBody),
                    ex);
        }

        for (ApiHandlerContext.OutputMapping mapping : context.getOutputMappings()) {
            JsonNode node = root.get(mapping.variableName());
            String value  = (node == null || node.isNull() || node.isMissingNode())
                    ? null
                    : (node.isTextual() ? node.asText() : node.toString());

            if (value == null) {
                log.warn("[{}] Field '{}' not found in PL/SQL response JSON " +
                                "(activity={}, instance={})",
                        providerName(), mapping.variableName(),
                        context.getActivityAbbreviation(), context.getInstanceId());
            }

            result.put(mapping.variableName(), value);

            log.debug("[{}] Output mapping '{}' = '{}' (activity={}, instance={})",
                    providerName(), mapping.variableName(), value,
                    context.getActivityAbbreviation(), context.getInstanceId());
        }

        return result;
    }

    // ---------------------------------------------------------------
    // Internal types
    // ---------------------------------------------------------------

    /** Holds the raw output of a successful procedure call. */
    private record ProcedureResult(String responseBody, int statusCode) {}
}