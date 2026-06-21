package org.bpmnflow.runtime.api.mcp;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.bpmnflow.runtime.api.ApiHandlerException;

/**
 * Encapsulates the defensive parsing of the text returned by
 * {@code DBMS_CLOUD_AI_AGENT.RUN_TEAM}.
 *
 * <h2>Oracle RUN_TEAM response envelope</h2>
 * <p>{@code RUN_TEAM} wraps the agent output in a JSON envelope where the
 * {@code result} field contains a JSON string with <strong>literal newline
 * characters</strong> embedded inside it:</p>
 * <pre>
 * {"status":"success","result":"{\n  \"field\": \"value\"\n}\n"}
 * </pre>
 * <p>Literal control characters inside a JSON string value violate RFC 7159
 * and cause the standard Jackson parser to reject the entire document.
 * This class solves the problem in two steps:</p>
 * <ol>
 *   <li>Parse the outer envelope with a <em>lenient</em> {@link ObjectMapper}
 *       that accepts unquoted control characters
 *       ({@link JsonParser.Feature#ALLOW_UNQUOTED_CONTROL_CHARS}).</li>
 *   <li>Extract the {@code "result"} string value via {@link JsonNode#asText()},
 *       collapse its embedded newlines, and parse it as the effective JSON root
 *       with the standard mapper.</li>
 * </ol>
 *
 * <h2>Extraction strategy</h2>
 * <ol>
 *   <li>Parse the full text with the lenient mapper.</li>
 *   <li>Unwrap the {@code "result"} envelope if present.</li>
 *   <li>If step 1 fails, extract the first JSON object by brace-balancing and
 *       repeat.</li>
 *   <li>On failure, throw {@link ApiHandlerException} with a preview.</li>
 * </ol>
 *
 * <h2>Must be called inside the JDBC ConnectionCallback</h2>
 * <p>{@link #parse(String, ObjectMapper)} must be invoked inside the
 * {@code ConnectionCallback} lambda — after reading the CLOB to {@link String}
 * but before the connection is returned to the pool.</p>
 */
public class McpAgentResponse {

    private static final int PREVIEW_LENGTH = 200;

    private final String   rawText;
    private final JsonNode jsonRoot;

    private McpAgentResponse(String rawText, JsonNode jsonRoot) {
        this.rawText  = rawText;
        this.jsonRoot = jsonRoot;
    }

    /**
     * Parses the agent text returned by {@code RUN_TEAM}, unwrapping the Oracle
     * envelope {@code {"status":"success","result":"..."}} when present.
     *
     * @param agentText    text returned by {@code RUN_TEAM} (read from the CLOB)
     * @param objectMapper shared {@link ObjectMapper} instance
     * @return parsed {@code McpAgentResponse}
     * @throws ApiHandlerException if no valid JSON object is found
     */
    public static McpAgentResponse parse(String agentText, ObjectMapper objectMapper) {
        if (agentText == null || agentText.isBlank()) {
            throw new ApiHandlerException("MCP agent returned an empty response.");
        }

        // Oracle RUN_TEAM embeds literal newline characters inside the "result"
        // string value, which violates RFC 7159. ALLOW_UNQUOTED_CONTROL_CHARS
        // lets Jackson parse the outer envelope without rejecting the document.
        ObjectMapper lenient = objectMapper.copy()
                .enable(JsonParser.Feature.ALLOW_UNQUOTED_CONTROL_CHARS);

        // Strategy 1 — direct lenient parse of the full text
        String trimmed = agentText.trim();
        JsonNode node = tryParse(trimmed, lenient);
        if (node != null) {
            JsonNode unwrapped = unwrapResultEnvelope(node, objectMapper);
            return new McpAgentResponse(agentText, unwrapped != null ? unwrapped : node);
        }

        // Strategy 2 — brace-balance extraction + lenient parse
        String extracted = extractFirstJsonObject(agentText);
        if (extracted != null) {
            node = tryParse(extracted, lenient);
            if (node != null) {
                JsonNode unwrapped = unwrapResultEnvelope(node, objectMapper);
                return new McpAgentResponse(agentText, unwrapped != null ? unwrapped : node);
            }
        }

        String preview = agentText.length() > PREVIEW_LENGTH
                ? agentText.substring(0, PREVIEW_LENGTH) + "..."
                : agentText;
        throw new ApiHandlerException(
                "MCP agent response does not contain a valid JSON object. Preview: " + preview);
    }

    /**
     * Returns the {@link JsonNode} at the given top-level field name.
     *
     * @param fieldName top-level JSON field name (e.g. {@code "tracking_id"})
     * @return the node, or {@code null} if absent or null
     */
    public JsonNode get(String fieldName) {
        if (jsonRoot == null) return null;
        JsonNode node = jsonRoot.get(fieldName);
        return (node == null || node.isNull() || node.isMissingNode()) ? null : node;
    }

    public String   getRawText()  { return rawText; }
    public JsonNode getJsonRoot() { return jsonRoot; }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    /**
     * Detects and unwraps the Oracle {@code RUN_TEAM} envelope
     * {@code {"status":"success","result":"<json-string>"}}.
     *
     * <p>After lenient parsing, {@link JsonNode#asText()} on the {@code result}
     * field returns the raw string with literal newlines intact. These are
     * collapsed before the second parse so the standard mapper can handle them.</p>
     */
    private static JsonNode unwrapResultEnvelope(JsonNode node, ObjectMapper objectMapper) {
        if (node == null || !node.isObject()) return null;

        JsonNode resultNode = node.get("result");
        if (resultNode == null || resultNode.isNull() || resultNode.isMissingNode()) return null;

        // result is already a JSON object — return directly (future-proof)
        if (resultNode.isObject()) return resultNode;

        if (resultNode.isTextual()) {
            // asText() returns the string with embedded literal newlines.
            // Collapse them before parsing so the standard mapper accepts the JSON.
            String innerJson = resultNode.asText()
                    .replace("\r\n", " ")
                    .replace("\n", " ")
                    .replace("\r", " ")
                    .trim();

            JsonNode inner = tryParse(innerJson, objectMapper);
            if (inner != null && inner.isObject()) return inner;

            // Fallback: brace-balance extraction on the collapsed string
            String extracted = extractFirstJsonObject(innerJson);
            if (extracted != null) {
                inner = tryParse(extracted, objectMapper);
                if (inner != null && inner.isObject()) return inner;
            }
        }

        return null;
    }

    private static JsonNode tryParse(String text, ObjectMapper objectMapper) {
        if (text == null || text.isBlank()) return null;
        try {
            return objectMapper.readTree(text);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Extracts the first complete JSON object by brace-balancing,
     * correctly handling escape sequences inside string literals.
     */
    static String extractFirstJsonObject(String text) {
        int start = text.indexOf('{');
        if (start < 0) return null;

        int     depth    = 0;
        boolean inString = false;
        boolean escape   = false;

        for (int i = start; i < text.length(); i++) {
            char c = text.charAt(i);

            if (escape)                { escape = false; continue; }
            if (c == '\\' && inString) { escape = true;  continue; }
            if (c == '"')              { inString = !inString; continue; }
            if (inString)                continue;

            if      (c == '{') depth++;
            else if (c == '}') {
                depth--;
                if (depth == 0) return text.substring(start, i + 1);
            }
        }
        return null;
    }
}