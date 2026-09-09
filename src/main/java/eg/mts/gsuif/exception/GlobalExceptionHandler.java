package eg.mts.gsuif.exception;

import eg.mts.gsuif.dto.ApiResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.context.MessageSourceResolvable;
import org.springframework.web.method.annotation.HandlerMethodValidationException;

import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import jakarta.validation.ConstraintViolationException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

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
        Map<String, String> errors = new LinkedHashMap<>();
        for (var result : ex.getParameterValidationResults()) {
            String paramName = result.getMethodParameter().getParameterName();
            if (paramName == null) {
                paramName = "unknown";
            }
            for (MessageSourceResolvable error : result.getResolvableErrors()) {
                errors.putIfAbsent(paramName, error.getDefaultMessage());
            }
        }
        return ResponseEntity
                .badRequest()
                .body(ApiResponse.error(400, "Request validation failed", errors));
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
