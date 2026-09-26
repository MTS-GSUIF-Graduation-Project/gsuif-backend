package eg.mts.gsuif.audit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Collections;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
    void getCurrentAuditor_whenUnauthenticated_returnsSystem() {
        Optional<String> auditor = auditorAware.getCurrentAuditor();

        assertTrue(auditor.isPresent());
        assertEquals("system", auditor.get());
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

        assertTrue(auditor.isPresent());
        assertEquals("system", auditor.get());
    }

    @Test
    void getCurrentAuditor_whenAuthenticated_returnsUsername() {
        UsernamePasswordAuthenticationToken auth =
                new UsernamePasswordAuthenticationToken("john_doe", "pass", Collections.emptyList());
        SecurityContextHolder.getContext().setAuthentication(auth);

        Optional<String> auditor = auditorAware.getCurrentAuditor();

        assertTrue(auditor.isPresent());
        assertEquals("john_doe", auditor.get());
    }
}
