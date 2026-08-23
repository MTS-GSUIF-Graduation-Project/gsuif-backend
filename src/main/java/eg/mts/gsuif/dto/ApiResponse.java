package eg.mts.gsuif.dto;

import java.util.Map;

public record ApiResponse<T>(
    String status,
    String clientMessage,
    int statusCode,
    T body,
    Map<String, String> errors
) {
    public static <T> ApiResponse<T> success(T body, String clientMessage, int statusCode) {
        return new ApiResponse<>("OK", clientMessage, statusCode, body, null);
    }

    public static <T> ApiResponse<T> error(String status, String clientMessage, int statusCode, Map<String, String> errors) {
        return new ApiResponse<>(status, clientMessage, statusCode, null, errors);
    }
}
