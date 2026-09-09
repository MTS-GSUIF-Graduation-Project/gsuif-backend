package eg.mts.gsuif.exception;

import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;

import org.springframework.test.context.ActiveProfiles;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import org.springframework.context.annotation.Import;
import static org.hamcrest.Matchers.aMapWithSize;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.containsString;
import org.springframework.validation.annotation.Validated;
import jakarta.validation.constraints.Min;
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(GlobalExceptionHandlerIntegrationTest.TestController.class)
public class GlobalExceptionHandlerIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @RestController
    @RequestMapping("/api/test-exception")
    @Validated
    public static class TestController {

        @PostMapping("/valid")
        public String valid(@Valid @RequestBody TestRequest request) {
            return "OK";
        }

        @GetMapping("/type-mismatch/{id}")
        public String typeMismatch(@PathVariable UUID id) {
            return id.toString();
        }

        @GetMapping("/missing-param")
        public String missingParam(@RequestParam String param) {
            return param;
        }

        @GetMapping("/generic-error")
        public String genericError() {
            throw new RuntimeException("Test generic exception");
        }
        
        @PostMapping("/unsupported-media")
        public String unsupportedMedia(@RequestBody TestRequest request) {
            return "OK";
        }

        @GetMapping("/constraint")
        public String constraint(@RequestParam @Min(1) int page) {
            return String.valueOf(page);
        }
    }

    public static class TestRequest {
        @NotNull
        public String field;
    }

    @Test
    @WithMockUser
    void testUnknownRoute_Returns404() throws Exception {
        mockMvc.perform(get("/api/unknown-route"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$", aMapWithSize(5)))
                .andExpect(jsonPath("$.status").value("NOT_FOUND"))
                .andExpect(jsonPath("$.statusCode").value(404))
                .andExpect(jsonPath("$.body").isEmpty())
                .andExpect(jsonPath("$.errors").isEmpty())
                .andExpect(jsonPath("$.clientMessage").exists());
    }

    @Test
    @WithMockUser
    void testMethodArgumentNotValid_Returns400() throws Exception {
        TestRequest request = new TestRequest(); // field is null
        mockMvc.perform(post("/api/test-exception/valid")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$", aMapWithSize(5)))
                .andExpect(jsonPath("$.status").value("BAD_REQUEST"))
                .andExpect(jsonPath("$.clientMessage").value("Validation failed"))
                .andExpect(jsonPath("$.statusCode").value(400))
                .andExpect(jsonPath("$.body").isEmpty())
                .andExpect(jsonPath("$.errors.field").exists());
    }

    @Test
    @WithMockUser
    void testMalformedJson_Returns400() throws Exception {
        mockMvc.perform(post("/api/test-exception/valid")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{ invalid json }"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$", aMapWithSize(5)))
                .andExpect(jsonPath("$.status").value("BAD_REQUEST"))
                .andExpect(jsonPath("$.clientMessage").value("Malformed request body"))
                .andExpect(jsonPath("$.statusCode").value(400))
                .andExpect(jsonPath("$.body").isEmpty())
                .andExpect(jsonPath("$.errors").isEmpty())
                .andExpect(content().string(not(containsString("JsonParseException"))));
    }

    @Test
    @WithMockUser
    void testTypeMismatch_Returns400() throws Exception {
        mockMvc.perform(get("/api/test-exception/type-mismatch/not-a-uuid"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$", aMapWithSize(5)))
                .andExpect(jsonPath("$.status").value("BAD_REQUEST"))
                .andExpect(jsonPath("$.clientMessage").value("Invalid request parameter"))
                .andExpect(jsonPath("$.statusCode").value(400))
                .andExpect(jsonPath("$.body").isEmpty())
                .andExpect(jsonPath("$.errors").isEmpty());
    }

    @Test
    @WithMockUser
    void testMissingParam_Returns400() throws Exception {
        mockMvc.perform(get("/api/test-exception/missing-param"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$", aMapWithSize(5)))
                .andExpect(jsonPath("$.status").value("BAD_REQUEST"))
                .andExpect(jsonPath("$.clientMessage").value("Required request parameter is missing"))
                .andExpect(jsonPath("$.statusCode").value(400))
                .andExpect(jsonPath("$.body").isEmpty())
                .andExpect(jsonPath("$.errors").isEmpty());
    }

    @Test
    @WithMockUser
    void testMethodNotSupported_Returns405() throws Exception {
        mockMvc.perform(post("/api/test-exception/missing-param?param=value"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$", aMapWithSize(5)))
                .andExpect(jsonPath("$.status").value("METHOD_NOT_ALLOWED"))
                .andExpect(jsonPath("$.clientMessage").value("HTTP method not supported"))
                .andExpect(jsonPath("$.statusCode").value(405))
                .andExpect(jsonPath("$.body").isEmpty())
                .andExpect(jsonPath("$.errors").isEmpty());
    }

    @Test
    @WithMockUser
    void testUnsupportedMediaType_Returns415() throws Exception {
        TestRequest request = new TestRequest();
        request.field = "valid";
        mockMvc.perform(post("/api/test-exception/unsupported-media")
                .contentType(MediaType.TEXT_PLAIN)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$", aMapWithSize(5)))
                .andExpect(jsonPath("$.status").value("UNSUPPORTED_MEDIA_TYPE"))
                .andExpect(jsonPath("$.clientMessage").value("Unsupported media type"))
                .andExpect(jsonPath("$.statusCode").value(415))
                .andExpect(jsonPath("$.body").isEmpty())
                .andExpect(jsonPath("$.errors").isEmpty());
    }

    @Test
    @WithMockUser
    void testGenericException_Returns500() throws Exception {
        mockMvc.perform(get("/api/test-exception/generic-error"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$", aMapWithSize(5)))
                .andExpect(jsonPath("$.status").value("INTERNAL_SERVER_ERROR"))
                .andExpect(jsonPath("$.clientMessage").value("An unexpected error occurred"))
                .andExpect(jsonPath("$.statusCode").value(500))
                .andExpect(jsonPath("$.body").isEmpty())
                .andExpect(jsonPath("$.errors").isEmpty())
                .andExpect(content().string(not(containsString("Test generic exception"))))
                .andExpect(content().string(not(containsString("RuntimeException"))))
                .andExpect(content().string(not(containsString("java.lang"))))
                .andExpect(content().string(not(containsString("stackTrace"))));
    }

    @Test
    @WithMockUser
    void testConstraintViolation_Returns400() throws Exception {
        mockMvc.perform(get("/api/test-exception/constraint?page=0"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$", aMapWithSize(5)))
                .andExpect(jsonPath("$.status").value("BAD_REQUEST"))
                .andExpect(jsonPath("$.clientMessage").value("Request validation failed"))
                .andExpect(jsonPath("$.statusCode").value(400))
                .andExpect(jsonPath("$.body").isEmpty())
                .andExpect(jsonPath("$.errors").isEmpty());
    }
}
