package eg.mts.gsuif.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * Immutable view of a configurable permission.
 */
public record PermissionDto(
        UUID id,
        String code,
        String name,
        String description,
        Instant createdAt,
        Instant updatedAt,
        String createdBy,
        String lastModifiedBy
) {
}
