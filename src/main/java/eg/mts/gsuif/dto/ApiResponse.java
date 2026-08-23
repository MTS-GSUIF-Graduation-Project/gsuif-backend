package eg.mts.gsuif.dto;

import java.util.List;

public record ApiResponse<T>(
    String status,
    String clientMessage,
    int statusCode,
    T body,
    List<String> errors
) {
    public static <T> ApiResponse<T> success(T body, String clientMessage, int statusCode) {
        return new ApiResponse<>("SUCCESS", clientMessage, statusCode, body, null);
    }

    public static <T> ApiResponse<T> error(String clientMessage, int statusCode, List<String> errors) {
        return new ApiResponse<>("ERROR", clientMessage, statusCode, null, errors);
    }
}
