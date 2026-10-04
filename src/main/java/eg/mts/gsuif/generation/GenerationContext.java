package eg.mts.gsuif.generation;

import com.fasterxml.jackson.databind.JsonNode;
import eg.mts.gsuif.entity.MetadataVersion;
import java.util.Map;
import java.util.Set;

/** Validated provider input; target and framework are execution choices. */
public record GenerationContext(MetadataVersion metadataVersion, JsonNode snapshot,
        GenerationSpecification specification, Set<Target> targets, String framework,
        Map<String, Object> templateModel) {
    public enum Target { ENTITY, CONTROLLER, ANGULAR }
}
