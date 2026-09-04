package eg.mts.gsuif.dto;

import java.util.Map;

public record ApiResponse<T>(
    String status,
    String clientMessage,
    int statusCode,
    T body,
    Map<String, String> errors
) {
    public static <T> ApiResponse<T> success(T body, String clientMessage) {
        return new ApiResponse<>("OK", clientMessage, 200, body, null);
    }

    public static <T> ApiResponse<T> success(T body, String clientMessage, int statusCode) {
        return new ApiResponse<>("OK", clientMessage, statusCode, body, null);
    }

    public static <T> ApiResponse<T> error(int code, String clientMessage, Map<String, String> errors) {
        String status = org.springframework.http.HttpStatus.valueOf(code).name();
        return new ApiResponse<>(status, clientMessage, code, null, errors);
    }
}
