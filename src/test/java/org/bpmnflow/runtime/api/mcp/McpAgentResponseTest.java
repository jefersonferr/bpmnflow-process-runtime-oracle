package org.bpmnflow.runtime.api.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.bpmnflow.runtime.api.ApiHandlerException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link McpAgentResponse}.
 * No Spring context — plain JUnit 5 with a shared ObjectMapper.
 */
@DisplayName("McpAgentResponse")
class McpAgentResponseTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    // -------------------------------------------------------------------------
    // parse() — Strategy 1: direct JSON
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("direct JSON response")
    class DirectJsonTests {

        @Test
        @DisplayName("parses pure JSON and exposes top-level fields via get()")
        void parsesDirectJson() {
            String agentText = "{\"authToken\": \"jwt-abc\", \"expiresIn\": 3600}";

            McpAgentResponse response = McpAgentResponse.parse(agentText, objectMapper);

            assertNotNull(response.get("authToken"));
            assertEquals("jwt-abc", response.get("authToken").asText());
            assertEquals("3600",    response.get("expiresIn").asText());
        }

        @Test
        @DisplayName("get() returns null for a missing top-level field — no exception")
        void returnsNullForMissingField() {
            McpAgentResponse response = McpAgentResponse.parse("{\"a\": \"1\"}", objectMapper);

            assertNull(response.get("b"));
        }

        @Test
        @DisplayName("handles nested JSON objects — get() returns the nested node")
        void handlesNestedObjects() {
            String json = "{\"data\": {\"token\": \"jwt-xyz\"}, \"status\": 200}";

            McpAgentResponse response = McpAgentResponse.parse(json, objectMapper);

            assertNotNull(response.get("data"));
            assertEquals("200", response.get("status").asText());
        }

        @Test
        @DisplayName("numeric value is accessible as text via asText()")
        void numericValueAsText() {
            McpAgentResponse response = McpAgentResponse.parse(
                    "{\"tempo_estimado_entrega\": 35}", objectMapper);

            assertEquals("35", response.get("tempo_estimado_entrega").asText());
        }
    }

    // -------------------------------------------------------------------------
    // parse() — Oracle RUN_TEAM envelope unwrapping
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("Oracle RUN_TEAM envelope unwrapping")
    class OracleEnvelopeTests {

        @Test
        @DisplayName("unwraps Oracle envelope with literal newlines in result string")
        void unwrapsOracleEnvelopeWithLiteralNewlines() {
            // Exact format returned by DBMS_CLOUD_AI_AGENT.RUN_TEAM
            String agentText = "{\"status\":\"success\",\"result\":\"{\n" +
                    "  \\\"entregador_nome\\\": \\\"Ana Oliveira\\\",\n" +
                    "  \\\"tempo_estimado_entrega\\\": 31,\n" +
                    "  \\\"tracking_id\\\": \\\"TRK-036C049F8F\\\"\n" +
                    "}\n\"}";

            McpAgentResponse response = McpAgentResponse.parse(agentText, objectMapper);

            assertEquals("Ana Oliveira",  response.get("entregador_nome").asText());
            assertEquals("31",            response.get("tempo_estimado_entrega").asText());
            assertEquals("TRK-036C049F8F", response.get("tracking_id").asText());
        }

        @Test
        @DisplayName("unwraps Oracle envelope with compact result string (no newlines)")
        void unwrapsOracleEnvelopeCompact() {
            String agentText = "{\"status\":\"success\",\"result\":" +
                    "\"{\\\"pagamento_status\\\":\\\"APPROVED\\\"," +
                    "\\\"pagamento_txn_id\\\":\\\"TXN-ABC123\\\"}\"}";

            McpAgentResponse response = McpAgentResponse.parse(agentText, objectMapper);

            assertEquals("APPROVED",   response.get("pagamento_status").asText());
            assertEquals("TXN-ABC123", response.get("pagamento_txn_id").asText());
        }

        @Test
        @DisplayName("returns result node directly when it is already a JSON object")
        void returnsResultNodeWhenAlreadyObject() {
            String agentText = "{\"status\":\"success\"," +
                    "\"result\":{\"tracking_id\":\"TRK-XYZ\",\"status\":\"OK\"}}";

            McpAgentResponse response = McpAgentResponse.parse(agentText, objectMapper);

            assertEquals("TRK-XYZ", response.get("tracking_id").asText());
            assertEquals("OK",       response.get("status").asText());
        }
    }

    // -------------------------------------------------------------------------
    // parse() — Strategy 2: JSON embedded in reasoning text
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("JSON embedded in reasoning text")
    class EmbeddedJsonTests {

        @Test
        @DisplayName("extracts JSON object when agent prepends reasoning text")
        void extractsJsonAfterReasoningText() {
            String agentText = "Analyzed the process variables.\n"
                    + "Calling the API tool with the resolved parameters.\n"
                    + "{\"orderId\": \"42\", \"status\": \"CONFIRMED\"}";

            McpAgentResponse response = McpAgentResponse.parse(agentText, objectMapper);

            assertEquals("42",        response.get("orderId").asText());
            assertEquals("CONFIRMED", response.get("status").asText());
        }

        @Test
        @DisplayName("extracts JSON when agent appends trailing text after the object")
        void extractsJsonBeforeTrailingText() {
            String agentText = "{\"field\": \"ok\"}\nTask completed successfully.";

            McpAgentResponse response = McpAgentResponse.parse(agentText, objectMapper);

            assertEquals("ok", response.get("field").asText());
        }

        @Test
        @DisplayName("captures root JSON object even when deeply nested objects are present")
        void capturesRootObjectWithNestedObjects() {
            String agentText = "Reasoning done.\n"
                    + "{\"response\": {\"token\": \"abc\", \"meta\": {\"ttl\": 60}}, \"status\": 200}";

            McpAgentResponse response = McpAgentResponse.parse(agentText, objectMapper);

            assertEquals("200", response.get("status").asText());
            assertNotNull(response.get("response"));
        }

        @Test
        @DisplayName("ignores braces inside JSON string literals during extraction")
        void ignoresBracesInsideStrings() {
            String agentText = "{\"template\": \"prefix_{orderId}_suffix\", \"value\": 1}";

            McpAgentResponse response = McpAgentResponse.parse(agentText, objectMapper);

            assertEquals("prefix_{orderId}_suffix", response.get("template").asText());
            assertEquals("1",                       response.get("value").asText());
        }

        @Test
        @DisplayName("handles escaped quotes inside JSON strings during brace-balancing")
        void handlesEscapedQuotes() {
            String agentText = "{\"msg\": \"he said \\\"hello\\\"\"}";

            McpAgentResponse response = McpAgentResponse.parse(agentText, objectMapper);

            assertEquals("he said \"hello\"", response.get("msg").asText());
        }
    }

    // -------------------------------------------------------------------------
    // parse() — failure cases
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("failure cases")
    class FailureTests {

        @Test
        @DisplayName("throws ApiHandlerException on null input")
        void throwsOnNull() {
            ApiHandlerException ex = assertThrows(ApiHandlerException.class,
                    () -> McpAgentResponse.parse(null, objectMapper));
            assertTrue(ex.getMessage().contains("empty response"),
                    "Unexpected message: " + ex.getMessage());
        }

        @Test
        @DisplayName("throws ApiHandlerException on blank input")
        void throwsOnBlank() {
            ApiHandlerException ex = assertThrows(ApiHandlerException.class,
                    () -> McpAgentResponse.parse("   ", objectMapper));
            assertTrue(ex.getMessage().contains("empty response"),
                    "Unexpected message: " + ex.getMessage());
        }

        @Test
        @DisplayName("throws ApiHandlerException when no JSON object is present in the text")
        void throwsWhenNoJsonFound() {
            ApiHandlerException ex = assertThrows(ApiHandlerException.class,
                    () -> McpAgentResponse.parse(
                            "I was unable to complete the task. Please provide more details.",
                            objectMapper));
            assertTrue(ex.getMessage().contains("not contain a valid JSON object"),
                    "Unexpected message: " + ex.getMessage());
        }

        @Test
        @DisplayName("throws ApiHandlerException for unclosed JSON object")
        void throwsForUnclosedJson() {
            assertThrows(ApiHandlerException.class,
                    () -> McpAgentResponse.parse("{\"unclosed\": \"object\"", objectMapper));
        }
    }

    // -------------------------------------------------------------------------
    // extractFirstJsonObject() — isolated algorithm tests
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("extractFirstJsonObject algorithm")
    class ExtractAlgorithmTests {

        @Test
        @DisplayName("returns null when the string contains no opening brace")
        void returnsNullWithNoOpeningBrace() {
            assertNull(McpAgentResponse.extractFirstJsonObject("no json here"));
        }

        @Test
        @DisplayName("returns null for empty string")
        void returnsNullForEmpty() {
            assertNull(McpAgentResponse.extractFirstJsonObject(""));
        }

        @Test
        @DisplayName("extracts simple object surrounded by non-JSON text")
        void extractsSimpleObjectFromMixedText() {
            String text = "prefix {\"k\": \"v\"} suffix";
            assertEquals("{\"k\": \"v\"}", McpAgentResponse.extractFirstJsonObject(text));
        }
    }
}