package eg.mts.gsuif.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;

import java.util.Arrays;

@Configuration
public class SecurityConfig {

    private final Environment environment;

    public SecurityConfig(Environment environment) {
        this.environment = environment;
    }

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        boolean isProd = Arrays.asList(environment.getActiveProfiles()).contains("prod");

        return http
                // CSRF is disabled globally and intentionally for the stateless JWT-based authentication
                // (DEC/STD security model), as we do not use cookie sessions.
                // Note: Dev vs prod security toggle (STD-19) is deferred to a follow-up ticket and is
                // not in scope for the initial SCRUM-18 skeleton.
                .csrf(csrf -> csrf.disable())
                .authorizeHttpRequests(authorize -> {
                    if (isProd) {
                        // In production, Swagger/OpenAPI endpoints must be secured and require authentication
                        authorize.requestMatchers("/api/hello").permitAll()
                                 .requestMatchers("/swagger-ui/**", "/swagger-ui.html", "/v3/api-docs/**").authenticated();
                    } else {
                        // In development/local/test profiles, Swagger/OpenAPI endpoints are publicly accessible
                        authorize.requestMatchers("/api/hello", "/swagger-ui/**", "/swagger-ui.html", "/v3/api-docs/**").permitAll();
                    }
                    authorize.anyRequest().authenticated();
                })
                .build();
    }
}
