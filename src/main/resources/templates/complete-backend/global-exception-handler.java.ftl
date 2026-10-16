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
        Map<String, String> errors = new TreeMap<>();
        exception.getBindingResult().getFieldErrors().stream().map(error -> error.getField())
                .filter(FIELD_KEYS::contains).distinct().sorted().limit(MAX_FIELD_ERRORS)
                .forEach(field -> errors.put(field, "Invalid value"));
        if (errors.isEmpty()) errors.put("_global", "Invalid request");
        return ResponseEntity.status(400).body(ApiResponse.error(400, "Invalid request", errors));
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<ApiResponse<Void>> missingParameter(MissingServletRequestParameterException exception) {
        String key = FIELD_KEYS.contains(exception.getParameterName()) ? exception.getParameterName() : "_global";
        return ResponseEntity.status(400).body(ApiResponse.error(400, "Invalid request",
                Map.of(key, "Required parameter is missing")));
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiResponse<Void>> invalidParameter(MethodArgumentTypeMismatchException exception) {
        String key = FIELD_KEYS.contains(exception.getName()) ? exception.getName() : "_global";
        return ResponseEntity.status(400).body(ApiResponse.error(400, "Invalid request",
                Map.of(key, "Invalid value")));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiResponse<Void>> unreadableBody(HttpMessageNotReadableException exception) {
        return ResponseEntity.status(400).body(ApiResponse.error(400, "Invalid request",
                Map.of("_global", "Invalid request body")));
    }

    @ExceptionHandler(RequestedRecordNotFoundException.class)
    public ResponseEntity<ApiResponse<Void>> missingRecord(RequestedRecordNotFoundException exception) {
        return ResponseEntity.status(404).body(ApiResponse.error(404, "Resource not found",
                Map.of("_global", "Resource not found")));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> unexpected(Exception exception) {
        if (exception instanceof org.springframework.web.ErrorResponse protocol) {
            HttpStatus status = HttpStatus.resolve(protocol.getStatusCode().value());
            if (status != null) {
                String message = status == HttpStatus.METHOD_NOT_ALLOWED ? "Method not allowed"
                        : status == HttpStatus.UNSUPPORTED_MEDIA_TYPE ? "Unsupported media type"
                        : status.is4xxClientError() ? "Invalid request" : "Unexpected server error";
                return ResponseEntity.status(status).headers(protocol.getHeaders()).body(ApiResponse.error(status.value(), message,
                        Map.of("_global", message)));
            }
        }
        return ResponseEntity.status(500).body(ApiResponse.error(500, "Unexpected server error",
                Map.of("_global", "Unexpected server error")));
    }
}
