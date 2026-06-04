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
 * </pre>
 *
 * <p>When {@code provider: plsql} is set, {@link PlSqlApiHandlerProvider} is
 * registered and the {@link SpringApiHandlerProvider} bean is suppressed
 * ({@code @ConditionalOnMissingBean}).</p>
 *
 * <p>Applications that declare their own {@link ApiHandlerProvider} bean
 * (e.g. {@code McpApiHandlerProvider}, {@code SelectAiApiHandlerProvider})
 * automatically replace both defaults without any further configuration.</p>
 *
 * <h2>Provider resolution order</h2>
 * <ol>
 *   <li>Application-declared {@link ApiHandlerProvider} bean (highest priority)</li>
 *   <li>{@link PlSqlApiHandlerProvider} when {@code bpmnflow.api-handler.provider=plsql}</li>
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
     *
     * <p>Takes precedence over {@link SpringApiHandlerProvider} because it
     * registers a concrete {@link ApiHandlerProvider} bean, which causes
     * the {@code @ConditionalOnMissingBean} on the Spring fallback to fire.</p>
     */
    @Bean
    @ConditionalOnMissingBean(ApiHandlerProvider.class)
    @ConditionalOnProperty(
            name         = "bpmnflow.api-handler.provider",
            havingValue  = "plsql"
    )
    public ApiHandlerProvider plSqlApiHandlerProvider(JdbcTemplate jdbcTemplate,
                                                      ObjectMapper objectMapper) {
        return new PlSqlApiHandlerProvider(jdbcTemplate, objectMapper);
    }

    /**
     * Registers {@link SpringApiHandlerProvider} as the universal fallback
     * when no other {@link ApiHandlerProvider} bean is present.
     */
    @Bean
    @ConditionalOnMissingBean(ApiHandlerProvider.class)
    public ApiHandlerProvider springApiHandlerProvider(RestTemplate restTemplate,
                                                       ObjectMapper objectMapper) {
        return new SpringApiHandlerProvider(restTemplate, objectMapper);
    }
}