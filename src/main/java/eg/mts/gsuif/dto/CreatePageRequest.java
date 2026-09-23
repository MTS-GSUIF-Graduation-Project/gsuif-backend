package eg.mts.gsuif.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Request body for creating a new page within a project.
 */
public record CreatePageRequest(
        @NotBlank(message = "Page name is required")
        @Size(max = 200, message = "Page name must not exceed 200 characters")
        String name,

        @Size(max = 255, message = "Page route must not exceed 255 characters")
        String route
) {
    public CreatePageRequest {
        name = name != null ? name.trim() : null;
        // Blank route is treated as absent (no route assigned).
        if (route != null) {
            route = route.trim().isEmpty() ? null : route.trim();
        }
    }
}
