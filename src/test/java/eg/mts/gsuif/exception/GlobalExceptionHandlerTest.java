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
import org.springframework.validation.method.MethodValidationResult;
import org.springframework.validation.method.ParameterErrors;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.validation.method.ParameterValidationResult;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
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
        when(ex.isForReturnValue()).thenReturn(false);
        when(ex.getCrossParameterValidationResults()).thenReturn(List.of());
        doAnswer(invocation -> {
            HandlerMethodValidationException.Visitor visitor = invocation.getArgument(0);
            visitor.requestParam(null, validationResult);
            return null;
        }).when(ex).visitResults(any());

        ResponseEntity<ApiResponse<Void>> response = handler.handleHandlerMethodValidationException(ex);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().statusCode()).isEqualTo(400);
        assertThat(response.getBody().clientMessage()).isEqualTo("Request validation failed");
        assertThat(response.getBody().body()).isNull();
        assertThat(response.getBody().errors()).containsEntry("page", "must be greater than or equal to 1");
    }

    @Test
    void handleHandlerMethodValidationException_whenReturnValueValidation_returnsSanitized500() {
        HandlerMethodValidationException ex = mock(HandlerMethodValidationException.class);
        when(ex.isForReturnValue()).thenReturn(true);

        ResponseEntity<ApiResponse<Void>> response = handler.handleHandlerMethodValidationException(ex);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().statusCode()).isEqualTo(500);
        assertThat(response.getBody().status()).isEqualTo("INTERNAL_SERVER_ERROR");
        assertThat(response.getBody().clientMessage()).isEqualTo("An unexpected error occurred");
        assertThat(response.getBody().body()).isNull();
        assertThat(response.getBody().errors()).isNull();
    }

    @Test
    void handleHandlerMethodValidationException_whenCrossParameterValidation_returnsGlobalError() throws NoSuchMethodException {
        Method method = this.getClass().getDeclaredMethod("crossParameterMethod", String.class, String.class);
        MethodValidationResult validationResult = MethodValidationResult.create(
                this,
                method,
                List.of(),
                List.of(new DefaultMessageSourceResolvable(new String[] {}, "At least one parameter must be present")));
        HandlerMethodValidationException ex = new HandlerMethodValidationException(validationResult);

        ResponseEntity<ApiResponse<Void>> response = handler.handleHandlerMethodValidationException(ex);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().errors()).containsEntry("_global", "At least one parameter must be present");
    }

    @Test
    void handleHandlerMethodValidationException_whenNestedDtoFields_returnsPrefixedFieldErrors() throws NoSuchMethodException {
        Method method = this.getClass().getDeclaredMethod("nestedBodyMethod", GlobalExceptionHandlerIntegrationTest.NestedRequest.class);
        MethodParameter methodParameter = new MethodParameter(method, 0);
        BeanPropertyBindingResult bindingResult =
                new BeanPropertyBindingResult(new GlobalExceptionHandlerIntegrationTest.NestedRequest(), "nested");
        bindingResult.addError(new FieldError("nested", "orderNumber", "must not be null"));
        bindingResult.addError(new FieldError("nested", "dueDate", "must not be null"));
        ParameterErrors parameterErrors = new ParameterErrors(
                methodParameter,
                bindingResult.getTarget(),
                bindingResult,
                null,
                null,
                null);
        MethodValidationResult validationResult = MethodValidationResult.create(this, method, List.of(parameterErrors));
        HandlerMethodValidationException ex = new HandlerMethodValidationException(validationResult);

        ResponseEntity<ApiResponse<Void>> response = handler.handleHandlerMethodValidationException(ex);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().errors()).containsEntry("orderNumber", "must not be null");
        assertThat(response.getBody().errors()).containsEntry("dueDate", "must not be null");
    }

    @Test
    void handleHandlerMethodValidationException_whenReturnValueValidation_usesRealSpringException() throws NoSuchMethodException {
        Method method = this.getClass().getDeclaredMethod("returnNestedMethod");
        MethodParameter returnParam = new MethodParameter(method, -1);
        BeanPropertyBindingResult bindingResult =
                new BeanPropertyBindingResult(new GlobalExceptionHandlerIntegrationTest.NestedRequest(), "nested");
        bindingResult.addError(new FieldError("nested", "orderNumber", "must not be null"));
        ParameterErrors parameterErrors = new ParameterErrors(
                returnParam,
                bindingResult.getTarget(),
                bindingResult,
                null,
                null,
                null);
        MethodValidationResult validationResult = new ReturnValueMethodValidationResult(this, method, List.of(parameterErrors));
        HandlerMethodValidationException ex = new HandlerMethodValidationException(validationResult);

        ResponseEntity<ApiResponse<Void>> response = handler.handleHandlerMethodValidationException(ex);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().clientMessage()).isEqualTo("An unexpected error occurred");
        assertThat(response.getBody().errors()).isNull();
    }

    private static final class ReturnValueMethodValidationResult implements MethodValidationResult {
        private final Object target;
        private final Method method;
        private final List<ParameterValidationResult> parameterResults;

        private ReturnValueMethodValidationResult(Object target, Method method, List<ParameterValidationResult> parameterResults) {
            this.target = target;
            this.method = method;
            this.parameterResults = parameterResults;
        }

        @Override
        public Object getTarget() {
            return target;
        }

        @Override
        public Method getMethod() {
            return method;
        }

        @Override
        public boolean isForReturnValue() {
            return true;
        }

        @Override
        public List<ParameterValidationResult> getParameterValidationResults() {
            return parameterResults;
        }

        @Override
        public List<org.springframework.context.MessageSourceResolvable> getCrossParameterValidationResults() {
            return List.of();
        }
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
        assertThat(response.getBody().clientMessage()).isEqualTo("Malformed request body");
        assertThat(response.getBody().body()).isNull();
        assertThat(response.getBody().errors()).isNull();
    }

    @Test
    void handleMethodArgumentTypeMismatch_returns400WithoutMethodParameter() {
        MethodArgumentTypeMismatchException ex = new MethodArgumentTypeMismatchException(
                "invalid-uuid", UUID.class, "id", null, new IllegalArgumentException("Invalid UUID"));

        ResponseEntity<ApiResponse<Void>> response = handler.handleMethodArgumentTypeMismatch(ex);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().statusCode()).isEqualTo(400);
        assertThat(response.getBody().status()).isEqualTo("BAD_REQUEST");
        assertThat(response.getBody().clientMessage()).isEqualTo("Invalid request parameter");
        assertThat(response.getBody().body()).isNull();
        assertThat(response.getBody().errors()).containsEntry("id", "Invalid value");
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

        ResponseEntity<ApiResponse<Void>> response = handler.handleHttpRequestMethodNotSupported(ex);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.METHOD_NOT_ALLOWED);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().statusCode()).isEqualTo(405);
        assertThat(response.getBody().status()).isEqualTo("METHOD_NOT_ALLOWED");
        assertThat(response.getBody().clientMessage()).isEqualTo("HTTP method not supported");
        assertThat(response.getBody().body()).isNull();
        assertThat(response.getBody().errors()).isNull();
    }

    @Test
    void handleMediaTypeNotSupported_returns415() {
        org.springframework.web.HttpMediaTypeNotSupportedException ex =
                new org.springframework.web.HttpMediaTypeNotSupportedException("application/xml");

        ResponseEntity<ApiResponse<Void>> response = handler.handleHttpMediaTypeNotSupported(ex);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNSUPPORTED_MEDIA_TYPE);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().statusCode()).isEqualTo(415);
        assertThat(response.getBody().status()).isEqualTo("UNSUPPORTED_MEDIA_TYPE");
        assertThat(response.getBody().clientMessage()).isEqualTo("Unsupported media type");
        assertThat(response.getBody().body()).isNull();
        assertThat(response.getBody().errors()).isNull();
    }


    @SuppressWarnings("unused")
    private void dummyMethod(String param) {}

    @SuppressWarnings("unused")
    private void pathVariableMethod(UUID id) {}

    @SuppressWarnings("unused")
    private void nestedBodyMethod(GlobalExceptionHandlerIntegrationTest.NestedRequest nested) {}

    @SuppressWarnings("unused")
    private GlobalExceptionHandlerIntegrationTest.NestedRequest returnNestedMethod() {
        return new GlobalExceptionHandlerIntegrationTest.NestedRequest();
    }

    @SuppressWarnings("unused")
    private void crossParameterMethod(String first, String second) {}

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
