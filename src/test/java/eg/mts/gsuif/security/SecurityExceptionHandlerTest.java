package eg.mts.gsuif.security;

import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;

import static org.assertj.core.api.Assertions.assertThat;

class SecurityExceptionHandlerTest {

    private ObjectMapper objectMapper;
    private JwtAuthenticationEntryPoint entryPoint;
    private JwtAccessDeniedHandler accessDeniedHandler;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        entryPoint = new JwtAuthenticationEntryPoint(objectMapper);
        accessDeniedHandler = new JwtAccessDeniedHandler(objectMapper);
    }

    @Test
    void commence_returns401WithApiResponse() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRequestURI("/api/protected");
        MockHttpServletResponse response = new MockHttpServletResponse();

        entryPoint.commence(request, response, new BadCredentialsException("Bad credentials"));

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getContentType()).contains("application/json");

        String json = response.getContentAsString();
        assertThat(json).contains("\"statusCode\":401");
        assertThat(json).contains("\"status\":\"UNAUTHORIZED\"");
        assertThat(json).contains("\"clientMessage\":\"Authentication required\"");
    }

    @Test
    void handle_returns403WithApiResponse() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRequestURI("/api/admin");
        MockHttpServletResponse response = new MockHttpServletResponse();

        accessDeniedHandler.handle(request, response, new AccessDeniedException("Access denied"));

        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(response.getContentType()).contains("application/json");

        String json = response.getContentAsString();
        assertThat(json).contains("\"statusCode\":403");
        assertThat(json).contains("\"status\":\"FORBIDDEN\"");
        assertThat(json).contains("\"clientMessage\":\"Access denied\"");
    }
}
