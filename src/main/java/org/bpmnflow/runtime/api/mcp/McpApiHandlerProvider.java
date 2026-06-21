package org.bpmnflow.runtime.api.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import oracle.jdbc.OracleTypes;
import org.bpmnflow.runtime.api.ApiHandlerContext;
import org.bpmnflow.runtime.api.ApiHandlerException;
import org.bpmnflow.runtime.api.ApiHandlerProvider;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.CallableStatement;
import java.sql.Clob;
import java.util.HashMap;
import java.util.Map;

/**
 * {@link ApiHandlerProvider} that delegates API calls to the
 * Oracle Autonomous AI Database via {@code DBMS_CLOUD_AI_AGENT.RUN_TEAM}.
 *
 * <h2>Activation</h2>
 * <pre>
 * bpmnflow:
 *   api-handler:
 *     provider: mcp
 * </pre>
 *
 * <h2>Differentiator over PlSqlApiHandlerProvider</h2>
 * <p>The ReAct agent receives the full map of resolved process variables and a
 * free-text description of the task, <strong>without being told which tool to
 * call or which parameter values to use</strong>. The agent reasons about the
 * process context, selects the most appropriate variable values, and invokes
 * {@code BPMNFLOW_API_CALLER} — a custom MCP tool backed by
 * {@code DBMS_CLOUD.SEND_REQUEST} — without requiring an Oracle network ACL
 * at the Java layer. This transforms a structured process into an intelligent
 * one: the same {@code .bpmn} file produces context-aware API calls.</p>
 *
 * <h2>Oracle prerequisites</h2>
 * <ul>
 *   <li>Oracle Autonomous AI Database 19c (19.29+) or 26ai.</li>
 *   <li>AI Profile {@code BPMNFLOW_AI_PROFILE} created via
 *       {@code DBMS_CLOUD_AI.CREATE_PROFILE}.</li>
 *   <li>{@code GRANT EXECUTE ON DBMS_CLOUD_AI       TO BPMNFLOW;}</li>
 *   <li>{@code GRANT EXECUTE ON DBMS_CLOUD_AI_AGENT TO BPMNFLOW;}</li>
 *   <li>Migration {@code V010__mcp_api_handler.yaml} executed with
 *       Liquibase context {@code oracle}.</li>
 * </ul>
 *
 * <h2>JDBC call sequence</h2>
 * <p>The Oracle internal table
 * {@code DBMS_CLOUD_AI_CONVERSATION_PROMPT$.CONVERSATION_ID#} has a NOT NULL
 * constraint. Passing {@code params => NULL} to {@code RUN_TEAM} causes
 * {@code ORA-01400} because the engine does not auto-generate the ID.
 * The correct approach is a two-step sequence:</p>
 * <ol>
 *   <li>Call {@code DBMS_CLOUD_AI.CREATE_CONVERSATION()} to obtain a valid
 *       {@code conversation_id}.</li>
 *   <li>Pass it to {@code RUN_TEAM} via
 *       {@code params => '{"conversation_id":"<id>"}'}</li>
 * </ol>
 * <p>Each activity execution creates a fresh conversation — stateless by
 * design. Process state is carried by instance variables in the prompt;
 * agent session memory is not needed for single-step activities.</p>
 *
 * <h2>RUN_TEAM JDBC contract (positional)</h2>
 * <pre>{@code
 * -- Step 1
 * BEGIN :1 := DBMS_CLOUD_AI.CREATE_CONVERSATION(); END;
 *   :1 OUT VARCHAR2  — conversation_id
 *
 * -- Step 2
 * BEGIN :1 := DBMS_CLOUD_AI_AGENT.RUN_TEAM(
 *     team_name   => :2,   IN  VARCHAR2
 *     user_prompt => :3,   IN  CLOB
 *     params      => :4    IN  CLOB  {"conversation_id":"<id>"}
 * ); END;
 *   :1 OUT CLOB  — agent JSON response
 * }</pre>
 *
 * <h2>Output mapping</h2>
 * <p>The agent is instructed to respond with a single JSON object. Output
 * mapping uses top-level field lookup via {@code variableName} — identical
 * to {@link org.bpmnflow.runtime.api.PlSqlApiHandlerProvider} — keeping
 * the mapping convention consistent across all providers.</p>
 */
@Slf4j
public class McpApiHandlerProvider implements ApiHandlerProvider {

    /**
     * Step 1 — create a conversation and return its ID.
     * Required because DBMS_CLOUD_AI_CONVERSATION_PROMPT$.CONVERSATION_ID#
     * is NOT NULL and RUN_TEAM does not auto-generate it when params is NULL.
     */
    private static final String CREATE_CONVERSATION_SQL =
            "BEGIN ? := DBMS_CLOUD_AI.CREATE_CONVERSATION(); END;";

    /**
     * Step 2 — invoke the agent team with the conversation_id in params.
     *
     * <p>Positional parameters:</p>
     * <ol>
     *   <li>OUT CLOB     — agent response</li>
     *   <li>IN  VARCHAR2 — team_name</li>
     *   <li>IN  CLOB     — user_prompt</li>
     *   <li>IN  CLOB     — params JSON with conversation_id</li>
     * </ol>
     */
    private static final String RUN_TEAM_SQL =
            "BEGIN ? := DBMS_CLOUD_AI_AGENT.RUN_TEAM(" +
                    "  team_name   => ?," +
                    "  user_prompt => ?," +
                    "  params      => ?" +
                    "); END;";

    private final JdbcTemplate  jdbcTemplate;
    private final ObjectMapper  objectMapper;
    private final McpProperties properties;

    public McpApiHandlerProvider(JdbcTemplate jdbcTemplate,
                                 ObjectMapper objectMapper,
                                 McpProperties properties) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.properties   = properties;
    }

    // -------------------------------------------------------------------------
    // ApiHandlerProvider
    // -------------------------------------------------------------------------

    @Override
    public Map<String, String> execute(ApiHandlerContext context) {
        log.info("[{}] Invoking RUN_TEAM '{}' for activity '{}' (instance={})",
                providerName(), properties.getTeamName(),
                context.getActivityAbbreviation(), context.getInstanceId());

        String prompt = McpPromptBuilder.build(context);

        McpAgentResponse agentResponse = callRunTeam(prompt, context);

        log.info("[{}] RUN_TEAM completed for activity '{}' (instance={})",
                providerName(), context.getActivityAbbreviation(), context.getInstanceId());

        return mapResponse(agentResponse, context);
    }

    @Override
    public String providerName() {
        return "McpApiHandlerProvider";
    }

    // -------------------------------------------------------------------------
    // JDBC calls
    // -------------------------------------------------------------------------

    /**
     * Executes the two-step JDBC sequence and returns the parsed agent response.
     *
     * <p>Both statements share the same connection to ensure the conversation
     * created in step 1 is visible to {@code RUN_TEAM} in step 2. All CLOB
     * locators are read inside the {@code ConnectionCallback} lambda — before
     * the connection is returned to the pool — because the Oracle JDBC driver
     * invalidates CLOB references after the connection closes.</p>
     */
    private McpAgentResponse callRunTeam(String prompt, ApiHandlerContext context) {
        return jdbcTemplate.execute((java.sql.Connection conn) -> {
            try {
                // ── Step 1: create conversation ───────────────────────────
                String conversationId;
                try (CallableStatement cs = conn.prepareCall(CREATE_CONVERSATION_SQL)) {
                    cs.registerOutParameter(1, java.sql.Types.VARCHAR);
                    cs.execute();
                    conversationId = cs.getString(1);
                }

                log.debug("[{}] Created conversation_id='{}' for activity '{}' (instance={})",
                        providerName(), conversationId,
                        context.getActivityAbbreviation(), context.getInstanceId());

                // ── Step 2: invoke RUN_TEAM ───────────────────────────────
                String responseText;
                try (CallableStatement cs = conn.prepareCall(RUN_TEAM_SQL)) {

                    cs.registerOutParameter(1, OracleTypes.CLOB);
                    cs.setString(2, properties.getTeamName());

                    Clob promptClob = conn.createClob();
                    promptClob.setString(1, prompt);
                    cs.setClob(3, promptClob);

                    String paramsJson = "{\"conversation_id\":\"" + conversationId + "\"}";
                    Clob   paramsClob = conn.createClob();
                    paramsClob.setString(1, paramsJson);
                    cs.setClob(4, paramsClob);

                    cs.execute();

                    Clob responseClob = cs.getClob(1);
                    if (responseClob == null) {
                        throw new ApiHandlerException(
                                "DBMS_CLOUD_AI_AGENT.RUN_TEAM returned NULL for activity '"
                                        + context.getActivityAbbreviation() + "' (instance="
                                        + context.getInstanceId() + ")");
                    }

                    responseText = responseClob.getSubString(1, (int) responseClob.length());
                    responseClob.free();
                }

                log.debug("[{}] Raw agent response for activity '{}': {}",
                        providerName(), context.getActivityAbbreviation(),
                        responseText.length() > 500
                                ? responseText.substring(0, 500) + "..."
                                : responseText);

                return McpAgentResponse.parse(responseText, objectMapper);

            } catch (ApiHandlerException ex) {
                throw ex;
            } catch (Exception ex) {
                throw new ApiHandlerException(
                        "RUN_TEAM failed for activity '%s' (instance=%d). Cause: %s"
                                .formatted(context.getActivityAbbreviation(),
                                        context.getInstanceId(), ex.getMessage()),
                        ex);
            }
        });
    }

    // -------------------------------------------------------------------------
    // Output mapping
    // -------------------------------------------------------------------------

    /**
     * Applies {@code connector.output.*} output mappings to the agent's JSON response.
     *
     * <p>Field lookup uses the {@code variableName} of each
     * {@link ApiHandlerContext.OutputMapping} as the top-level JSON key —
     * consistent with {@link org.bpmnflow.runtime.api.PlSqlApiHandlerProvider}.
     * The {@code jsonPath} field of the mapping is intentionally ignored:
     * the agent is responsible for returning a flat JSON object whose keys
     * match the expected variable names.</p>
     *
     * @param response parsed agent response
     * @param context  activity context carrying the output mapping list
     * @return variable name → extracted string value (null if field absent)
     */
    private Map<String, String> mapResponse(McpAgentResponse response, ApiHandlerContext context) {
        Map<String, String> result = new HashMap<>();

        if (context.getOutputMappings() == null || context.getOutputMappings().isEmpty()) {
            log.debug("[{}] No output mappings for activity '{}' (instance={})",
                    providerName(), context.getActivityAbbreviation(), context.getInstanceId());
            return result;
        }

        for (ApiHandlerContext.OutputMapping mapping : context.getOutputMappings()) {
            JsonNode node  = response.get(mapping.variableName());
            String   value = null;

            if (node == null) {
                log.warn("[{}] Field '{}' not found in MCP agent response " +
                                "(activity={}, instance={})",
                        providerName(), mapping.variableName(),
                        context.getActivityAbbreviation(), context.getInstanceId());
            } else {
                value = node.isTextual() ? node.asText() : node.toString();
            }

            result.put(mapping.variableName(), value);

            log.debug("[{}] Output mapping '{}' = '{}' (activity={}, instance={})",
                    providerName(), mapping.variableName(), value,
                    context.getActivityAbbreviation(), context.getInstanceId());
        }

        return result;
    }
}