package eg.mts.gsuif.validator;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Cross-record checks for the 1.2.0 page and snapshot contracts. */
public final class BindingRelationships {
    private BindingRelationships() {}

    public static List<MetadataSchemaValidator.ValidationError> validate(JsonNode record, String path) {
        List<MetadataSchemaValidator.ValidationError> errors = new ArrayList<>();
        Set<String> components = new HashSet<>();
        for (JsonNode component : record.path("components")) {
            components.add(component.path("id").asText());
        }
        Map<String, Set<String>> edges = new HashMap<>();
        Set<String> claimedChildren = new HashSet<>();
        JsonNode bindings = record.path("apiBindings");
        for (int i = 0; i < bindings.size(); i++) {
            JsonNode binding = bindings.get(i);
            String base = path + ".apiBindings[" + i + "]";
            JsonNode parentNode = binding.get("parentComponentId");
            JsonNode children = binding.get("childComponentIds");
            if (parentNode == null && children == null) continue;
            if (parentNode == null || children == null || children.isEmpty()) {
                errors.add(new MetadataSchemaValidator.ValidationError(base, "relationship requires parentComponentId and nonempty childComponentIds"));
                continue;
            }
            String parent = parentNode.asText();
            if (!components.contains(parent)) {
                errors.add(new MetadataSchemaValidator.ValidationError(base + ".parentComponentId", "parent component does not exist"));
            }
            Set<String> outgoing = edges.computeIfAbsent(parent, ignored -> new HashSet<>());
            for (int j = 0; j < children.size(); j++) {
                String child = children.get(j).asText();
                String childPath = base + ".childComponentIds[" + j + "]";
                if (!components.contains(child)) errors.add(new MetadataSchemaValidator.ValidationError(childPath, "child component does not exist"));
                if (parent.equals(child)) errors.add(new MetadataSchemaValidator.ValidationError(childPath, "component cannot be its own child"));
                if (!claimedChildren.add(child)) errors.add(new MetadataSchemaValidator.ValidationError(childPath, "child component is linked more than once"));
                outgoing.add(child);
            }
        }
        for (String node : edges.keySet()) {
            if (cyclic(node, edges, new HashSet<>(), new HashSet<>())) {
                errors.add(new MetadataSchemaValidator.ValidationError(path + ".apiBindings", "component relationships contain a cycle"));
                break;
            }
        }
        return errors;
    }

    private static boolean cyclic(String node, Map<String, Set<String>> edges, Set<String> visiting, Set<String> visited) {
        if (visiting.contains(node)) return true;
        if (!visited.add(node)) return false;
        visiting.add(node);
        for (String child : edges.getOrDefault(node, Set.of())) {
            if (cyclic(child, edges, visiting, visited)) return true;
        }
        visiting.remove(node);
        return false;
    }
}
