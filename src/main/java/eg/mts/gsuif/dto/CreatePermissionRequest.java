package eg.mts.gsuif.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Internal request for creating a configurable permission.
 */
public record CreatePermissionRequest(
        @NotBlank(message = "Permission code is required")
        @Size(max = 50, message = "Permission code must not exceed 50 characters")
        String code,

        @NotBlank(message = "Permission name is required")
        @Size(max = 200, message = "Permission name must not exceed 200 characters")
        String name,

        String description
) {
}
