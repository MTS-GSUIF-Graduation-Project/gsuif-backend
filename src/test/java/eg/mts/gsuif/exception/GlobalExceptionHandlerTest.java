package eg.mts.gsuif.exception;

import eg.mts.gsuif.dto.ApiResponse;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.constraints.Min;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.DefaultMessageSourceResolvable;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.validation.method.ParameterValidationResult;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

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
    void handleMethodArgumentTypeMismatch_returns400WithFieldError() throws NoSuchMethodException {
        MethodParameter parameter = new MethodParameter(
                this.getClass().getDeclaredMethod("pathVariableMethod", UUID.class), 0);
        MethodArgumentTypeMismatchException ex = new MethodArgumentTypeMismatchException(
                "not-a-uuid", UUID.class, "id", parameter, new IllegalArgumentException("Invalid UUID"));

        ResponseEntity<ApiResponse<Void>> response = handler.handleMethodArgumentTypeMismatch(ex);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().statusCode()).isEqualTo(400);
        assertThat(response.getBody().clientMessage()).isEqualTo("Invalid request parameter");
        assertThat(response.getBody().body()).isNull();
        assertThat(response.getBody().errors()).containsEntry("id", "Invalid value");
    }

    @Test
    void handleMissingServletRequestParameter_returns400WithFieldError() {
        MissingServletRequestParameterException ex =
                new MissingServletRequestParameterException("param", "String");

        ResponseEntity<ApiResponse<Void>> response = handler.handleMissingServletRequestParameter(ex);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().statusCode()).isEqualTo(400);
        assertThat(response.getBody().clientMessage()).isEqualTo("Required request parameter is missing");
        assertThat(response.getBody().body()).isNull();
        assertThat(response.getBody().errors()).containsEntry("param", "Parameter is required");
    }

    @Test
    void handleConstraintViolation_returns400WithFieldErrors() {
        Validator validator = Validation.buildDefaultValidatorFactory().getValidator();
        PageRequest target = new PageRequest();
        target.page = 0;
        ConstraintViolationException ex = new ConstraintViolationException(validator.validate(target));

        ResponseEntity<ApiResponse<Void>> response = handler.handleConstraintViolation(ex);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().statusCode()).isEqualTo(400);
        assertThat(response.getBody().clientMessage()).isEqualTo("Request validation failed");
        assertThat(response.getBody().body()).isNull();
        assertThat(response.getBody().errors()).containsEntry("page", "must be greater than or equal to 1");
    }

    @Test
    void handleHandlerMethodValidationException_returns400WithFieldErrors() {
        MethodParameter methodParameter = mock(MethodParameter.class);
        when(methodParameter.getParameterName()).thenReturn("page");
        ParameterValidationResult validationResult = new ParameterValidationResult(
                methodParameter,
                0,
                List.of(new DefaultMessageSourceResolvable(new String[]{"page"}, "must be greater than or equal to 1")),
                null,
                null,
                null,
                null);
        HandlerMethodValidationException ex = mock(HandlerMethodValidationException.class);
        when(ex.getParameterValidationResults()).thenReturn(List.of(validationResult));

        ResponseEntity<ApiResponse<Void>> response = handler.handleHandlerMethodValidationException(ex);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().statusCode()).isEqualTo(400);
        assertThat(response.getBody().clientMessage()).isEqualTo("Request validation failed");
        assertThat(response.getBody().body()).isNull();
        assertThat(response.getBody().errors()).containsEntry("page", "must be greater than or equal to 1");
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

    @SuppressWarnings("unused")
    private void pathVariableMethod(UUID id) {}

    static class PageRequest {
        @Min(1)
        int page;
    }

    static class TestDto {
        private String email;
        public String getEmail() { return email; }
        public void setEmail(String email) { this.email = email; }
    }
}
