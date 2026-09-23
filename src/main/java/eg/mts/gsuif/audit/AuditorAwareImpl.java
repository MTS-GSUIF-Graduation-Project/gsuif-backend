package eg.mts.gsuif.audit;

import org.springframework.data.domain.AuditorAware;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * Implementation of {@link AuditorAware} for Spring Data JPA Auditing.
 *
 * <p>Captures the username of the currently authenticated principal from
 * {@link SecurityContextHolder}. Falls back to {@code "system"} when
 * no authentication is present (e.g. startup, background tasks, or unauthenticated requests).
 */
@Component("auditorAware")
public class AuditorAwareImpl implements AuditorAware<String> {

    private static final String DEFAULT_AUDITOR = "system";

    @Override
    public Optional<String> getCurrentAuditor() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();

        if (authentication == null
                || !authentication.isAuthenticated()
                || "anonymousUser".equals(authentication.getPrincipal())) {
            return Optional.of(DEFAULT_AUDITOR);
        }

        String username = authentication.getName();
        if (username == null || username.isBlank()) {
            return Optional.of(DEFAULT_AUDITOR);
        }

        return Optional.of(username);
    }
}
