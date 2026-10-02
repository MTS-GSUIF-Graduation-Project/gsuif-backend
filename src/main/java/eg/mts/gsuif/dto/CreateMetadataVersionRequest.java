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
        @Schema(minLength = 1, description = "Required; trimmed before validation and length checks. Must contain a non-whitespace character (Java Character.isWhitespace).", example = "1.0.0")
        String schemaVersion,

        @NotNull(message = "Snapshot cannot be null")
        @tools.jackson.databind.annotation.JsonDeserialize(using = MetadataSnapshotDeserializer.class)
        @Schema(implementation = Object.class, types = {"object"},
                description = "A JSON object representing the UI layout and bindings, structurally validated against the requested schemaVersion.",
                example = "{\"components\":[{\"id\":\"123e4567-e89b-12d3-a456-426614174000\",\"type\":\"text-field\",\"label\":\"Test\",\"position\":{\"row\":0,\"col\":0},\"size\":{\"width\":6,\"height\":1},\"visibility\":true,\"disabled\":false}],\"apiBindings\":[]}")
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
