package eg.mts.gsuif.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
public class SecurityConfig {

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        return http
                // CSRF is disabled globally and intentionally for the stateless JWT-based authentication
                // (DEC/STD security model), as we do not use cookie sessions.
                // Note: Dev vs prod security toggle (STD-19) is deferred to a follow-up ticket and is
                // not in scope for the initial SCRUM-18 skeleton.
                .csrf(csrf -> csrf.disable())
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers("/api/hello", "/swagger-ui/**", "/swagger-ui.html", "/v3/api-docs/**")
                        .permitAll()
                        .anyRequest().authenticated())
                .build();
    }
}
