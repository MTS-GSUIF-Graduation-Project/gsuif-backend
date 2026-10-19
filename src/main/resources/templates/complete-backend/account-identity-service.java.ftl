<#ftl strip_whitespace=true encoding="UTF-8">
package ${packageName}.security;

import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.context.annotation.Profile;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Profile({"test", "prod"})
public class AccountIdentityService implements UserDetailsService {
    // Fixed BCrypt work factor for absent/disabled accounts; never a usable account credential.
    private static final String DUMMY_HASH = "$2a$10$7EqJtq98hPqEX7fNZaFWoOHi/GxDEiHPpsC1I8TslIYyNCI34uQZK";
    private final UserAccountRepository accounts;
    private final PasswordEncoder passwords;

    public AccountIdentityService(UserAccountRepository accounts, PasswordEncoder passwords) {
        this.accounts = accounts;
        this.passwords = passwords;
    }

    /** Credential verification is confined to this transaction; hashes never enter the principal. */
    @Transactional(readOnly = true)
    public AuthenticatedAccount authenticate(String username, String password) {
        UserAccount account = username == null ? null : accounts.findByUsername(username).orElse(null);
        String hash = account != null && account.isEnabled() ? account.getPasswordHash() : DUMMY_HASH;
        boolean validPassword = passwords.matches(password == null ? "" : password, hash);
        if (account == null || !account.isEnabled() || password == null || !validPassword)
            throw new BadCredentialsException("Authentication failed");
        return identity(account);
    }

    @Transactional(readOnly = true)
    public AuthenticatedAccount loadById(UUID id) {
        if (id == null) throw new UsernameNotFoundException("Account unavailable");
        UserAccount account = accounts.findById(id).filter(UserAccount::isEnabled)
                .orElseThrow(() -> new UsernameNotFoundException("Account unavailable"));
        return identity(account);
    }

    @Override
    @Transactional(readOnly = true)
    public UserDetails loadUserByUsername(String username) {
        UserAccount account = accounts.findByUsername(username)
                .filter(UserAccount::isEnabled)
                .orElseThrow(() -> new UsernameNotFoundException("Account unavailable"));
        return identity(account);
    }

    private static AuthenticatedAccount identity(UserAccount account) {
        Set<org.springframework.security.core.GrantedAuthority> roles = account.getRoles().stream()
                .map(role -> (org.springframework.security.core.GrantedAuthority)
                        new SimpleGrantedAuthority(role.name()))
                .collect(Collectors.toUnmodifiableSet());
        return new AuthenticatedAccount(account.getId(), account.getUsername(), roles);
    }
}
