<#ftl strip_whitespace=true encoding="UTF-8">
package ${packageName}.api;

public record ApiResponse<T>(String status, String clientMessage, int statusCode, T body,
        java.util.Map<String, String> errors) {
    public static <T> ApiResponse<T> success(T body, String message, int code) {
        return new ApiResponse<>("OK", message, code, body, null);
    }

    public static <T> ApiResponse<T> error(int code, String message, java.util.Map<String, String> errors) {
        return new ApiResponse<>(org.springframework.http.HttpStatus.valueOf(code).name(), message, code, null, errors);
    }
}
