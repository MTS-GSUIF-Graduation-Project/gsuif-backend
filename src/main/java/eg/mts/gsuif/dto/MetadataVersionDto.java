package eg.mts.gsuif.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import tools.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.UUID;

/**
 * Response DTO for {@link eg.mts.gsuif.entity.MetadataVersion}.
 */
public record MetadataVersionDto(
        UUID id,
        UUID pageId,
        UUID projectId,
        Integer version,
        String schemaVersion,
        @JsonProperty("isCurrent")
        boolean isCurrent,
        JsonNode snapshot,
        Instant createdAt,
        Instant updatedAt,
        String createdBy,
        String lastModifiedBy
) {
}
