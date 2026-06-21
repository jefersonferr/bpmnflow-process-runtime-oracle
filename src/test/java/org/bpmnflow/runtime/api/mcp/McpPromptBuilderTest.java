package org.bpmnflow.runtime.api.mcp;

import org.bpmnflow.runtime.api.ApiHandlerContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link McpPromptBuilder}.
 *
 * No Spring context — plain JUnit 5.
 */
@DisplayName("McpPromptBuilder")
class McpPromptBuilderTest {

    // -------------------------------------------------------------------------
    // Context builder helper — mirrors PlSqlApiHandlerProviderTest style
    // -------------------------------------------------------------------------

    private ApiHandlerContext context(String endpoint, String method,
                                      String payload, Map<String, String> vars) {
        return ApiHandlerContext.builder()
                .instanceId(42L)
                .activityAbbreviation("SC-PMT_AUTH")
                .endpoint(endpoint)
                .method(method)
                .payloadTemplate(payload)
                .instanceVariables(vars)
                .outputMappings(List.of())
                .build();
    }

    // -------------------------------------------------------------------------
    // Content tests
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("prompt content")
    class ContentTests {

        @Test
        @DisplayName("includes activityAbbreviation and instanceId in the prompt header")
        void includesActivityAndInstanceId() {
            ApiHandlerContext ctx = context(null, null, null, Map.of());

            String prompt = McpPromptBuilder.build(ctx);

            assertTrue(prompt.contains("SC-PMT_AUTH"), "Expected activity abbreviation");
            assertTrue(prompt.contains("42"),          "Expected instance ID");
        }

        @Test
        @DisplayName("exposes connector.input.* properties as available variables")
        void exposesConnectorInputProperties() {
            ApiHandlerContext ctx = context(
                    "https://api.example.com/auth",
                    "POST",
                    "{\"clientId\":\"C-99\"}",
                    Map.of());

            String prompt = McpPromptBuilder.build(ctx);

            assertTrue(prompt.contains("connector.input.url: https://api.example.com/auth"));
            assertTrue(prompt.contains("connector.input.method: POST"));
            assertTrue(prompt.contains("connector.input.payload: {\"clientId\":\"C-99\"}"));
        }

        @Test
        @DisplayName("exposes current instance variables in the prompt")
        void exposesInstanceVariables() {
            ApiHandlerContext ctx = context(null, null, null,
                    Map.of("orderId", "42", "customerId", "C-99"));

            String prompt = McpPromptBuilder.build(ctx);

            assertTrue(prompt.contains("orderId: 42"),      "Expected orderId variable");
            assertTrue(prompt.contains("customerId: C-99"), "Expected customerId variable");
        }

        @Test
        @DisplayName("contains the JSON-only response instruction")
        void containsJsonInstruction() {
            String prompt = McpPromptBuilder.build(context(null, null, null, Map.of()));

            assertTrue(prompt.contains("single valid JSON object only"));
            assertTrue(prompt.contains("No explanation"));
            assertTrue(prompt.contains("No markdown"));
            assertTrue(prompt.contains("No code block"));
        }
    }

    // -------------------------------------------------------------------------
    // Option B2 compliance — no tool name in prompt
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("Option B2 compliance")
    class OptionB2Tests {

        @Test
        @DisplayName("does not mention the tool name — agent decides which tool to call")
        void doesNotMentionToolName() {
            ApiHandlerContext ctx = context(
                    "https://api.example.com", "POST", "{}", Map.of("orderId", "1"));

            String prompt = McpPromptBuilder.build(ctx);

            assertFalse(prompt.contains("BPMNFLOW_API_CALLER"),
                    "Prompt must not name the tool — Option B2 requires agent autonomy");
        }
    }

    // -------------------------------------------------------------------------
    // Edge cases
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("edge cases")
    class EdgeCaseTests {

        @Test
        @DisplayName("generates a non-blank minimal prompt with all-null connector fields")
        void generatesMinimalPromptWithNullFields() {
            String prompt = McpPromptBuilder.build(context(null, null, null, Map.of()));

            assertNotNull(prompt);
            assertFalse(prompt.isBlank());
            assertTrue(prompt.contains("single valid JSON object only"));
        }

        @Test
        @DisplayName("omits connector.input.* lines when values are blank")
        void omitsBlankConnectorFields() {
            ApiHandlerContext ctx = context("", null, "  ", Map.of());

            String prompt = McpPromptBuilder.build(ctx);

            assertFalse(prompt.contains("connector.input.url"),     "Should omit blank url");
            assertFalse(prompt.contains("connector.input.method"),   "Should omit null method");
            assertFalse(prompt.contains("connector.input.payload"),  "Should omit blank payload");
        }
    }
}