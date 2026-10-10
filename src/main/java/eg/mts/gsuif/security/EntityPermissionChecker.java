package eg.mts.gsuif.security;

import eg.mts.gsuif.aspect.Loggable;
import eg.mts.gsuif.entity.GsuifRolePermission;
import eg.mts.gsuif.entity.GsuifUser;
import eg.mts.gsuif.repository.GsuifRolePermissionRepository;
import eg.mts.gsuif.repository.GsuifUserRepository;
import eg.mts.gsuif.repository.GsuifUserRoleRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Set;
import java.util.stream.Collectors;

/**
 * Enforces SCRUM-61 permissions stored as free-text permission codes.
 * Entity access uses {@code ENTITY_ACCESS:{SimpleEntityName}}. Field denial uses
 * {@code FIELD_DENY:{SimpleEntityName}:{fieldName}}; every matching field is removed
 * from the response DTO by the calling service.
 */
@Service
@Transactional(readOnly = true)
public class EntityPermissionChecker {

    private static final String ENTITY_ACCESS_PREFIX = "ENTITY_ACCESS:";
    private static final String FIELD_DENY_PREFIX = "FIELD_DENY:";

    private final GsuifUserRepository userRepository;
    private final GsuifUserRoleRepository userRoleRepository;
    private final GsuifRolePermissionRepository rolePermissionRepository;
    private final String securityMode;

    public EntityPermissionChecker(GsuifUserRepository userRepository,
                                   GsuifUserRoleRepository userRoleRepository,
                                   GsuifRolePermissionRepository rolePermissionRepository,
                                   @Value("${gsuif.security.mode:prod}") String securityMode) {
        this.userRepository = userRepository;
        this.userRoleRepository = userRoleRepository;
        this.rolePermissionRepository = rolePermissionRepository;
        this.securityMode = securityMode;
    }

    @Loggable
    public void requireEntityAccess(String entityName, Authentication authentication) {
        if (isDevMode()) {
            return;
        }
        if (!permissionCodes(authentication).contains(ENTITY_ACCESS_PREFIX + entityName)) {
            throw new AccessDeniedException("Entity access denied: " + entityName);
        }
    }

    @Loggable
    public Set<String> getDeniedFields(String entityName, Authentication authentication) {
        if (isDevMode()) {
            return Set.of();
        }
        String prefix = FIELD_DENY_PREFIX + entityName + ":";
        return permissionCodes(authentication).stream()
                .filter(code -> code.startsWith(prefix) && code.length() > prefix.length())
                .map(code -> code.substring(prefix.length()))
                .collect(Collectors.toUnmodifiableSet());
    }

    private Set<String> permissionCodes(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()) {
            throw new AccessDeniedException("Entity access denied: unauthenticated user");
        }
        GsuifUser user = userRepository.findByUsername(authentication.getName())
                .orElseThrow(() -> new AccessDeniedException("Entity access denied: unknown user"));
        return userRoleRepository.findByUser(user).stream()
                .flatMap(userRole -> rolePermissionRepository.findByRole(userRole.getRole()).stream())
                .map(GsuifRolePermission::getPermission)
                .map(permission -> permission.getCode())
                .collect(Collectors.toUnmodifiableSet());
    }

    private boolean isDevMode() {
        return "dev".equalsIgnoreCase(securityMode);
    }
}
