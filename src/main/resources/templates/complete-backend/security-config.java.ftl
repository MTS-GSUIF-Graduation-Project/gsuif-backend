<#ftl strip_whitespace=true encoding="UTF-8">
package ${packageName}.security;

import ${packageName}.api.ApiResponse;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Map;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;

@Configuration(proxyBeanMethods = false)
@org.springframework.security.config.annotation.web.configuration.EnableWebSecurity
public class SecurityConfig {
    private final SecurityMode mode;
    private final tools.jackson.databind.ObjectMapper mapper;

    public SecurityConfig(SecurityMode mode, tools.jackson.databind.ObjectMapper mapper) {
        this.mode = mode;
        this.mapper = mapper;
    }

    @Bean
    SecurityFilterChain applicationSecurity(HttpSecurity http) throws Exception {
        http.httpBasic(basic -> basic.disable()).formLogin(form -> form.disable())
                .csrf(csrf -> csrf.disable());
        if (mode.isDev()) {
            http.authorizeHttpRequests(requests -> requests.anyRequest().permitAll());
        } else {
            // JWT is not installed in this slice. Enforced modes stay closed.
            http.authorizeHttpRequests(requests -> requests.anyRequest().denyAll());
        }
        http.exceptionHandling(errors -> errors
                .authenticationEntryPoint((request, response, exception) -> writeError(response, 401))
                .accessDeniedHandler((request, response, exception) -> writeError(response, 403)));
        return http.build();
    }

    @Bean
    @Profile("dev")
    UserDetailsService devRejectAllUsers() {
        return username -> { throw new UsernameNotFoundException("Account unavailable"); };
    }

    @Bean
    PasswordEncoder passwordEncoder() { return new BCryptPasswordEncoder(); }

    private void writeError(HttpServletResponse response, int status) throws java.io.IOException {
        String message = status == 401 ? "Authentication required" : "Access denied";
        String body = mapper.writeValueAsString(ApiResponse.error(status, message, Map.of("_global", message)));
        response.setStatus(status);
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write(body);
    }
}
