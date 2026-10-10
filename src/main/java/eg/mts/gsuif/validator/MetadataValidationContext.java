package eg.mts.gsuif.validator;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Objects;
import java.util.UUID;

/** Internal operation context; routes are page attributes, never snapshot fields. */
public record MetadataValidationContext(Operation operation, JsonNode snapshot,
                                        String exactPersistedJson, UUID projectId,
                                        UUID excludedPageId, String route) {
    public enum Operation { SNAPSHOT, PAGE }

    public static MetadataValidationContext snapshot(JsonNode snapshot, String exactPersistedJson) {
        return new MetadataValidationContext(Operation.SNAPSHOT, Objects.requireNonNull(snapshot),
                Objects.requireNonNull(exactPersistedJson), null, null, null);
    }

    public static MetadataValidationContext page(UUID projectId, UUID excludedPageId, String route) {
        return new MetadataValidationContext(Operation.PAGE, null, null,
                Objects.requireNonNull(projectId), excludedPageId, route);
    }
}
