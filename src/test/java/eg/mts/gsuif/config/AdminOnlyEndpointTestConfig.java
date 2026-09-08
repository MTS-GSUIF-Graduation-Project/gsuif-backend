package eg.mts.gsuif.config;

import eg.mts.gsuif.security.JwtAccessDeniedHandler;
import eg.mts.gsuif.security.JwtAuthenticationEntryPoint;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Test-only security configuration that registers an additional {@link SecurityFilterChain}
 * exclusively for the path {@code /api/test/admin-only}.
 *
 * <p>This chain requires {@code ROLE_ADMIN} at the URL level. When an authenticated user
 * without that role hits the path, Spring Security's {@code ExceptionTranslationFilter}
 * raises an {@link org.springframework.security.access.AccessDeniedException} and delegates
 * to the production {@link JwtAccessDeniedHandler}, producing the correct 403 response body.
 *
 * <p>No controller is needed — the request is rejected by the filter chain before it
 * ever reaches the dispatcher servlet. This avoids any handler-mapping ambiguity and
 * does not create or modify production endpoints or authorization rules.
 *
 * <p>The chain runs at {@code @Order(0)} so it matches before the production
 * {@link SecurityConfig} chain, which is unordered (effective order = {@code Integer.MAX_VALUE}).
 * The production chain is untouched.
 */
@TestConfiguration
public class AdminOnlyEndpointTestConfig {

    /**
     * A filter chain that secures {@code /api/test/admin-only} with {@code ROLE_ADMIN},
     * wired to the same exception handlers as production so that the 403 body shape
     * (STD-01 / STD-03) is verified end-to-end.
     */
    @Bean
    @Order(0)
    SecurityFilterChain adminTestFilterChain(
            HttpSecurity http,
            JwtAuthenticationEntryPoint jwtAuthenticationEntryPoint,
            JwtAccessDeniedHandler jwtAccessDeniedHandler) throws Exception {

        return http
                .securityMatcher("/api/test/admin-only")
                .csrf(csrf -> csrf.disable())
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint(jwtAuthenticationEntryPoint)
                        .accessDeniedHandler(jwtAccessDeniedHandler)
                )
                .authorizeHttpRequests(auth -> auth.anyRequest().hasRole("ADMIN"))
                .build();
    }
}
