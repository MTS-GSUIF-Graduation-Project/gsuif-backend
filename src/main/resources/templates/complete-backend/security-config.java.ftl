<#ftl strip_whitespace=true encoding="UTF-8">
package ${packageName}.security;

import ${packageName}.api.ApiResponse;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Map;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.http.HttpMethod;

@Configuration(proxyBeanMethods = false)
@org.springframework.security.config.annotation.web.configuration.EnableWebSecurity
public class SecurityConfig {
    private final SecurityMode mode;
    private final tools.jackson.databind.ObjectMapper mapper;
    private final ObjectProvider<JwtService> tokens;
    private final ObjectProvider<AccountIdentityService> accounts;

    public SecurityConfig(SecurityMode mode, tools.jackson.databind.ObjectMapper mapper,
            ObjectProvider<JwtService> tokens, ObjectProvider<AccountIdentityService> accounts) {
        this.mode = mode;
        this.mapper = mapper;
        this.tokens = tokens;
        this.accounts = accounts;
    }

    @Bean
    SecurityFilterChain applicationSecurity(HttpSecurity http) throws Exception {
        http.httpBasic(basic -> basic.disable()).formLogin(form -> form.disable())
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .requestCache(cache -> cache.disable());
        if (mode.isDev()) {
            http.authorizeHttpRequests(requests -> requests.anyRequest().permitAll());
        } else {
            http.authorizeHttpRequests(requests -> requests
                    .requestMatchers(HttpMethod.POST, "/api/auth/login").permitAll()
                    .requestMatchers("/v3/api-docs", "/v3/api-docs/**", "/swagger-ui.html", "/swagger-ui/**").permitAll()
                    .anyRequest().authenticated());
            http.addFilterBefore(new JwtAuthenticationFilter(tokens.getObject(), accounts.getObject(), mapper),
                    UsernamePasswordAuthenticationFilter.class);
        }
        http.exceptionHandling(errors -> errors
                .authenticationEntryPoint((request, response, exception) -> writeError(mapper, response, 401))
                .accessDeniedHandler((request, response, exception) -> writeError(mapper, response, 403)));
        return http.build();
    }

    @Bean
    @Profile("dev")
    UserDetailsService devRejectAllUsers() {
        return username -> { throw new UsernameNotFoundException("Account unavailable"); };
    }

    static void writeError(tools.jackson.databind.ObjectMapper mapper,
            HttpServletResponse response, int status) throws java.io.IOException {
        String message = status == 401 ? "Authentication required"
                : status == 403 ? "Access denied" : "Unexpected server error";
        String body = mapper.writeValueAsString(ApiResponse.error(status, message, Map.of("_global", message)));
        response.setStatus(status);
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write(body);
    }
}
