package org.bpmnflow.runtime.api.mcp;

import org.bpmnflow.runtime.api.ApiHandlerException;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestTemplate;

import java.time.Instant;
import java.util.Map;

/**
 * Manages the lifecycle of the Bearer token used to authenticate against the
 * Autonomous AI Database OAuth 2.1 endpoint.
 *
 * <p>Oracle issues tokens valid for 60 minutes. This class keeps a thread-safe
 * cache and renews the token before expiry based on
 * {@link McpProperties#getTokenTtlMinutes()}. The token is fetched via HTTP POST
 * — separately from the JDBC pool — because cache validity must survive across
 * multiple pooled connections.</p>
 *
 * <p>{@link #invalidate()} should be called whenever the ADB MCP endpoint returns
 * HTTP 401, forcing a fresh token on the next call to {@link #getToken()}.</p>
 *
 * <p>Note: this manager is available for future providers that call the ADB MCP
 * Server directly over HTTP. {@link McpApiHandlerProvider} uses JDBC
 * ({@code DBMS_CLOUD_AI_AGENT.RUN_TEAM}) and does not require a Bearer token
 * at the Java layer — authentication is handled by the JDBC wallet/mTLS.</p>
 */
public class McpTokenManager {

    private static final String GRANT_TYPE = "password";

    private final RestTemplate  restTemplate;
    private final McpProperties properties;

    private volatile String  cachedToken;
    private volatile Instant expiresAt = Instant.EPOCH;

    public McpTokenManager(RestTemplate restTemplate, McpProperties properties) {
        this.restTemplate = restTemplate;
        this.properties   = properties;
    }

    /**
     * Returns a valid Bearer token, fetching a new one if the cache is empty
     * or the TTL has elapsed.
     *
     * @return Bearer token as a plain string (without the {@code "Bearer "} prefix)
     * @throws ApiHandlerException if the auth endpoint is unreachable or returns
     *                             a response without {@code access_token}
     */
    public synchronized String getToken() {
        if (cachedToken != null && Instant.now().isBefore(expiresAt)) {
            return cachedToken;
        }
        return fetchNewToken();
    }

    /**
     * Invalidates the cached token, forcing a new fetch on the next
     * call to {@link #getToken()}.
     */
    public synchronized void invalidate() {
        cachedToken = null;
        expiresAt   = Instant.EPOCH;
    }

    // -------------------------------------------------------------------------
    // Private
    // -------------------------------------------------------------------------

    private String fetchNewToken() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        Map<String, String> body = Map.of(
                "grant_type", GRANT_TYPE,
                "username",   properties.getUsername(),
                "password",   properties.getPassword()
        );

        try {
            @SuppressWarnings("unchecked")
            ResponseEntity<Map<String, Object>> response =
                    (ResponseEntity<Map<String, Object>>) (ResponseEntity<?>) restTemplate.postForEntity(
                            properties.getAuthUrl(),
                            new HttpEntity<>(body, headers),
                            Map.class);

            if (response.getBody() == null
                    || !response.getBody().containsKey("access_token")) {
                throw new ApiHandlerException(
                        "MCP auth endpoint returned no access_token. Status: "
                                + response.getStatusCode());
            }

            cachedToken = (String) response.getBody().get("access_token");
            expiresAt   = Instant.now().plusSeconds(properties.getTokenTtlMinutes() * 60L);
            return cachedToken;

        } catch (ApiHandlerException e) {
            throw e;
        } catch (Exception e) {
            throw new ApiHandlerException(
                    "Failed to obtain MCP Bearer token from: " + properties.getAuthUrl(), e);
        }
    }
}