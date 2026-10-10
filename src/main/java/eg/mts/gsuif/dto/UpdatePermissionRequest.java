package eg.mts.gsuif.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Internal request for updating mutable permission fields. The canonical code is immutable.
 */
public record UpdatePermissionRequest(
        @NotBlank(message = "Permission name is required")
        @Size(max = 200, message = "Permission name must not exceed 200 characters")
        String name,

        String description
) {
}
