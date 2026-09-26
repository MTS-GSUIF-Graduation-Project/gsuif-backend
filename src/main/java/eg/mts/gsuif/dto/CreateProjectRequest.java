package eg.mts.gsuif.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Request body for creating a new project.
 */
public record CreateProjectRequest(
        @NotBlank(message = "Project name is required")
        @Size(max = 200, message = "Project name must not exceed 200 characters")
        @Schema(minLength = 1, description = "Required; trimmed before validation and length checks. Must contain a non-whitespace character (Java Character.isWhitespace).", example = "Customer portal")
        String name,

        @Schema(nullable = true, description = "Optional description; null is accepted. Trimmed when supplied.", example = "Self-service customer pages")
        String description
) {
    public CreateProjectRequest {
        name = name != null ? name.trim() : null;
        description = description != null ? description.trim() : null;
    }
}
