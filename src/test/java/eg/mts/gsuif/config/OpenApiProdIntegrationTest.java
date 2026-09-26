package eg.mts.gsuif.config;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:prodtestdb;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=none",
        "gsuif.security.jwt.secret=thisisaverylongmocksecretthatisatleast256bitslong12345"
})
@AutoConfigureMockMvc
@ActiveProfiles("prod")
public class OpenApiProdIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void whenProdProfile_thenSwaggerDocsUnavailableWith404() throws Exception {
        // Base API docs and swagger config
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/v3/api-docs/swagger-config"))
                .andExpect(status().isNotFound());

        // Group API docs
        mockMvc.perform(get("/v3/api-docs/metadata-api"))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/v3/api-docs/auth-api"))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/v3/api-docs/reference-api"))
                .andExpect(status().isNotFound());

        // Swagger UI endpoints
        mockMvc.perform(get("/swagger-ui.html"))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/swagger-ui/index.html"))
                .andExpect(status().isNotFound());
    }
}
