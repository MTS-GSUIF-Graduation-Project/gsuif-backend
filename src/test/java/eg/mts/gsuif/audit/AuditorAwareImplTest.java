package eg.mts.gsuif.audit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.User;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class AuditorAwareImplTest {

    private AuditorAwareImpl auditorAware;

    @BeforeEach
    void setUp() {
        auditorAware = new AuditorAwareImpl();
        SecurityContextHolder.clearContext();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void getCurrentAuditor_whenSecurityContextEmpty_returnsSystem() {
        Optional<String> auditor = auditorAware.getCurrentAuditor();

        assertThat(auditor).isPresent().contains(AuditorAwareImpl.DEFAULT_SYSTEM_AUDITOR);
    }

    @Test
    void getCurrentAuditor_whenAnonymousAuthentication_returnsSystem() {
        AnonymousAuthenticationToken anonymousToken = new AnonymousAuthenticationToken(
                "key",
                "anonymousUser",
                AuthorityUtils.createAuthorityList("ROLE_ANONYMOUS")
        );
        SecurityContextHolder.getContext().setAuthentication(anonymousToken);

        Optional<String> auditor = auditorAware.getCurrentAuditor();

        assertThat(auditor).isPresent().contains(AuditorAwareImpl.DEFAULT_SYSTEM_AUDITOR);
    }

    @Test
    void getCurrentAuditor_whenAuthenticatedUser_returnsUsername() {
        User user = new User("esraa.abdelrazek", "password", AuthorityUtils.createAuthorityList("ROLE_USER"));
        UsernamePasswordAuthenticationToken token = new UsernamePasswordAuthenticationToken(
                user,
                null,
                user.getAuthorities()
        );
        SecurityContextHolder.getContext().setAuthentication(token);

        Optional<String> auditor = auditorAware.getCurrentAuditor();

        assertThat(auditor).isPresent().contains("esraa.abdelrazek");
    }

    @Test
    void getCurrentAuditor_whenPrincipalNameIsBlank_returnsSystem() {
        UsernamePasswordAuthenticationToken token = new UsernamePasswordAuthenticationToken(
                "",
                null,
                AuthorityUtils.NO_AUTHORITIES
        );
        SecurityContextHolder.getContext().setAuthentication(token);

        Optional<String> auditor = auditorAware.getCurrentAuditor();

        assertThat(auditor).isPresent().contains(AuditorAwareImpl.DEFAULT_SYSTEM_AUDITOR);
    }
}
