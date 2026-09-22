package eg.mts.gsuif.controller;

import tools.jackson.databind.ObjectMapper;
import eg.mts.gsuif.dto.LoginRequest;
import eg.mts.gsuif.security.JwtUtil;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.List;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AuthIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JwtUtil jwtUtil;

    @Test
    void fullAuthFlow_validCredentials_returnsTokenAndAccessesProtectedEndpoint() throws Exception {
        LoginRequest loginRequest = new LoginRequest("admin", "password123");

        MvcResult result = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(loginRequest)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("OK"))
                .andExpect(jsonPath("$.statusCode").value(200))
                .andExpect(jsonPath("$.body.token").exists())
                .andExpect(jsonPath("$.body.username").value("admin"))
                .andExpect(jsonPath("$.body.roles[0]").value("ROLE_ADMIN"))
                .andReturn();

        String responseString = result.getResponse().getContentAsString();
        String token = objectMapper.readTree(responseString).get("body").get("token").asText();

        // Use valid token on a protected endpoint
        mockMvc.perform(get("/api/v1/work-orders")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
    }

    @Test
    void login_invalidCredentials_returns401() throws Exception {
        LoginRequest loginRequest = new LoginRequest("admin", "wrongpassword");

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(loginRequest)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.statusCode").value(401))
                .andExpect(jsonPath("$.clientMessage").value("Invalid username or password"));
    }

    @Test
    void protectedEndpoint_expiredOrInvalidToken_returns401() throws Exception {
        // Invalid token
        mockMvc.perform(get("/api/v1/work-orders")
                        .header("Authorization", "Bearer invalid.jwt.token"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.statusCode").value(401));

        // Incorrectly signed token
        JwtUtil wrongKeyJwtUtil = new JwtUtil("104E635266556A586E3272357538782F413F4428472B4B6250645367566B5970", 3600000);
        String tamperedToken = wrongKeyJwtUtil.generateToken("admin", List.of(new SimpleGrantedAuthority("ROLE_ADMIN")));

        mockMvc.perform(get("/api/v1/work-orders")
                        .header("Authorization", "Bearer " + tamperedToken))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.statusCode").value(401));
    }
}
