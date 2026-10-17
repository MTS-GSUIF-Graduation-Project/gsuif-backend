package eg.mts.gsuif.generation;

import eg.mts.gsuif.repository.GenerationRunRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@WithMockUser(username = "esraa.abdelrazek")
class GenerationMalformedSpecificationIntegrationTest {
    @Autowired MockMvc mvc;
    @MockitoBean GenerationRunRepository runRepository;
    @MockitoBean GenerationRunService runs;

    @Test void angularNullFieldReturnsBounded400WithoutAttemptOrArtifact() throws Exception {
        String request = """
                {"pageId":"%s","generationType":"ANGULAR","specification":{
                  "specVersion":"1.0.0","basePackage":"com.example.fixture",
                  "entity":{"className":"Gadget","tableName":"gadget","fields":[null],"enversAudited":false},
                  "openApi":null,"operationRoles":{},"angular":{"className":"GadgetComponent","tableColumns":{}}
                }}
                """.formatted(UUID.randomUUID());
        mvc.perform(post("/api/v1/generation/generate").contentType(MediaType.APPLICATION_JSON).content(request))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.statusCode").value(400))
                .andExpect(jsonPath("$.body").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.errors._global").value(
                        "specification.entity.fields[0]: required"))
                .andExpect(header().doesNotExist("X-Generation-Run-Id"));
        verifyNoInteractions(runs, runRepository);
    }
}
