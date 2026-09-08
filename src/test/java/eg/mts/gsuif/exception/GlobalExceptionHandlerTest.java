package eg.mts.gsuif.exception;

import eg.mts.gsuif.dto.ApiResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class GlobalExceptionHandlerTest {

    private GlobalExceptionHandler handler;

    @BeforeEach
    void setUp() {
        handler = new GlobalExceptionHandler();
    }

    @Test
    void handleNotFound_returns404() {
        ResourceNotFoundException ex = new ResourceNotFoundException("User not found");

        ResponseEntity<ApiResponse<Void>> response = handler.handleNotFound(ex);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().statusCode()).isEqualTo(404);
        assertThat(response.getBody().status()).isEqualTo("NOT_FOUND");
        assertThat(response.getBody().clientMessage()).isEqualTo("User not found");
        assertThat(response.getBody().body()).isNull();
        assertThat(response.getBody().errors()).isNull();
    }

    @Test
    void handleValidation_returns400WithFieldErrors() throws NoSuchMethodException {
        TestDto target = new TestDto();
        BeanPropertyBindingResult bindingResult = new BeanPropertyBindingResult(target, "testDto");
        bindingResult.addError(new FieldError("testDto", "email", "must not be blank"));

        MethodParameter parameter = new MethodParameter(
                this.getClass().getDeclaredMethod("dummyMethod", String.class), 0);
        MethodArgumentNotValidException ex = new MethodArgumentNotValidException(parameter, bindingResult);

        ResponseEntity<ApiResponse<Void>> response = handler.handleValidation(ex);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().statusCode()).isEqualTo(400);
        assertThat(response.getBody().status()).isEqualTo("BAD_REQUEST");
        assertThat(response.getBody().clientMessage()).isEqualTo("Validation failed");
        assertThat(response.getBody().errors()).isInstanceOf(Map.class);

        Map<String, String> errors = response.getBody().errors();
        assertThat(errors).containsEntry("email", "must not be blank");
    }

    @Test
    void handleGeneral_returns500WithGenericMessage() {
        Exception ex = new RuntimeException("Database connection timeout - secret internal info");

        ResponseEntity<ApiResponse<Void>> response = handler.handleGeneral(ex);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().statusCode()).isEqualTo(500);
        assertThat(response.getBody().status()).isEqualTo("INTERNAL_SERVER_ERROR");
        // STD-12: Never leak internal exception details to client
        assertThat(response.getBody().clientMessage()).isEqualTo("An unexpected error occurred");
        assertThat(response.getBody().errors()).isNull();
    }

    @SuppressWarnings("unused")
    private void dummyMethod(String param) {}

    static class TestDto {
        private String email;
        public String getEmail() { return email; }
        public void setEmail(String email) { this.email = email; }
    }
}
