package org.bpmnflow.runtime.api.mcp;

import org.bpmnflow.runtime.api.ApiHandlerException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestTemplate;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link McpTokenManager}.
 * HTTP layer fully mocked — no ADB endpoint required.
 */
@DisplayName("McpTokenManager")
@ExtendWith(MockitoExtension.class)
class McpTokenManagerTest {

    @Mock
    RestTemplate restTemplate;

    McpProperties   properties;
    McpTokenManager tokenManager;

    @BeforeEach
    void setUp() {
        properties = new McpProperties();
        properties.setAuthUrl("https://dataaccess.adb.sa-saopaulo-1.oraclecloudapps.com"
                + "/adb/auth/v1/databases/ocid1.xxx/token");
        properties.setUsername("BPMNFLOW");
        properties.setPassword("Secret123#");
        properties.setTokenTtlMinutes(55);

        tokenManager = new McpTokenManager(restTemplate, properties);
    }

    @Nested
    @DisplayName("token acquisition")
    class TokenAcquisitionTests {

        @Test
        @DisplayName("fetches a new token when the cache is empty")
        void fetchesNewTokenWhenCacheEmpty() {
            mockAuthEndpoint("token-first");

            String token = tokenManager.getToken();

            assertEquals("token-first", token);
            verify(restTemplate, times(1)).postForEntity(anyString(), any(), eq(Map.class));
        }

        @Test
        @DisplayName("reuses the cached token within the configured TTL")
        void reusesCachedTokenWithinTtl() {
            mockAuthEndpoint("token-cached");

            String first  = tokenManager.getToken();
            String second = tokenManager.getToken();
            String third  = tokenManager.getToken();

            assertEquals("token-cached", first);
            assertEquals("token-cached", second);
            assertEquals("token-cached", third);
            // Auth endpoint called exactly once — token reused on subsequent calls
            verify(restTemplate, times(1)).postForEntity(anyString(), any(), eq(Map.class));
        }

        @Test
        @DisplayName("fetches a new token after invalidate() is called")
        void fetchesNewTokenAfterInvalidation() {
            mockAuthEndpoint("token-renewed");

            tokenManager.getToken();   // prime the cache
            tokenManager.invalidate(); // simulate HTTP 401 from ADB
            String renewed = tokenManager.getToken();

            assertEquals("token-renewed", renewed);
            verify(restTemplate, times(2)).postForEntity(anyString(), any(), eq(Map.class));
        }
    }

    @Nested
    @DisplayName("failure cases")
    class FailureTests {

        @Test
        @DisplayName("throws ApiHandlerException when the auth endpoint is unreachable")
        void throwsWhenEndpointUnreachable() {
            when(restTemplate.postForEntity(anyString(), any(), eq(Map.class)))
                    .thenThrow(new org.springframework.web.client.ResourceAccessException(
                            "Connection refused"));

            ApiHandlerException ex = assertThrows(ApiHandlerException.class,
                    () -> tokenManager.getToken());
            assertTrue(ex.getMessage().contains("Failed to obtain MCP Bearer token"),
                    "Unexpected message: " + ex.getMessage());
        }

        @Test
        @DisplayName("throws ApiHandlerException when response contains no access_token field")
        void throwsWhenNoAccessTokenInResponse() {
            ResponseEntity<Map> response = new ResponseEntity<>(
                    Map.of("error", "invalid_credentials"), HttpStatus.OK);
            when(restTemplate.postForEntity(anyString(), any(), eq(Map.class)))
                    .thenReturn(response);

            ApiHandlerException ex = assertThrows(ApiHandlerException.class,
                    () -> tokenManager.getToken());
            assertTrue(ex.getMessage().contains("no access_token"),
                    "Unexpected message: " + ex.getMessage());
        }

        @Test
        @DisplayName("throws ApiHandlerException on HTTP 401 from auth endpoint")
        void throwsOn401FromAuthEndpoint() {
            when(restTemplate.postForEntity(anyString(), any(), eq(Map.class)))
                    .thenThrow(new org.springframework.web.client.HttpClientErrorException(
                            HttpStatus.UNAUTHORIZED));

            assertThrows(ApiHandlerException.class, () -> tokenManager.getToken());
        }
    }

    // -------------------------------------------------------------------------
    // Helper
    // -------------------------------------------------------------------------

    private void mockAuthEndpoint(String token) {
        ResponseEntity<Map> response = new ResponseEntity<>(
                Map.of("access_token", token), HttpStatus.OK);
        when(restTemplate.postForEntity(anyString(), any(), eq(Map.class)))
                .thenReturn(response);
    }
}