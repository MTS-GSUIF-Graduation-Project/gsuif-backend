<#ftl strip_whitespace=true encoding="UTF-8">
package ${packageName}.bootstrap;

import ${packageName}.security.UserAccount;
import ${packageName}.security.UserAccountRepository;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/** Invoked only by the non-web bootstrap context, never by normal application scanning. */
@Service
public class FirstAdminBootstrapService {
    private final UserAccountRepository accounts;
    private final PasswordEncoder passwords;

    public FirstAdminBootstrapService(UserAccountRepository accounts, PasswordEncoder passwords) {
        this.accounts = accounts;
        this.passwords = passwords;
    }

    /** Caller reports success only after this proxied transaction commits. */
    @Transactional(isolation = Isolation.SERIALIZABLE)
    public boolean createIfNoAdmin(String username, char[] password) {
        if (accounts.countWithRole(UserAccount.Role.ROLE_ADMIN) != 0) return false;
        UserAccount admin = new UserAccount();
        admin.setUsername(username);
        admin.setPasswordHash(passwords.encode(new String(password)));
        admin.setEnabled(true);
        admin.getRoles().add(UserAccount.Role.ROLE_ADMIN);
        accounts.saveAndFlush(admin);
        return true;
    }
}
