package eg.mts.gsuif.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import tools.jackson.databind.JsonNode;
import io.swagger.v3.oas.annotations.media.Schema;

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
        @Schema(implementation = Object.class, types = {"object", "array", "string", "number", "boolean"},
                description = "The stored non-null JSON value, returned unchanged in shape. Nested nulls are allowed.")
        JsonNode snapshot,
        Instant createdAt,
        Instant updatedAt,
        String createdBy,
        String lastModifiedBy
) {
}
