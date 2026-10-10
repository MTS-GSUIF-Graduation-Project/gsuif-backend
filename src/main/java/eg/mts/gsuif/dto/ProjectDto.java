package eg.mts.gsuif.dto;

import java.time.Instant;
import java.util.UUID;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Immutable DTO representing a {@link eg.mts.gsuif.entity.GsuifProject} response.
 *
 * <p>Used as the {@code body} inside {@link ApiResponse}.
 */
public record ProjectDto(
        UUID id,
        String name,
        @Schema(nullable = true, description = "Project description, or null when absent.")
        String description,
        Instant createdAt,
        Instant updatedAt,
        String createdBy,
        String lastModifiedBy
) {
}
