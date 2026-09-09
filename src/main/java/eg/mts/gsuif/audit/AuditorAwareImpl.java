package eg.mts.gsuif.audit;

import org.springframework.data.domain.AuditorAware;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * Resolves the current auditor username from the Spring Security context.
 *
 * <p>Adheres to STD-23: resolves the current authenticated username from
 * {@link SecurityContextHolder}, falling back to {@code "system"} if unauthenticated
 * or anonymous.
 */
@Component("auditorAware")
public class AuditorAwareImpl implements AuditorAware<String> {

    public static final String DEFAULT_SYSTEM_AUDITOR = "system";

    @Override
    public Optional<String> getCurrentAuditor() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();

        if (authentication == null
                || !authentication.isAuthenticated()
                || authentication instanceof AnonymousAuthenticationToken
                || "anonymousUser".equals(authentication.getPrincipal())) {
            return Optional.of(DEFAULT_SYSTEM_AUDITOR);
        }

        return Optional.ofNullable(authentication.getName())
                .filter(name -> !name.isBlank())
                .or(() -> Optional.of(DEFAULT_SYSTEM_AUDITOR));
    }
}
