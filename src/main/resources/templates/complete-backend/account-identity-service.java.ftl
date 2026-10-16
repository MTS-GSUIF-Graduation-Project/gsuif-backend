<#ftl strip_whitespace=true encoding="UTF-8">
package ${packageName}.security;

import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.context.annotation.Profile;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Profile({"test", "prod"})
public class AccountIdentityService implements UserDetailsService {
    private final UserAccountRepository accounts;

    public AccountIdentityService(UserAccountRepository accounts) { this.accounts = accounts; }

    @Override
    @Transactional(readOnly = true)
    public UserDetails loadUserByUsername(String username) {
        UserAccount account = accounts.findByUsername(username)
                .filter(UserAccount::isEnabled)
                .orElseThrow(() -> new UsernameNotFoundException("Account unavailable"));
        Set<org.springframework.security.core.GrantedAuthority> roles = account.getRoles().stream()
                .map(role -> (org.springframework.security.core.GrantedAuthority)
                        new SimpleGrantedAuthority(role.name()))
                .collect(Collectors.toUnmodifiableSet());
        return new AuthenticatedAccount(account.getId(), account.getUsername(), roles);
    }
}
