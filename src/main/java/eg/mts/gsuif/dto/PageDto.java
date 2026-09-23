package eg.mts.gsuif.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * Immutable DTO representing a {@link eg.mts.gsuif.entity.GsuifPage} response.
 *
 * <p>Used as the {@code body} inside {@link ApiResponse}.
 */
public record PageDto(
        UUID id,
        UUID projectId,
        String name,
        String route,
        Instant createdAt,
        Instant updatedAt,
        String createdBy,
        String lastModifiedBy
) {
}
