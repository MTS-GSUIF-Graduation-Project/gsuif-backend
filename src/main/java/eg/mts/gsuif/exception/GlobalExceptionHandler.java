package eg.mts.gsuif.exception;

import eg.mts.gsuif.dto.ApiResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.HashMap;
import java.util.Map;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /** Bean Validation failures → HTTP 400 with per-field errors. */
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

    /** Missing resource → HTTP 404. */
    @ExceptionHandler(ResourceNotFoundException.class)
    public ResponseEntity<ApiResponse<Void>> handleNotFound(ResourceNotFoundException ex) {
        log.error("Resource not found: {}", ex.getMessage(), ex);
        return ResponseEntity
                .status(404)
                .body(ApiResponse.error(404, ex.getMessage(), null));
    }

    /** Malformed JSON or unreadable request body → HTTP 400. */
    @ExceptionHandler(org.springframework.http.converter.HttpMessageNotReadableException.class)
    public ResponseEntity<ApiResponse<Void>> handleHttpMessageNotReadable(org.springframework.http.converter.HttpMessageNotReadableException ex) {
        log.error("Malformed request payload: {}", ex.getMessage(), ex);
        return ResponseEntity
                .badRequest()
                .body(ApiResponse.error(400, "Malformed JSON or invalid request payload", null));
    }

    /** Invalid path or query parameter type (e.g. malformed UUID) → HTTP 400. */
    @ExceptionHandler(org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiResponse<Void>> handleMethodArgumentTypeMismatch(org.springframework.web.method.annotation.MethodArgumentTypeMismatchException ex) {
        log.error("Parameter type mismatch: {}", ex.getMessage(), ex);
        String message = String.format("Invalid value '%s' for parameter '%s'", ex.getValue(), ex.getName());
        return ResponseEntity
                .badRequest()
                .body(ApiResponse.error(400, message, null));
    }

    /** Invalid pagination sort property → HTTP 400. */
    @ExceptionHandler(org.springframework.data.core.PropertyReferenceException.class)
    public ResponseEntity<ApiResponse<Void>> handlePropertyReferenceException(org.springframework.data.core.PropertyReferenceException ex) {
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
    @ExceptionHandler(org.springframework.dao.DataIntegrityViolationException.class)
    public ResponseEntity<ApiResponse<Void>> handleDataIntegrityViolation(org.springframework.dao.DataIntegrityViolationException ex) {
        log.error("Data integrity violation: {}", ex.getMessage(), ex);

        if (isUniqueConstraintViolation(ex)) {
            return ResponseEntity
                    .badRequest()
                    .body(ApiResponse.error(400, "Resource already exists or violates unique constraint", null));
        }

        return handleGeneral(ex);
    }

    private boolean isUniqueConstraintViolation(org.springframework.dao.DataIntegrityViolationException ex) {
        Throwable cause = ex;
        while (cause != null) {
            if (cause instanceof java.sql.SQLException sqlEx) {
                // SQLState 23505 (unique_violation): used by PostgreSQL and H2 in PostgreSQL mode (STD-34).
                // Not guaranteed by every JDBC driver or relational database.
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

    /** Unsupported HTTP method → HTTP 405. */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ApiResponse<Void>> handleMethodNotSupported(HttpRequestMethodNotSupportedException ex) {
        log.error("Method not supported: {}", ex.getMessage(), ex);
        return ResponseEntity
                .status(405)
                .body(ApiResponse.error(405, "Method Not Allowed", null));
    }

    /** Unsupported request media type → HTTP 415. */
    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<ApiResponse<Void>> handleMediaTypeNotSupported(HttpMediaTypeNotSupportedException ex) {
        log.error("Media type not supported: {}", ex.getMessage(), ex);
        return ResponseEntity
                .status(415)
                .body(ApiResponse.error(415, "Unsupported Media Type", null));
    }

    /** Unhandled exceptions → HTTP 500 with a generic client message. */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleGeneral(Exception ex) {
        log.error("Unexpected error", ex);
        return ResponseEntity
                .internalServerError()
                .body(ApiResponse.error(500, "An unexpected error occurred", null));
    }
}
