package eg.mts.gsuif.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import tools.jackson.databind.JsonNode;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Request DTO for creating a new {@link eg.mts.gsuif.entity.MetadataVersion}.
 * Only {@code schemaVersion} and {@code snapshot} are allowed. Extra properties are rejected.
 */
@JsonIgnoreProperties(ignoreUnknown = false)
public record CreateMetadataVersionRequest(
        @NotBlank(message = "Schema version cannot be blank")
        @Size(max = 20, message = "Schema version cannot exceed 20 characters")
        String schemaVersion,
        
        @NotNull(message = "Snapshot cannot be null")
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
