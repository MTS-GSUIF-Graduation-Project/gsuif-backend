<#ftl strip_whitespace=true encoding="UTF-8">
package ${packageName}.security;

import java.util.Collection;
import java.util.Set;
import java.util.UUID;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

/** Detached immutable identity; it never holds the account entity or password hash. */
public record AuthenticatedAccount(UUID id, String username, Set<GrantedAuthority> roles) implements UserDetails {
    public AuthenticatedAccount {
        if (id == null || username == null || username.isBlank() || roles == null)
            throw new IllegalArgumentException("Invalid account identity");
        roles = Set.copyOf(roles);
    }

    @Override public Collection<? extends GrantedAuthority> getAuthorities() { return roles; }
    @Override public String getUsername() { return username; }
    @Override public String getPassword() { return null; }
}
