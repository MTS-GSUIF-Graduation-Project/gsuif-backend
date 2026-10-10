package eg.mts.gsuif.config;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration tests that verify the Spring Security filter chain correctly routes
 * unauthenticated and unauthorized requests through the custom handlers wired in
 * {@link eg.mts.gsuif.config.SecurityConfig}:
 *
 * <ul>
 *   <li>401 — {@link eg.mts.gsuif.security.JwtAuthenticationEntryPoint}
 *   <li>403 — {@link eg.mts.gsuif.security.JwtAccessDeniedHandler}
 * </ul>
 *
 * <p>These tests exercise the real filter chain end-to-end via {@link MockMvc},
 * not the handler implementations in isolation.
 *
 * <p>{@link AdminOnlyEndpointTestConfig} is imported explicitly — it is a
 * {@code @TestConfiguration} and is therefore never included in the production context.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(AdminOnlyEndpointTestConfig.class)
class SecurityConfigIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    // ── 401: Unauthenticated request ──────────────────────────────────────────

    /**
     * A request with no credentials to any protected path must be rejected by
     * {@link eg.mts.gsuif.security.JwtAuthenticationEntryPoint} with HTTP 401 and
     * a standard {@link eg.mts.gsuif.dto.ApiResponse} body (STD-01 / STD-03).
     */
    @Test
    void unauthenticatedRequest_triggers401WithApiResponseBody() throws Exception {
        mockMvc.perform(get("/api/secured-resource"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.statusCode").value(401))
                .andExpect(jsonPath("$.status").value("UNAUTHORIZED"))
                .andExpect(jsonPath("$.clientMessage").value("Authentication required"))
                .andExpect(jsonPath("$.body").doesNotExist())
                .andExpect(jsonPath("$.errors").doesNotExist());
    }

    // ── 403: Authenticated but unauthorized ───────────────────────────────────

    /**
     * A request from an authenticated {@code USER}-role principal to an
     * {@code ADMIN}-only endpoint must be rejected by
     * {@link eg.mts.gsuif.security.JwtAccessDeniedHandler} with HTTP 403 and
     * a standard {@link eg.mts.gsuif.dto.ApiResponse} body (STD-01 / STD-03).
     *
     * <p>{@code @WithMockUser(roles = "USER")} injects an authenticated principal
     * that satisfies {@code anyRequest().authenticated()} but lacks {@code ROLE_ADMIN},
     * so Spring Security method-security raises
     * {@link org.springframework.security.access.AccessDeniedException}.
     */
    @Test
    @WithMockUser(roles = "USER")
    void authenticatedUserWithoutAdminRole_triggers403WithApiResponseBody() throws Exception {
        mockMvc.perform(get("/api/test/admin-only"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.statusCode").value(403))
                .andExpect(jsonPath("$.status").value("FORBIDDEN"))
                .andExpect(jsonPath("$.clientMessage").value("Access denied"))
                .andExpect(jsonPath("$.body").doesNotExist())
                .andExpect(jsonPath("$.errors").doesNotExist());
    }
}
