package eg.mts.gsuif.exception;

import eg.mts.gsuif.dto.ApiResponse;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.MessageSourceResolvable;
import org.springframework.core.MethodParameter;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.core.PropertyReferenceException;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.lang.Nullable;
import org.springframework.validation.FieldError;
import org.springframework.validation.ObjectError;
import org.springframework.validation.method.ParameterErrors;
import org.springframework.validation.method.ParameterValidationResult;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.MatrixVariable;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /** Reserved errors-map key for object-level and cross-parameter validation messages (STD-03). */
    private static final String GLOBAL_ERROR_KEY = "_global";

    private Map<String, String> createSingleErrorMap(String key, String message) {
        Map<String, String> errors = new LinkedHashMap<>();
        errors.put(key, message);
        return errors;
    }

    // Branch 1: validation errors → 400
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Void>> handleValidation(MethodArgumentNotValidException ex) {
        log.error("Validation error: {}", ex.getMessage(), ex);
        Map<String, String> errors = new HashMap<>();
        for (FieldError fieldError : ex.getBindingResult().getFieldErrors()) {
            errors.put(fieldError.getField(), fieldError.getDefaultMessage());
        }
        return ResponseEntity
                .badRequest()
                .body(ApiResponse.error(400, "Validation failed", errors));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiResponse<Void>> handleHttpMessageNotReadable(HttpMessageNotReadableException ex) {
        log.error("Malformed request body: {}", ex.getMessage(), ex);
        return ResponseEntity
                .badRequest()
                .body(ApiResponse.error(400, "Malformed request body", null));
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiResponse<Void>> handleMethodArgumentTypeMismatch(MethodArgumentTypeMismatchException ex) {
        log.error("Invalid request parameter: {}", ex.getMessage(), ex);
        return ResponseEntity
                .badRequest()
                .body(ApiResponse.error(400, "Invalid request parameter", createSingleErrorMap(ex.getName(), "Invalid value")));
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<ApiResponse<Void>> handleMissingServletRequestParameter(MissingServletRequestParameterException ex) {
        log.error("Required request parameter is missing: {}", ex.getMessage(), ex);
        return ResponseEntity
                .badRequest()
                .body(ApiResponse.error(400, "Required request parameter is missing", createSingleErrorMap(ex.getParameterName(), "Parameter is required")));
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ApiResponse<Void>> handleConstraintViolation(ConstraintViolationException ex) {
        log.error("Constraint violation: {}", ex.getMessage(), ex);
        Map<String, String> errors = new LinkedHashMap<>();
        for (var violation : ex.getConstraintViolations()) {
            String field = "unknown";
            for (var node : violation.getPropertyPath()) {
                field = node.getName();
            }
            errors.putIfAbsent(field, violation.getMessage());
        }
        return ResponseEntity
                .badRequest()
                .body(ApiResponse.error(400, "Request validation failed", errors));
    }

    @ExceptionHandler(HandlerMethodValidationException.class)
    public ResponseEntity<ApiResponse<Void>> handleHandlerMethodValidationException(HandlerMethodValidationException ex) {
        log.error("Method validation failed: {}", ex.getMessage(), ex);

        if (ex.isForReturnValue()) {
            return ResponseEntity
                    .internalServerError()
                    .body(ApiResponse.error(500, "An unexpected error occurred", null));
        }

        Map<String, String> errors = new LinkedHashMap<>();
        collectMethodValidationErrors(ex, errors);
        return ResponseEntity
                .badRequest()
                .body(ApiResponse.error(400, "Request validation failed", errors));
    }

    private void collectMethodValidationErrors(HandlerMethodValidationException ex, Map<String, String> errors) {
        ex.visitResults(new HandlerMethodValidationException.Visitor() {
            @Override
            public void cookieValue(@Nullable CookieValue cookieValue, ParameterValidationResult result) {
                addSimpleParameterResult(result, errors);
            }

            @Override
            public void matrixVariable(@Nullable MatrixVariable matrixVariable, ParameterValidationResult result) {
                addSimpleParameterResult(result, errors);
            }

            @Override
            public void modelAttribute(@Nullable ModelAttribute modelAttribute, ParameterErrors paramErrors) {
                addParameterErrors(paramErrors, errors);
            }

            @Override
            public void pathVariable(@Nullable PathVariable pathVariable, ParameterValidationResult result) {
                addSimpleParameterResult(result, errors);
            }

            @Override
            public void requestBody(@Nullable RequestBody requestBody, ParameterErrors paramErrors) {
                addParameterErrors(paramErrors, errors);
            }

            @Override
            public void requestHeader(@Nullable RequestHeader requestHeader, ParameterValidationResult result) {
                addSimpleParameterResult(result, errors);
            }

            @Override
            public void requestParam(@Nullable RequestParam requestParam, ParameterValidationResult result) {
                addSimpleParameterResult(result, errors);
            }

            @Override
            public void requestPart(@Nullable RequestPart requestPart, ParameterErrors paramErrors) {
                addParameterErrors(paramErrors, errors);
            }

            @Override
            public void other(ParameterValidationResult result) {
                addSimpleParameterResult(result, errors);
            }
        });

        for (MessageSourceResolvable crossError : ex.getCrossParameterValidationResults()) {
            putValidationMessage(errors, GLOBAL_ERROR_KEY, crossError.getDefaultMessage());
        }
    }

    private void addSimpleParameterResult(ParameterValidationResult result, Map<String, String> errors) {
        if (result instanceof ParameterErrors paramErrors) {
            addParameterErrors(paramErrors, errors);
            return;
        }
        String key = parameterName(result.getMethodParameter());
        for (MessageSourceResolvable error : result.getResolvableErrors()) {
            putValidationMessage(errors, key, error.getDefaultMessage());
        }
    }

    private void addParameterErrors(ParameterErrors paramErrors, Map<String, String> errors) {
        for (FieldError fieldError : paramErrors.getFieldErrors()) {
            putValidationMessage(errors, fieldError.getField(), fieldError.getDefaultMessage());
        }
        for (ObjectError globalError : paramErrors.getGlobalErrors()) {
            putValidationMessage(errors, GLOBAL_ERROR_KEY, globalError.getDefaultMessage());
        }
    }

    private String parameterName(MethodParameter methodParameter) {
        String name = methodParameter.getParameterName();
        return name != null ? name : "unknown";
    }

    private void putValidationMessage(Map<String, String> errors, String key, @Nullable String message) {
        if (message != null) {
            errors.putIfAbsent(key, message);
        }
    }

    /** Invalid pagination sort property → HTTP 400. */
    @ExceptionHandler(PropertyReferenceException.class)
    public ResponseEntity<ApiResponse<Void>> handlePropertyReferenceException(PropertyReferenceException ex) {
        log.error("Invalid property reference: {}", ex.getMessage(), ex);
        return ResponseEntity
                .badRequest()
                .body(ApiResponse.error(400, "Invalid property reference in sorting", null));
    }

    /** Application-level duplicate business key → HTTP 400. */
    @ExceptionHandler(DuplicateResourceException.class)
    public ResponseEntity<ApiResponse<Void>> handleDuplicateResource(DuplicateResourceException ex) {
        log.error("Duplicate resource: {}", ex.getMessage(), ex);
        return ResponseEntity
                .badRequest()
                .body(ApiResponse.error(400, ex.getMessage(), null));
    }

    /**
     * Race-condition fallback when a unique DB constraint is hit despite the service pre-check.
     * Only unique violations map to HTTP 400; other integrity failures fall through to HTTP 500.
     */
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ApiResponse<Void>> handleDataIntegrityViolation(DataIntegrityViolationException ex) {
        log.error("Data integrity violation: {}", ex.getMessage(), ex);

        if (isUniqueConstraintViolation(ex)) {
            return ResponseEntity
                    .badRequest()
                    .body(ApiResponse.error(400, "Resource already exists or violates unique constraint", null));
        }

        return handleGeneral(ex);
    }

    private boolean isUniqueConstraintViolation(DataIntegrityViolationException ex) {
        Throwable cause = ex;
        while (cause != null) {
            if (cause instanceof java.sql.SQLException sqlEx) {
                // SQLState 23505 (unique_violation): used by PostgreSQL and H2 in PostgreSQL mode (STD-34).
                if ("23505".equals(sqlEx.getSQLState())) {
                    return true;
                }
            }
            if (cause instanceof org.hibernate.exception.ConstraintViolationException cve) {
                if ("23505".equals(cve.getSQLState())) {
                    return true;
                }
            }
            cause = cause.getCause();
        }
        return false;
    }

    // Branch 2: resource not found → 404
    @ExceptionHandler(ResourceNotFoundException.class)
    public ResponseEntity<ApiResponse<Void>> handleNotFound(ResourceNotFoundException ex) {
        log.error("Resource not found: {}", ex.getMessage(), ex);
        return ResponseEntity
                .status(404)
                .body(ApiResponse.error(404, ex.getMessage(), null));
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ApiResponse<Void>> handleNoResourceFound(NoResourceFoundException ex) {
        log.error("Resource not found (NoResourceFoundException): {}", ex.getMessage(), ex);
        return ResponseEntity
                .status(404)
                .body(ApiResponse.error(404, "Resource not found", null));
    }

    // HTTP method / media type errors
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ApiResponse<Void>> handleHttpRequestMethodNotSupported(HttpRequestMethodNotSupportedException ex) {
        log.error("HTTP method not supported: {}", ex.getMessage(), ex);
        return ResponseEntity
                .status(405)
                .body(ApiResponse.error(405, "HTTP method not supported", null));
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<ApiResponse<Void>> handleHttpMediaTypeNotSupported(HttpMediaTypeNotSupportedException ex) {
        log.error("Unsupported media type: {}", ex.getMessage(), ex);
        return ResponseEntity
                .status(415)
                .body(ApiResponse.error(415, "Unsupported media type", null));
    }

    // Branch 5: catch-all → 500
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleGeneral(Exception ex) {
        log.error("Unexpected error", ex);
        return ResponseEntity
                .internalServerError()
                .body(ApiResponse.error(500, "An unexpected error occurred", null));
    }
}
