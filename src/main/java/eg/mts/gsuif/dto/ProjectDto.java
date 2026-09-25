package eg.mts.gsuif.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * Immutable DTO representing a {@link eg.mts.gsuif.entity.GsuifProject} response.
 *
 * <p>Used as the {@code body} inside {@link ApiResponse}.
 */
public record ProjectDto(
        UUID id,
        String name,
        String description,
        Instant createdAt,
        Instant updatedAt,
        String createdBy,
        String lastModifiedBy
) {
}
