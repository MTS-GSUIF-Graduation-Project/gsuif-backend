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

    // Branch 2: resource not found → 404
    @ExceptionHandler(ResourceNotFoundException.class)
    public ResponseEntity<ApiResponse<Void>> handleNotFound(ResourceNotFoundException ex) {
        log.error("Resource not found: {}", ex.getMessage(), ex);
        return ResponseEntity
                .status(404)
                .body(ApiResponse.error(404, ex.getMessage(), null));
    }
    // Branch 3: malformed JSON or unreadable payload → 400
    @ExceptionHandler(org.springframework.http.converter.HttpMessageNotReadableException.class)
    public ResponseEntity<ApiResponse<Void>> handleHttpMessageNotReadable(org.springframework.http.converter.HttpMessageNotReadableException ex) {
        log.error("Malformed request payload: {}", ex.getMessage(), ex);
        return ResponseEntity
                .badRequest()
                .body(ApiResponse.error(400, "Malformed JSON or invalid request payload", null));
    }

    // Branch 4: type mismatch in path/query parameters (e.g. invalid UUID) → 400
    @ExceptionHandler(org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiResponse<Void>> handleMethodArgumentTypeMismatch(org.springframework.web.method.annotation.MethodArgumentTypeMismatchException ex) {
        log.error("Parameter type mismatch: {}", ex.getMessage(), ex);
        String message = String.format("Invalid value '%s' for parameter '%s'", ex.getValue(), ex.getName());
        return ResponseEntity
                .badRequest()
                .body(ApiResponse.error(400, message, null));
    }

    // Branch 5: duplicate resource → 400 (per STD-28 allowed status codes)
    @ExceptionHandler(DuplicateResourceException.class)
    public ResponseEntity<ApiResponse<Void>> handleDuplicateResource(DuplicateResourceException ex) {
        log.error("Duplicate resource: {}", ex.getMessage(), ex);
        return ResponseEntity
                .badRequest()
                .body(ApiResponse.error(400, ex.getMessage(), null));
    }

    // Branch 6: database constraint violations (only unique violations map to 400; unexpected integrity errors fall to 500)
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
                // 23505 is the ANSI SQL / PostgreSQL / H2 standard SQLState for unique_violation
                if ("23505".equals(sqlEx.getSQLState())) {
                    return true;
                }
            }
            if (cause instanceof org.hibernate.exception.ConstraintViolationException cve) {
                if ("23505".equals(cve.getSQLState())) {
                    return true;
                }
                String constraint = cve.getConstraintName();
                if (constraint != null && (constraint.toLowerCase().contains("unique") || constraint.toLowerCase().contains("uk_"))) {
                    return true;
                }
            }
            String msg = cause.getMessage();
            if (msg != null) {
                String lower = msg.toLowerCase();
                if (lower.contains("duplicate key")
                        || lower.contains("unique index")
                        || (lower.contains("unique") && (lower.contains("constraint") || lower.contains("index") || lower.contains("violation")))
                        || lower.contains("uk_")) {
                    return true;
                }
            }
            cause = cause.getCause();
        }
        return false;
    }

    // Branch 7: unsupported HTTP method -> 405
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ApiResponse<Void>> handleMethodNotSupported(HttpRequestMethodNotSupportedException ex) {
        log.error("Method not supported: {}", ex.getMessage(), ex);
        return ResponseEntity
                .status(405)
                .body(ApiResponse.error(405, "Method Not Allowed", null));
    }

    // Branch 8: unsupported media type -> 415
    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<ApiResponse<Void>> handleMediaTypeNotSupported(HttpMediaTypeNotSupportedException ex) {
        log.error("Media type not supported: {}", ex.getMessage(), ex);
        return ResponseEntity
                .status(415)
                .body(ApiResponse.error(415, "Unsupported Media Type", null));
    }

    // Branch 9: catch-all → 500
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleGeneral(Exception ex) {
        log.error("Unexpected error", ex);
        return ResponseEntity
                .internalServerError()
                .body(ApiResponse.error(500, "An unexpected error occurred", null));
    }
}
