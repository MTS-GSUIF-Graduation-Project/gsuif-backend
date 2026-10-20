<#ftl strip_whitespace=true encoding="UTF-8">
package ${packageName}.exception;

import ${packageName}.api.ApiResponse;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.http.converter.HttpMessageNotReadableException;

@RestControllerAdvice
public final class GlobalExceptionHandler {
    private static final int MAX_FIELD_ERRORS = 16;
    private static final Set<String> FIELD_KEYS = Set.of(<#list fieldKeys as key>"${key}"<#if key_has_next>, </#if></#list>);

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Void>> invalidFields(MethodArgumentNotValidException exception) {
        ${packageName}.logging.SafeFailureLogger.logHandled(GlobalExceptionHandler.class, exception);
        Map<String, String> errors = new TreeMap<>();
        exception.getBindingResult().getFieldErrors().stream().map(error -> error.getField())
                .filter(FIELD_KEYS::contains).distinct().sorted().limit(MAX_FIELD_ERRORS)
                .forEach(field -> errors.put(field, "Invalid value"));
        if (errors.isEmpty()) errors.put("_global", "Invalid request");
        return ResponseEntity.status(400).body(ApiResponse.error(400, "Invalid request", errors));
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<ApiResponse<Void>> missingParameter(MissingServletRequestParameterException exception) {
        ${packageName}.logging.SafeFailureLogger.logHandled(GlobalExceptionHandler.class, exception);
        String key = FIELD_KEYS.contains(exception.getParameterName()) ? exception.getParameterName() : "_global";
        return ResponseEntity.status(400).body(ApiResponse.error(400, "Invalid request",
                Map.of(key, "Required parameter is missing")));
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiResponse<Void>> invalidParameter(MethodArgumentTypeMismatchException exception) {
        ${packageName}.logging.SafeFailureLogger.logHandled(GlobalExceptionHandler.class, exception);
        String key = FIELD_KEYS.contains(exception.getName()) ? exception.getName() : "_global";
        return ResponseEntity.status(400).body(ApiResponse.error(400, "Invalid request",
                Map.of(key, "Invalid value")));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiResponse<Void>> unreadableBody(HttpMessageNotReadableException exception) {
        ${packageName}.logging.SafeFailureLogger.logHandled(GlobalExceptionHandler.class, exception);
        String key = bodyFieldKey(exception);
        return ResponseEntity.status(400).body(ApiResponse.error(400, "Invalid request",
                Map.of(key, key.equals("_global") ? "Invalid request body" : "Invalid value")));
    }

    /**
     * Names the contract field a body failure belongs to (wrong JSON type, value outside an enum,
     * duplicate member or missing required member) without echoing the rejected value. Only declared
     * field names are returned; anything else stays {@code _global}.
     */
    private static String bodyFieldKey(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (!(cause instanceof tools.jackson.core.JacksonException jackson)) continue;
            for (var reference : jackson.getPath()) {
                String name = reference.getPropertyName();
                if (name != null && FIELD_KEYS.contains(name)) return name;
            }
            String message = jackson.getOriginalMessage();
            if (message != null && message.startsWith("Missing required creator property '")) {
                int end = message.indexOf('\'', "Missing required creator property '".length());
                String name = end < 0 ? null : message.substring("Missing required creator property '".length(), end);
                if (name != null && FIELD_KEYS.contains(name)) return name;
            }
            if (cause instanceof tools.jackson.core.exc.StreamReadException
                    && jackson.processor() instanceof tools.jackson.core.JsonParser parser) {
                String name = parser.currentName();
                if (name != null && FIELD_KEYS.contains(name)) return name;
            }
        }
        return "_global";
    }

    @ExceptionHandler(RequestedRecordNotFoundException.class)
    public ResponseEntity<ApiResponse<Void>> missingRecord(RequestedRecordNotFoundException exception) {
        ${packageName}.logging.SafeFailureLogger.logHandled(GlobalExceptionHandler.class, exception);
        return ResponseEntity.status(404).body(ApiResponse.error(404, "Resource not found",
                Map.of("_global", "Resource not found")));
    }

    @ExceptionHandler(org.springframework.security.core.AuthenticationException.class)
    public ResponseEntity<ApiResponse<Void>> unauthenticated(
            org.springframework.security.core.AuthenticationException exception) {
        ${packageName}.logging.SafeFailureLogger.logHandled(GlobalExceptionHandler.class, exception);
        return ResponseEntity.status(401).body(ApiResponse.error(401, "Authentication required",
                Map.of("_global", "Authentication required")));
    }

    @ExceptionHandler(org.springframework.security.access.AccessDeniedException.class)
    public ResponseEntity<ApiResponse<Void>> denied(
            org.springframework.security.access.AccessDeniedException exception) {
        ${packageName}.logging.SafeFailureLogger.logHandled(GlobalExceptionHandler.class, exception);
        return ResponseEntity.status(403).body(ApiResponse.error(403, "Access denied",
                Map.of("_global", "Access denied")));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> unexpected(Exception exception) {
        if (exception instanceof org.springframework.web.ErrorResponse protocol) {
            HttpStatus status = HttpStatus.resolve(protocol.getStatusCode().value());
            if (status != null) {
                ${packageName}.logging.SafeFailureLogger.logHandled(GlobalExceptionHandler.class, exception);
                String message = status == HttpStatus.METHOD_NOT_ALLOWED ? "Method not allowed"
                        : status == HttpStatus.UNSUPPORTED_MEDIA_TYPE ? "Unsupported media type"
                        : status.is4xxClientError() ? "Invalid request" : "Unexpected server error";
                return ResponseEntity.status(status).headers(protocol.getHeaders()).body(ApiResponse.error(status.value(), message,
                        Map.of("_global", message)));
            }
        }
        ${packageName}.logging.SafeFailureLogger.logUnexpected(GlobalExceptionHandler.class, exception);
        return ResponseEntity.status(500).body(ApiResponse.error(500, "Unexpected server error",
                Map.of("_global", "Unexpected server error")));
    }
}
