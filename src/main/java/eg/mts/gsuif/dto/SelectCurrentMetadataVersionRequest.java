package eg.mts.gsuif.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

/** Request to select an existing version of a page. */
public record SelectCurrentMetadataVersionRequest(
        @NotNull(message = "Version ID cannot be null")
        @Schema(description = "UUID of a metadata version owned by the page", requiredMode = Schema.RequiredMode.REQUIRED)
        UUID versionId
) {
}
