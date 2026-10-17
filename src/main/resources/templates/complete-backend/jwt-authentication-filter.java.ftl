<#ftl strip_whitespace=true encoding="UTF-8">
package ${packageName}.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/** Installed only inside the enforced Spring Security chain; not a servlet container filter bean. */
final class JwtAuthenticationFilter extends OncePerRequestFilter {
    private final JwtService tokens;
    private final AccountIdentityService accounts;
    private final tools.jackson.databind.ObjectMapper mapper;

    JwtAuthenticationFilter(JwtService tokens, AccountIdentityService accounts,
            tools.jackson.databind.ObjectMapper mapper) {
        this.tokens = tokens;
        this.accounts = accounts;
        this.mapper = mapper;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return "/api/auth/login".equals(request.getServletPath());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
            FilterChain chain) throws ServletException, IOException {
        String authorization = request.getHeader("Authorization");
        if (authorization != null && authorization.regionMatches(true, 0, "Bearer ", 0, 7)) {
            try {
                var account = accounts.loadById(tokens.validate(authorization.substring(7)));
                var authentication = UsernamePasswordAuthenticationToken.authenticated(
                        account, null, account.getAuthorities());
                SecurityContextHolder.getContext().setAuthentication(authentication);
            } catch (AuthenticationException exception) {
                SecurityContextHolder.clearContext();
                SecurityConfig.writeError(mapper, response, 401);
                return;
            } catch (RuntimeException exception) {
                SecurityContextHolder.clearContext();
                SecurityConfig.writeError(mapper, response, 500);
                return;
            }
        }
        chain.doFilter(request, response);
    }
}
