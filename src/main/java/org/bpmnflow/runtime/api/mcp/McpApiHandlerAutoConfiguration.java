package org.bpmnflow.runtime.api.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.bpmnflow.runtime.api.ApiHandlerProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.client.RestTemplate;

/**
 * Auto-configuration for {@link McpApiHandlerProvider}.
 *
 * <h2>Activation</h2>
 * <pre>
 * bpmnflow:
 *   api-handler:
 *     provider: mcp
 * </pre>
 *
 * <h2>RestTemplate</h2>
 * <p>{@link McpTokenManager} requires a {@link RestTemplate} to call the ADB OAuth
 * endpoint. The existing {@code ApiHandlerAutoConfiguration} already declares one
 * with {@code @ConditionalOnMissingBean}, so this configuration reuses it instead
 * of declaring a competing bean. The {@code McpTokenManager} receives whatever
 * {@code RestTemplate} is present in the context.</p>
 *
 * <h2>Provider registration</h2>
 * <p>{@link McpApiHandlerProvider} is registered with
 * {@code @ConditionalOnMissingBean(ApiHandlerProvider.class)}, consistent with
 * the pattern used for {@code PlSqlApiHandlerProvider} and
 * {@code SpringApiHandlerProvider} in {@code ApiHandlerAutoConfiguration}.
 * Registering this configuration class (e.g. via {@code @Import} or component
 * scan) when {@code provider=mcp} is set is sufficient to replace both defaults.</p>
 */
@Configuration
@EnableConfigurationProperties(McpProperties.class)
@ConditionalOnProperty(
        prefix      = "bpmnflow.api-handler",
        name        = "provider",
        havingValue = "mcp"
)
public class McpApiHandlerAutoConfiguration {

    /**
     * {@link McpTokenManager} bean — reuses the {@link RestTemplate} already
     * declared by {@code ApiHandlerAutoConfiguration} (or the application context).
     */
    @Bean
    public McpTokenManager mcpTokenManager(RestTemplate restTemplate,
                                           McpProperties mcpProperties) {
        return new McpTokenManager(restTemplate, mcpProperties);
    }

    /**
     * Registers {@link McpApiHandlerProvider} when {@code provider=mcp} is set
     * and no other {@link ApiHandlerProvider} bean is present.
     */
    @Bean
    @ConditionalOnMissingBean(ApiHandlerProvider.class)
    public ApiHandlerProvider mcpApiHandlerProvider(JdbcTemplate jdbcTemplate,
                                                    ObjectMapper objectMapper,
                                                    McpProperties mcpProperties) {
        return new McpApiHandlerProvider(jdbcTemplate, objectMapper, mcpProperties);
    }
}