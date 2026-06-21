package org.bpmnflow.runtime.api.mcp;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Typed configuration for {@link McpApiHandlerProvider}.
 *
 * <p>Bound from:</p>
 * <pre>
 * bpmnflow:
 *   api-handler:
 *     provider: mcp
 *     mcp:
 *       auth-url: https://dataaccess.adb.{region}.oraclecloudapps.com/adb/auth/v1/databases/{ocid}/token
 *       username: ${MCP_DB_USERNAME}
 *       password: ${MCP_DB_PASSWORD}
 *       token-ttl-minutes: 55
 *       team-name: BPMNFLOW_TEAM
 * </pre>
 */
@Setter
@Getter
@ConfigurationProperties(prefix = "bpmnflow.api-handler.mcp")
public class McpProperties {

    /**
     * OAuth 2.1 token endpoint of the Autonomous AI Database.
     * Pattern: {@code https://dataaccess.adb.{region}.oraclecloudapps.com/adb/auth/v1/databases/{ocid}/token}
     */
    private String authUrl;

    /** Database username for the BPMNFLOW schema. */
    private String username;

    /** Database password for the BPMNFLOW schema. */
    private String password;

    /**
     * Token refresh TTL in minutes.
     * Oracle issues tokens valid for 60 minutes; use 55 to refresh before expiry.
     */
    private int tokenTtlMinutes = 55;

    /**
     * Name of the Agent Team registered via {@code DBMS_CLOUD_AI_AGENT.CREATE_TEAM}.
     * Must match the {@code team_name} value in {@code V010__mcp_api_handler.yaml}.
     */
    private String teamName = "BPMNFLOW_TEAM";

}