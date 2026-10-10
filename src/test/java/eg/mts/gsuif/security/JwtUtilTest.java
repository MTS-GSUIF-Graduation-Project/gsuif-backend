package eg.mts.gsuif.security;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class JwtUtilTest {

    private JwtUtil jwtUtil;

    @BeforeEach
    void setUp() {
        jwtUtil = new JwtUtil("404E635266556A586E3272357538782F413F4428472B4B6250645367566B5970", 3600000);
    }

    @Test
    void testGenerateAndValidateToken() {
        String token = jwtUtil.generateToken("testuser", Collections.singletonList(new SimpleGrantedAuthority("ROLE_USER")));
        assertNotNull(token);
        assertTrue(jwtUtil.validateToken(token));
        assertEquals("testuser", jwtUtil.extractUsername(token));

        List<String> roles = jwtUtil.extractRoles(token);
        assertNotNull(roles);
        assertEquals(1, roles.size());
        assertEquals("ROLE_USER", roles.get(0));
    }

    @Test
    void testInvalidToken() {
        assertFalse(jwtUtil.validateToken("invalidTokenString"));
    }
}
