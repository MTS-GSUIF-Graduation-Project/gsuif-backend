<#ftl strip_whitespace=true encoding="UTF-8">
package ${packageName}.audit;

import ${packageName}.security.AuthenticatedAccount;
import ${packageName}.security.SecurityMode;
import java.util.Optional;
import org.springframework.core.env.Environment;
import org.springframework.data.domain.AuditorAware;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/** Resolves the stored actor without copying credentials or persistence entities. */
public final class GeneratedAppAuditorAware implements AuditorAware<String> {
    private final SecurityMode mode;
    private final String devActor;

    public GeneratedAppAuditorAware(SecurityMode mode, Environment environment) {
        this.mode = java.util.Objects.requireNonNull(mode);
        if (mode.isDev()) {
            String configured = environment.getProperty("GENERATED_APP_DEV_ACTOR");
            if (!valid(configured))
                throw new IllegalStateException("Dev audit actor must be configured with 1 to 100 characters");
            this.devActor = configured;
        } else {
            this.devActor = null;
        }
    }

    @Override
    public Optional<String> getCurrentAuditor() {
        if (mode.isDev()) return Optional.of(devActor);
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()
                || !(authentication.getPrincipal() instanceof AuthenticatedAccount account)
                || !valid(account.getUsername()))
            throw new IllegalStateException("Audit actor unavailable");
        return Optional.of(account.getUsername());
    }

    private static boolean valid(String actor) {
        return actor != null && !actor.isBlank() && actor.codePointCount(0, actor.length()) <= 100;
    }
}
