package org.bpmnflow.runtime.api.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import oracle.jdbc.OracleTypes;
import org.bpmnflow.runtime.api.ApiHandlerContext;
import org.bpmnflow.runtime.api.ApiHandlerException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.CallableStatement;
import java.sql.Clob;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link McpApiHandlerProvider}.
 * JDBC layer fully mocked — no Oracle database required.
 * Follows the same structure and conventions as {@code PlSqlApiHandlerProviderTest}.
 */
@DisplayName("McpApiHandlerProvider")
@ExtendWith(MockitoExtension.class)
class McpApiHandlerProviderTest {

    @Mock JdbcTemplate      jdbcTemplate;
    @Mock Connection        connection;
    @Mock CallableStatement callableStatement;

    McpApiHandlerProvider provider;
    ObjectMapper          objectMapper = new ObjectMapper();

    static final Long   INSTANCE_ID = 42L;
    static final String ACTIVITY    = "SC-PMT_AUTH";
    static final String ENDPOINT    = "https://api.pagamentos.com/v1/authorize";
    static final String METHOD      = "POST";

    @BeforeEach
    void setUp() throws Exception {
        McpProperties properties = new McpProperties();
        properties.setTeamName("BPMNFLOW_TEAM");
        properties.setAuthUrl("https://auth.example.com/token");
        properties.setUsername("BPMNFLOW");
        properties.setPassword("Secret123#");
        properties.setTokenTtlMinutes(55);

        provider = new McpApiHandlerProvider(jdbcTemplate, objectMapper, properties);

        // Wire JdbcTemplate.execute(ConnectionCallback) → lambda → mock Connection
        lenient().when(jdbcTemplate.execute(any(ConnectionCallback.class)))
                .thenAnswer(inv -> {
                    ConnectionCallback<?> cb = inv.getArgument(0);
                    return cb.doInConnection(connection);
                });

        lenient().when(connection.prepareCall(anyString()))
                .thenReturn(callableStatement);

        // connection.createClob() for the prompt CLOB parameter
        lenient().when(connection.createClob())
                .thenAnswer(i -> mock(Clob.class));
    }

    // -------------------------------------------------------------------------
    // Helper: build ApiHandlerContext using builder (consistent with project SPI)
    // -------------------------------------------------------------------------

    private ApiHandlerContext context(String payload,
                                      Map<String, String> vars,
                                      List<ApiHandlerContext.OutputMapping> mappings) {
        return ApiHandlerContext.builder()
                .instanceId(INSTANCE_ID)
                .activityAbbreviation(ACTIVITY)
                .endpoint(ENDPOINT)
                .method(METHOD)
                .headers(Map.of())
                .payloadTemplate(payload)
                .instanceVariables(vars)
                .outputMappings(mappings)
                .build();
    }

    // Helper: stub the OUT CLOB (param 1) for a successful RUN_TEAM call
    private void stubRunTeamSuccess(String agentResponse) throws SQLException {
        Clob responseClob = mock(Clob.class);
        when(callableStatement.getClob(1)).thenReturn(responseClob);
        when(responseClob.length()).thenReturn((long) agentResponse.length());
        when(responseClob.getSubString(1, agentResponse.length())).thenReturn(agentResponse);
    }

    // Helper: stub RUN_TEAM to return a null CLOB
    private void stubRunTeamNull() throws SQLException {
        when(callableStatement.getClob(1)).thenReturn(null);
    }

    // -------------------------------------------------------------------------
    // Happy path
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("successful execution")
    class SuccessTests {

        @Test
        @DisplayName("extracts top-level response fields by variableName")
        void extractsTopLevelFields() throws Exception {
            stubRunTeamSuccess("{\"pagamento_txn_id\": \"TXN-999\", \"pagamento_status\": \"APPROVED\"}");

            Map<String, String> result = provider.execute(context(
                    null, Map.of(),
                    List.of(
                            new ApiHandlerContext.OutputMapping("pagamento_txn_id",  "$.transaction_id"),
                            new ApiHandlerContext.OutputMapping("pagamento_status",   "$.status")
                    )));

            assertEquals("TXN-999",  result.get("pagamento_txn_id"));
            assertEquals("APPROVED", result.get("pagamento_status"));
        }

        @Test
        @DisplayName("returns empty map when no output mappings are defined")
        void noMappings_returnsEmpty() throws Exception {
            stubRunTeamSuccess("{\"any\": \"value\"}");

            Map<String, String> result = provider.execute(
                    context(null, Map.of(), List.of()));

            assertTrue(result.isEmpty());
        }

        @Test
        @DisplayName("returns null for a variable whose field is absent in the agent JSON")
        void missingField_returnsNull() throws Exception {
            stubRunTeamSuccess("{\"other\": \"value\"}");

            Map<String, String> result = provider.execute(context(
                    null, Map.of(),
                    List.of(new ApiHandlerContext.OutputMapping("pagamento_txn_id", "$.id"))));

            assertTrue(result.containsKey("pagamento_txn_id"));
            assertNull(result.get("pagamento_txn_id"));
        }

        @Test
        @DisplayName("extracts JSON from response that contains reasoning text before the object")
        void extractsJsonFromReasoningText() throws Exception {
            String agentText = "I analyzed the variables and determined the action.\n"
                    + "{\"orderId\": \"42\", \"status\": \"CONFIRMED\"}";
            stubRunTeamSuccess(agentText);

            Map<String, String> result = provider.execute(context(
                    null, Map.of(),
                    List.of(
                            new ApiHandlerContext.OutputMapping("orderId", "$.orderId"),
                            new ApiHandlerContext.OutputMapping("status",  "$.status")
                    )));

            assertEquals("42",          result.get("orderId"));
            assertEquals("CONFIRMED",   result.get("status"));
        }

        @Test
        @DisplayName("numeric JSON value returned as string")
        void numericValue_returnedAsString() throws Exception {
            stubRunTeamSuccess("{\"tempo_estimado_entrega\": 35}");

            Map<String, String> result = provider.execute(context(
                    null, Map.of(),
                    List.of(new ApiHandlerContext.OutputMapping(
                            "tempo_estimado_entrega", "$.minutes"))));

            assertEquals("35", result.get("tempo_estimado_entrega"));
        }

        @Test
        @DisplayName("registers OUT parameter 1 as OracleTypes.CLOB")
        void registersOutParameterAsClob() throws Exception {
            stubRunTeamSuccess("{\"ok\": true}");

            provider.execute(context(null, Map.of(), List.of()));

            verify(callableStatement).registerOutParameter(1, OracleTypes.CLOB);
        }

        @Test
        @DisplayName("passes team name as parameter 2")
        void passesTeamName() throws Exception {
            stubRunTeamSuccess("{\"ok\": true}");

            provider.execute(context(null, Map.of(), List.of()));

            verify(callableStatement).setString(2, "BPMNFLOW_TEAM");
        }

        @Test
        @DisplayName("passes conversation_id as CLOB in params JSON (two-step CREATE_CONVERSATION)")
        void passesConversationIdAsClob() throws Exception {
            stubRunTeamSuccess("{\"ok\": true}");

            // Stub getString(1) for CREATE_CONVERSATION step
            when(callableStatement.getString(1)).thenReturn("test-conv-id");

            provider.execute(context(null, Map.of(), List.of()));

            // Step 1: CREATE_CONVERSATION OUT param registered as VARCHAR
            verify(callableStatement).registerOutParameter(1, java.sql.Types.VARCHAR);
            // Step 2: params CLOB bound at position 4
            verify(callableStatement).setClob(eq(4), any(Clob.class));
        }
    }

    // -------------------------------------------------------------------------
    // Failure cases
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("failure cases")
    class FailureTests {

        @Test
        @DisplayName("throws ApiHandlerException when RUN_TEAM returns a null CLOB")
        void throwsWhenClobIsNull() throws Exception {
            stubRunTeamNull();

            ApiHandlerException ex = assertThrows(ApiHandlerException.class,
                    () -> provider.execute(context(null, Map.of(), List.of())));
            assertTrue(ex.getMessage().contains("returned NULL"),
                    "Unexpected message: " + ex.getMessage());
        }

        @Test
        @DisplayName("throws ApiHandlerException when agent response contains no valid JSON")
        void throwsWhenResponseIsNotJson() throws Exception {
            stubRunTeamSuccess("I was unable to complete the task. Please provide more details.");

            assertThrows(ApiHandlerException.class,
                    () -> provider.execute(context(
                            null, Map.of(),
                            List.of(new ApiHandlerContext.OutputMapping("txn_id", "$.id")))));
        }

        @Test
        @DisplayName("wraps JDBC SQLException in ApiHandlerException")
        void wrapsJdbcException() throws Exception {
            when(callableStatement.execute())
                    .thenThrow(new SQLException("ORA-20001: DBMS_CLOUD_AI_AGENT error"));

            ApiHandlerException ex = assertThrows(ApiHandlerException.class,
                    () -> provider.execute(context(null, Map.of(), List.of())));
            assertTrue(ex.getMessage().contains(ACTIVITY));
            assertNotNull(ex.getCause());
        }

        @Test
        @DisplayName("error message includes activityAbbreviation and instanceId")
        void errorMessageIncludesContext() throws Exception {
            stubRunTeamNull();

            ApiHandlerException ex = assertThrows(ApiHandlerException.class,
                    () -> provider.execute(context(null, Map.of(), List.of())));
            assertTrue(ex.getMessage().contains(ACTIVITY),
                    "Expected activity in: " + ex.getMessage());
            assertTrue(ex.getMessage().contains(String.valueOf(INSTANCE_ID)),
                    "Expected instance ID in: " + ex.getMessage());
        }
    }

    // -------------------------------------------------------------------------
    // Contract
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("providerName() returns McpApiHandlerProvider")
    void providerName() {
        assertEquals("McpApiHandlerProvider", provider.providerName());
    }
}