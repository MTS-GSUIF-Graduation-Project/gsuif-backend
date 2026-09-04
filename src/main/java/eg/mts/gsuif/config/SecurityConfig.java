package eg.mts.gsuif.config;

import eg.mts.gsuif.security.JwtAccessDeniedHandler;
import eg.mts.gsuif.security.JwtAuthenticationEntryPoint;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;

import java.util.Arrays;

@Configuration
public class SecurityConfig {

    private final Environment environment;
    private final JwtAuthenticationEntryPoint jwtAuthenticationEntryPoint;
    private final JwtAccessDeniedHandler jwtAccessDeniedHandler;

    public SecurityConfig(Environment environment,
                          JwtAuthenticationEntryPoint jwtAuthenticationEntryPoint,
                          JwtAccessDeniedHandler jwtAccessDeniedHandler) {
        this.environment = environment;
        this.jwtAuthenticationEntryPoint = jwtAuthenticationEntryPoint;
        this.jwtAccessDeniedHandler = jwtAccessDeniedHandler;
    }

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        boolean isProd = Arrays.asList(environment.getActiveProfiles()).contains("prod");

        return http
                .csrf(csrf -> csrf.disable())
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint(jwtAuthenticationEntryPoint)
                        .accessDeniedHandler(jwtAccessDeniedHandler)
                )
                .authorizeHttpRequests(authorize -> {
                    if (isProd) {
                        authorize.requestMatchers("/api/hello").permitAll()
                                 .requestMatchers("/swagger-ui/**", "/swagger-ui.html", "/v3/api-docs/**").authenticated();
                    } else {
                        authorize.requestMatchers("/api/hello", "/swagger-ui/**", "/swagger-ui.html", "/v3/api-docs/**").permitAll();
                    }
                    authorize.anyRequest().authenticated();
                })
                .build();
    }
}
