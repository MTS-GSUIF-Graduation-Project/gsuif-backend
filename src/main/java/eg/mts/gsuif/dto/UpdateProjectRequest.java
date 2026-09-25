package eg.mts.gsuif.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Request body for updating an existing project.
 */
public record UpdateProjectRequest(
        @NotBlank(message = "Project name is required")
        @Size(max = 200, message = "Project name must not exceed 200 characters")
        String name,

        String description
) {
    public UpdateProjectRequest {
        name = name != null ? name.trim() : null;
        description = description != null ? description.trim() : null;
    }
}
