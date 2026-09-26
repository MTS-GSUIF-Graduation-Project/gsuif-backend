package eg.mts.gsuif.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Request body for updating an existing page.
 *
 * <p>A {@code null} (or blank) {@code route} clears the existing route.
 */
public record UpdatePageRequest(
        @NotBlank(message = "Page name is required")
        @Size(max = 200, message = "Page name must not exceed 200 characters")
        @Schema(minLength = 1, description = "Required; trimmed before validation and length checks. Must contain a non-whitespace character (Java Character.isWhitespace).", example = "Order details")
        String name,

        @Size(max = 255, message = "Page route must not exceed 255 characters")
        @Schema(nullable = true, description = "Optional route; trimmed when supplied. Null, omission, or a blank value clears the existing route.", example = "/orders/details")
        String route
) {
    public UpdatePageRequest {
        name = name != null ? name.trim() : null;
        // Blank route is treated as absent (clears existing route).
        if (route != null) {
            route = route.trim().isEmpty() ? null : route.trim();
        }
    }
}
