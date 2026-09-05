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

    /**
     * STD-04 — Paginated success response.
     *
     * <p>Wraps a {@link PagedBody} as the {@code body} field, keeping the standard
     * 5-field envelope intact (STD-01). {@code errors} is always null on a success.
     *
     * @param pagedBody     the paginated data (data, totalPages, totalElements, size, number)
     * @param clientMessage human-readable success message
     * @param <T>           element type inside the page
     * @return an {@code ApiResponse} with {@code status="OK"}, {@code statusCode=200}
     */
    public static <T> ApiResponse<PagedBody<T>> page(PagedBody<T> pagedBody, String clientMessage) {
        return new ApiResponse<>("OK", clientMessage, 200, pagedBody, null);
    }
}
