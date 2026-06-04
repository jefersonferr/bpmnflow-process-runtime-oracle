package org.bpmnflow.runtime.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import oracle.jdbc.OracleTypes;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
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
 * Unit tests for {@link PlSqlApiHandlerProvider}.
 *
 * JDBC layer fully mocked — no Oracle database required.
 */
@DisplayName("PlSqlApiHandlerProvider")
@ExtendWith(MockitoExtension.class)
class PlSqlApiHandlerProviderTest {

    @Mock JdbcTemplate      jdbcTemplate;
    @Mock Connection        connection;
    @Mock CallableStatement callableStatement;

    PlSqlApiHandlerProvider provider;
    ObjectMapper            objectMapper = new ObjectMapper();

    static final String ENDPOINT    = "https://api.pagamentos.com/v1/authorize";
    static final String METHOD      = "POST";
    static final Long   INSTANCE_ID = 42L;
    static final String ACTIVITY    = "SC-PMT_AUTH";

    @BeforeEach
    void setUp() throws Exception {
        provider = new PlSqlApiHandlerProvider(jdbcTemplate, objectMapper);

        // Wire JdbcTemplate.execute(ConnectionCallback) → lambda → mock Connection
        lenient().when(jdbcTemplate.execute(
                        any(org.springframework.jdbc.core.ConnectionCallback.class)))
                .thenAnswer(inv -> {
                    org.springframework.jdbc.core.ConnectionCallback<?> cb =
                            inv.getArgument(0);
                    return cb.doInConnection(connection);
                });

        lenient().when(connection.prepareCall(anyString()))
                .thenReturn(callableStatement);

        // connection.createClob() must return a real mock Clob so
        // headersClob.setString() and payloadClob.setString() do not NPE
        lenient().when(connection.createClob())
                .thenAnswer(i -> mock(Clob.class));
    }

    // ---------------------------------------------------------------
    // Helper: build a minimal ApiHandlerContext
    // ---------------------------------------------------------------

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

    // Helper: stub the OUT parameters of EXECUTE_API for a successful call
    private void stubSuccess(String responseBody, int statusCode) throws SQLException {
        Clob responseClob = mock(Clob.class);
        when(callableStatement.getClob(5)).thenReturn(responseClob);
        when(callableStatement.getInt(6)).thenReturn(statusCode);
        if (responseBody != null) {
            when(responseClob.length()).thenReturn((long) responseBody.length());
            when(responseClob.getSubString(1, responseBody.length()))
                    .thenReturn(responseBody);
        } else {
            when(callableStatement.getClob(5)).thenReturn(null);
        }
    }

    // Helper: stub a non-2xx response — status only, no response body needed
    private void stubError(int statusCode) throws SQLException {
        Clob responseClob = mock(Clob.class);
        when(callableStatement.getClob(5)).thenReturn(responseClob);
        when(callableStatement.getInt(6)).thenReturn(statusCode);
        when(responseClob.length()).thenReturn(0L);
        when(responseClob.getSubString(anyLong(), anyInt())).thenReturn("");
    }

    // ---------------------------------------------------------------
    // Happy path
    // ---------------------------------------------------------------

    @Nested
    @DisplayName("successful execution")
    class SuccessTests {

        @Test
        @DisplayName("extracts top-level response field by variableName")
        void extractsTopLevelField() throws Exception {
            stubSuccess(
                    "{\"pagamento_txn_id\":\"TXN-999\",\"pagamento_status\":\"APPROVED\"}",
                    200);

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
        @DisplayName("returns empty map when no output mappings defined")
        void noMappings_returnsEmpty() throws Exception {
            stubSuccess("{\"ok\":true}", 200);

            Map<String, String> result = provider.execute(
                    context(null, Map.of(), List.of()));

            assertTrue(result.isEmpty());
        }

        @Test
        @DisplayName("resolves ${var.name} placeholders in payload before sending")
        void resolvesPayloadPlaceholders() throws Exception {
            stubSuccess("{\"ok\":true}", 200);

            assertDoesNotThrow(() -> provider.execute(context(
                    "{\"customer\":\"${var.clienteId}\",\"amount\":\"${var.valor}\"}",
                    Map.of("clienteId", "C001", "valor", "99.90"),
                    List.of())));
        }

        @Test
        @DisplayName("returns null for field missing in response JSON — no exception")
        void missingField_returnsNull() throws Exception {
            stubSuccess("{\"other\":\"value\"}", 200);

            Map<String, String> result = provider.execute(context(
                    null, Map.of(),
                    List.of(new ApiHandlerContext.OutputMapping(
                            "pagamento_txn_id", "$.transaction_id"))));

            assertTrue(result.containsKey("pagamento_txn_id"));
            assertNull(result.get("pagamento_txn_id"));
        }

        @Test
        @DisplayName("returns null for all mappings when response body is empty")
        void emptyResponseBody_returnsNullMappings() throws Exception {
            stubSuccess(null, 200);

            Map<String, String> result = provider.execute(context(
                    null, Map.of(),
                    List.of(new ApiHandlerContext.OutputMapping("pagamento_txn_id", "$.id"))));

            assertTrue(result.containsKey("pagamento_txn_id"));
            assertNull(result.get("pagamento_txn_id"));
        }

        @Test
        @DisplayName("numeric JSON value returned as string")
        void numericValue_returnedAsString() throws Exception {
            stubSuccess("{\"tempo_estimado_entrega\":35}", 200);

            Map<String, String> result = provider.execute(context(
                    null, Map.of(),
                    List.of(new ApiHandlerContext.OutputMapping(
                            "tempo_estimado_entrega", "$.minutes"))));

            assertEquals("35", result.get("tempo_estimado_entrega"));
        }

        @Test
        @DisplayName("unknown placeholder left unchanged — no exception thrown")
        void unknownPlaceholder_leftUnchanged() throws Exception {
            stubSuccess("{\"ok\":true}", 200);

            assertDoesNotThrow(() -> provider.execute(context(
                    "{\"id\":\"${var.unknown}\"}",
                    Map.of(),
                    List.of())));
        }

        @Test
        @DisplayName("registers OUT parameters 5 (CLOB) and 6 (NUMBER)")
        void registersOutParameters() throws Exception {
            stubSuccess("{}", 200);

            provider.execute(context(null, Map.of(), List.of()));

            verify(callableStatement).registerOutParameter(5, OracleTypes.CLOB);
            verify(callableStatement).registerOutParameter(6, OracleTypes.NUMBER);
        }
    }

    // ---------------------------------------------------------------
    // Failure cases
    // ---------------------------------------------------------------

    @Nested
    @DisplayName("failure cases")
    class FailureTests {

        @Test
        @DisplayName("throws ApiHandlerException on HTTP 400")
        void throws_on400() throws Exception {
            stubError(400);

            ApiHandlerException ex = assertThrows(ApiHandlerException.class,
                    () -> provider.execute(context(null, Map.of(), List.of())));

            assertTrue(ex.getMessage().contains("400"),
                    "Expected '400' in: " + ex.getMessage());
            assertTrue(ex.getMessage().contains(ACTIVITY));
        }

        @Test
        @DisplayName("throws ApiHandlerException on HTTP 500")
        void throws_on500() throws Exception {
            stubError(500);

            ApiHandlerException ex = assertThrows(ApiHandlerException.class,
                    () -> provider.execute(context(null, Map.of(), List.of())));

            assertTrue(ex.getMessage().contains("500"),
                    "Expected '500' in: " + ex.getMessage());
        }

        @Test
        @DisplayName("throws ApiHandlerException on HTTP 401")
        void throws_on401() throws Exception {
            stubError(401);

            assertThrows(ApiHandlerException.class,
                    () -> provider.execute(context(null, Map.of(), List.of())));
        }

        @Test
        @DisplayName("throws ApiHandlerException when response is not valid JSON")
        void throws_whenResponseNotJson() throws Exception {
            stubSuccess("NOT JSON", 200);

            assertThrows(ApiHandlerException.class,
                    () -> provider.execute(context(
                            null, Map.of(),
                            List.of(new ApiHandlerContext.OutputMapping("txn_id", "$.id")))));
        }

        @Test
        @DisplayName("wraps JDBC SQLException in ApiHandlerException")
        void wraps_jdbcException() throws Exception {
            when(callableStatement.execute())
                    .thenThrow(new SQLException("ORA-12541: TNS no listener"));

            ApiHandlerException ex = assertThrows(ApiHandlerException.class,
                    () -> provider.execute(context(null, Map.of(), List.of())));

            assertTrue(ex.getMessage().contains(ACTIVITY));
            assertNotNull(ex.getCause());
        }

        @Test
        @DisplayName("error message includes endpoint and activity abbreviation")
        void errorMessage_includesContext() throws Exception {
            stubError(403);

            ApiHandlerException ex = assertThrows(ApiHandlerException.class,
                    () -> provider.execute(context(null, Map.of(), List.of())));

            assertTrue(ex.getMessage().contains(ENDPOINT));
            assertTrue(ex.getMessage().contains(ACTIVITY));
        }
    }

    // ---------------------------------------------------------------
    // Contract
    // ---------------------------------------------------------------

    @Test
    @DisplayName("providerName returns PlSqlApiHandlerProvider")
    void providerName() {
        assertEquals("PlSqlApiHandlerProvider", provider.providerName());
    }
}