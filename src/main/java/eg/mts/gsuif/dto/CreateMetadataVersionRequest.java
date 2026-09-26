package eg.mts.gsuif.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import tools.jackson.databind.JsonNode;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Request DTO for creating a new {@link eg.mts.gsuif.entity.MetadataVersion}.
 * Only {@code schemaVersion} and {@code snapshot} are allowed. Extra properties are rejected.
 */
@JsonIgnoreProperties(ignoreUnknown = false)
public record CreateMetadataVersionRequest(
        @NotBlank(message = "Schema version cannot be blank")
        @Size(max = 20, message = "Schema version cannot exceed 20 characters")
        @Schema(minLength = 1, description = "Required; trimmed before validation and length checks. Must contain a non-whitespace character (Java Character.isWhitespace).", example = "1.0")
        String schemaVersion,

        @NotNull(message = "Snapshot cannot be null")
        @Schema(implementation = Object.class, types = {"object", "array", "string", "number", "boolean"},
                description = "Any non-null JSON value. Objects and arrays may contain nested nulls; a top-level null is rejected.",
                example = "{\"components\":[{\"type\":\"text\",\"label\":\"Order details\",\"value\":null}]}")
        JsonNode snapshot
) {
    @com.fasterxml.jackson.annotation.JsonCreator
    public CreateMetadataVersionRequest(
            @com.fasterxml.jackson.annotation.JsonProperty("schemaVersion") String schemaVersion,
            @com.fasterxml.jackson.annotation.JsonProperty("snapshot") JsonNode snapshot) {
        this.schemaVersion = schemaVersion != null ? schemaVersion.trim() : null;
        this.snapshot = (snapshot == null || snapshot.isNull() || snapshot.isMissingNode()) ? null : snapshot;
    }

    @com.fasterxml.jackson.annotation.JsonAnySetter
    public void handleUnknown(String key, Object value) {
        throw new IllegalArgumentException("Unrecognized property: " + key);
    }
}
