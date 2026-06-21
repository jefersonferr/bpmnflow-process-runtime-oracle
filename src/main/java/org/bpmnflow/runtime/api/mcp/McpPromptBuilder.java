package org.bpmnflow.runtime.api.mcp;

import org.bpmnflow.runtime.api.ApiHandlerContext;

import java.util.Map;

/**
 * Builds the natural-language prompt sent to {@code DBMS_CLOUD_AI_AGENT.RUN_TEAM}.
 *
 * <h2>Design — Option B2 (revised)</h2>
 * <p>The prompt describes <em>exactly what needs to be executed</em> using an
 * imperative instruction: endpoint, method and payload are presented as direct
 * parameters, not as variables for the agent to reason about. This prevents
 * the ReAct loop from iterating beyond the first tool call.</p>
 *
 * <p>The {@code instruction} attribute in {@code BPMNFLOW_API_TASK} already
 * enforces a single tool call; this prompt reinforces the same constraint at
 * the {@code RUN_TEAM} input level so both layers are consistent.</p>
 *
 * <h2>Prompt structure</h2>
 * <pre>
 * Execute the following API call for BPMN activity 'SC-PMT_AUTH' (instance: 51404):
 *
 * - endpoint: https://api.example.com/v1/authorize
 * - method:   POST
 * - payload:  {"customer_id":"12345","amount":"100.00","currency":"BRL"}
 *
 * Process variables (for reference):
 * - cliente_id: 12345
 * - valor_total: 100.00
 * - itens_pedido: [...]
 *
 * Call the tool exactly once using the endpoint, method and payload above.
 * After receiving the tool result, immediately return a single valid JSON object
 * containing only the output field values.
 * Do not call the tool again. Do not explain. Do not use markdown or code blocks.
 * </pre>
 */
public class McpPromptBuilder {

    private static final String STOP_INSTRUCTION =
            "Call the tool exactly once using the endpoint, method and payload above. " +
                    "After receiving the tool result, immediately return a single valid JSON object " +
                    "containing only the output field values. " +
                    "Do not call the tool again. Do not explain. Do not use markdown or code blocks.";

    private McpPromptBuilder() {}

    /**
     * Builds the full prompt for the agent from the resolved activity context.
     *
     * <p>All {@link ApiHandlerContext} properties — including {@code endpoint},
     * {@code method} and {@code payloadTemplate} (already with placeholders
     * resolved by {@link org.bpmnflow.runtime.api.ApiHandlerExecutor}) — are
     * presented as direct execution parameters, not as variables for the agent
     * to reason about. Instance variables are exposed separately as reference
     * data only.</p>
     *
     * @param context activity context with all placeholders already resolved
     * @return free-text prompt to be passed to {@code RUN_TEAM}
     */
    public static String build(ApiHandlerContext context) {
        StringBuilder sb = new StringBuilder();

        sb.append("Execute the following API call for BPMN activity '")
                .append(context.getActivityAbbreviation())
                .append("' (instance: ")
                .append(context.getInstanceId())
                .append("):\n\n");

        sb.append("- endpoint: ").append(context.getEndpoint()).append("\n");
        sb.append("- method:   ").append(context.getMethod()).append("\n");

        if (context.getPayloadTemplate() != null && !context.getPayloadTemplate().isBlank()) {
            sb.append("- payload:  ").append(context.getPayloadTemplate()).append("\n");
        }

        Map<String, String> vars = context.getInstanceVariables();
        if (vars != null && !vars.isEmpty()) {
            sb.append("\nProcess variables (for reference):\n");
            vars.forEach((key, value) ->
                    sb.append("- ").append(key).append(": ").append(value).append("\n")
            );
        }

        sb.append("\n").append(STOP_INSTRUCTION);

        return sb.toString();
    }
}