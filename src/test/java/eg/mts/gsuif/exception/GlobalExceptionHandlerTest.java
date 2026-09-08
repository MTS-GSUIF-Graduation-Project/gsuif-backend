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
import java.util.UUID;

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

    @Test
    void handleDuplicateResource_returns400() {
        DuplicateResourceException ex = new DuplicateResourceException("Work order already exists");

        ResponseEntity<ApiResponse<Void>> response = handler.handleDuplicateResource(ex);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().statusCode()).isEqualTo(400);
        assertThat(response.getBody().status()).isEqualTo("BAD_REQUEST");
        assertThat(response.getBody().clientMessage()).isEqualTo("Work order already exists");
        assertThat(response.getBody().body()).isNull();
        assertThat(response.getBody().errors()).isNull();
    }

    @Test
    void handleHttpMessageNotReadable_returns400() {
        org.springframework.http.converter.HttpMessageNotReadableException ex =
                new org.springframework.http.converter.HttpMessageNotReadableException("JSON parse error", (org.springframework.http.HttpInputMessage) null);

        ResponseEntity<ApiResponse<Void>> response = handler.handleHttpMessageNotReadable(ex);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().statusCode()).isEqualTo(400);
        assertThat(response.getBody().status()).isEqualTo("BAD_REQUEST");
        assertThat(response.getBody().clientMessage()).isEqualTo("Malformed JSON or invalid request payload");
        assertThat(response.getBody().body()).isNull();
        assertThat(response.getBody().errors()).isNull();
    }

    @Test
    void handleMethodArgumentTypeMismatch_returns400() {
        org.springframework.web.method.annotation.MethodArgumentTypeMismatchException ex =
                new org.springframework.web.method.annotation.MethodArgumentTypeMismatchException(
                        "invalid-uuid", UUID.class, "id", null, new IllegalArgumentException("Invalid UUID")
                );

        ResponseEntity<ApiResponse<Void>> response = handler.handleMethodArgumentTypeMismatch(ex);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().statusCode()).isEqualTo(400);
        assertThat(response.getBody().status()).isEqualTo("BAD_REQUEST");
        assertThat(response.getBody().clientMessage()).contains("Invalid value 'invalid-uuid' for parameter 'id'");
        assertThat(response.getBody().body()).isNull();
        assertThat(response.getBody().errors()).isNull();
    }

    @Test
    void handlePropertyReferenceException_returns400() {
        org.springframework.data.core.PropertyReferenceException ex =
                new org.springframework.data.core.PropertyReferenceException("notAField",
                        org.springframework.data.core.TypeInformation.of(Object.class),
                        java.util.Collections.emptyList());

        ResponseEntity<ApiResponse<Void>> response = handler.handlePropertyReferenceException(ex);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().statusCode()).isEqualTo(400);
        assertThat(response.getBody().status()).isEqualTo("BAD_REQUEST");
        assertThat(response.getBody().clientMessage()).isEqualTo("Invalid property reference in sorting");
        assertThat(response.getBody().body()).isNull();
        assertThat(response.getBody().errors()).isNull();
    }

    @Test
    void handleDataIntegrityViolation_whenUniqueConstraintSqlState23505_returns400() {
        java.sql.SQLException sqlEx = new java.sql.SQLException("duplicate key", "23505", 23505);
        org.springframework.dao.DataIntegrityViolationException ex =
                new org.springframework.dao.DataIntegrityViolationException("Unique index or primary key violation", sqlEx);

        ResponseEntity<ApiResponse<Void>> response = handler.handleDataIntegrityViolation(ex);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().statusCode()).isEqualTo(400);
        assertThat(response.getBody().status()).isEqualTo("BAD_REQUEST");
        assertThat(response.getBody().clientMessage()).isEqualTo("Resource already exists or violates unique constraint");
        assertThat(response.getBody().body()).isNull();
        assertThat(response.getBody().errors()).isNull();
    }

    @Test
    void handleDataIntegrityViolation_whenForeignKeyViolation_returns500() {
        java.sql.SQLException sqlEx = new java.sql.SQLException("foreign key violation", "23503", 23503);
        org.springframework.dao.DataIntegrityViolationException ex =
                new org.springframework.dao.DataIntegrityViolationException("violates foreign key constraint", sqlEx);

        ResponseEntity<ApiResponse<Void>> response = handler.handleDataIntegrityViolation(ex);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().statusCode()).isEqualTo(500);
        assertThat(response.getBody().status()).isEqualTo("INTERNAL_SERVER_ERROR");
        assertThat(response.getBody().clientMessage()).isEqualTo("An unexpected error occurred");
        assertThat(response.getBody().body()).isNull();
        assertThat(response.getBody().errors()).isNull();
    }

    @Test
    void handleDataIntegrityViolation_whenCheckConstraint_returns500() {
        org.springframework.dao.DataIntegrityViolationException ex =
                new org.springframework.dao.DataIntegrityViolationException(
                        "check constraint check_status_valid violated"
                );

        ResponseEntity<ApiResponse<Void>> response = handler.handleDataIntegrityViolation(ex);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().statusCode()).isEqualTo(500);
        assertThat(response.getBody().status()).isEqualTo("INTERNAL_SERVER_ERROR");
        assertThat(response.getBody().clientMessage()).isEqualTo("An unexpected error occurred");
        assertThat(response.getBody().body()).isNull();
        assertThat(response.getBody().errors()).isNull();
    }

    @Test
    void handleDataIntegrityViolation_whenNotNullViolationOnOrderNumber_returns500() {
        // Merely mentioning order_number in a non-unique integrity failure must NOT become 400
        org.springframework.dao.DataIntegrityViolationException ex =
                new org.springframework.dao.DataIntegrityViolationException(
                        "null value in column \"order_number\" of relation \"work_orders\" violates not-null constraint"
                );

        ResponseEntity<ApiResponse<Void>> response = handler.handleDataIntegrityViolation(ex);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().statusCode()).isEqualTo(500);
        assertThat(response.getBody().status()).isEqualTo("INTERNAL_SERVER_ERROR");
        assertThat(response.getBody().clientMessage()).isEqualTo("An unexpected error occurred");
        assertThat(response.getBody().body()).isNull();
        assertThat(response.getBody().errors()).isNull();
    }

    @Test
    void handleMethodNotSupported_returns405() {
        org.springframework.web.HttpRequestMethodNotSupportedException ex =
                new org.springframework.web.HttpRequestMethodNotSupportedException("POST");

        ResponseEntity<ApiResponse<Void>> response = handler.handleMethodNotSupported(ex);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.METHOD_NOT_ALLOWED);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().statusCode()).isEqualTo(405);
        assertThat(response.getBody().status()).isEqualTo("METHOD_NOT_ALLOWED");
        assertThat(response.getBody().clientMessage()).isEqualTo("Method Not Allowed");
        assertThat(response.getBody().body()).isNull();
        assertThat(response.getBody().errors()).isNull();
    }

    @Test
    void handleMediaTypeNotSupported_returns415() {
        org.springframework.web.HttpMediaTypeNotSupportedException ex =
                new org.springframework.web.HttpMediaTypeNotSupportedException("application/xml");

        ResponseEntity<ApiResponse<Void>> response = handler.handleMediaTypeNotSupported(ex);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNSUPPORTED_MEDIA_TYPE);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().statusCode()).isEqualTo(415);
        assertThat(response.getBody().status()).isEqualTo("UNSUPPORTED_MEDIA_TYPE");
        assertThat(response.getBody().clientMessage()).isEqualTo("Unsupported Media Type");
        assertThat(response.getBody().body()).isNull();
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
