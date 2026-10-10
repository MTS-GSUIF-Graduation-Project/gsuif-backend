package eg.mts.gsuif.dto;

import java.time.Instant;
import java.util.UUID;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Immutable DTO representing a {@link eg.mts.gsuif.entity.GsuifPage} response.
 *
 * <p>Used as the {@code body} inside {@link ApiResponse}.
 */
public record PageDto(
        UUID id,
        UUID projectId,
        String name,
        @Schema(nullable = true, description = "Page route, or null when absent or cleared.")
        String route,
        Instant createdAt,
        Instant updatedAt,
        String createdBy,
        String lastModifiedBy
) {
}
