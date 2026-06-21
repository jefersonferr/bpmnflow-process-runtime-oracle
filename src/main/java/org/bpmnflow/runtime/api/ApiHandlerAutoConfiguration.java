package org.bpmnflow.runtime.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.client.RestTemplate;

/**
 * Auto-configuration for the {@link ApiHandlerProvider} SPI.
 *
 * <h2>Provider selection</h2>
 * <p>The active provider is controlled by the property:</p>
 * <pre>
 * bpmnflow:
 *   api-handler:
 *     provider: spring   # default — SpringApiHandlerProvider (universal fallback)
 *                plsql   # PlSqlApiHandlerProvider (Oracle 19c+ with UTL_HTTP ACL)
 *                mcp     # McpApiHandlerProvider (Oracle Autonomous AI Database)
 * </pre>
 *
 * <p>When {@code provider: plsql} is set, {@link PlSqlApiHandlerProvider} is
 * registered and the {@link SpringApiHandlerProvider} bean is suppressed
 * ({@code @ConditionalOnMissingBean}).</p>
 *
 * <p>When {@code provider: mcp} is set, {@code McpApiHandlerAutoConfiguration}
 * registers {@code McpApiHandlerProvider} and the {@link SpringApiHandlerProvider}
 * fallback is suppressed via {@code @ConditionalOnProperty(matchIfMissing=false)}.</p>
 *
 * <p>Applications that declare their own {@link ApiHandlerProvider} bean
 * (e.g. {@code SelectAiApiHandlerProvider}) automatically replace all defaults
 * without any further configuration.</p>
 *
 * <h2>Provider resolution order</h2>
 * <ol>
 *   <li>Application-declared {@link ApiHandlerProvider} bean (highest priority)</li>
 *   <li>{@link PlSqlApiHandlerProvider} when {@code bpmnflow.api-handler.provider=plsql}</li>
 *   <li>{@code McpApiHandlerProvider} when {@code bpmnflow.api-handler.provider=mcp}</li>
 *   <li>{@link SpringApiHandlerProvider} — universal fallback (default)</li>
 * </ol>
 */
@Configuration
public class ApiHandlerAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(RestTemplate.class)
    public RestTemplate restTemplate() {
        return new RestTemplate();
    }

    /**
     * Registers {@link PlSqlApiHandlerProvider} when
     * {@code bpmnflow.api-handler.provider=plsql} is set.
     */
    @Bean
    @ConditionalOnMissingBean(ApiHandlerProvider.class)
    @ConditionalOnProperty(
            name        = "bpmnflow.api-handler.provider",
            havingValue = "plsql"
    )
    public ApiHandlerProvider plSqlApiHandlerProvider(JdbcTemplate jdbcTemplate,
                                                      ObjectMapper objectMapper) {
        return new PlSqlApiHandlerProvider(jdbcTemplate, objectMapper);
    }

    /**
     * Registers {@link SpringApiHandlerProvider} as the universal fallback
     * when no other {@link ApiHandlerProvider} bean is present.
     *
     * <p>Explicitly excluded for {@code provider=plsql} and {@code provider=mcp}
     * to prevent this fallback from winning the {@code @ConditionalOnMissingBean}
     * race against provider-specific configurations processed in the same phase.</p>
     */
    @Bean
    @ConditionalOnMissingBean(ApiHandlerProvider.class)
    @ConditionalOnProperty(
            name        = "bpmnflow.api-handler.provider",
            havingValue = "spring",
            matchIfMissing = true
    )
    public ApiHandlerProvider springApiHandlerProvider(RestTemplate restTemplate,
                                                       ObjectMapper objectMapper) {
        return new SpringApiHandlerProvider(restTemplate, objectMapper);
    }
}