package eg.mts.gsuif.validator;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
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
            JsonNode linked = binding.get("linkedComponentIds");
            if (linked != null) {
                for (int j = 0; j < linked.size(); j++) {
                    if (!components.contains(linked.get(j).asText())) {
                        errors.add(new MetadataSchemaValidator.ValidationError(
                                base + ".linkedComponentIds[" + j + "]", "linked component does not exist"));
                    }
                }
            }
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
        if (containsCycle(edges)) {
            errors.add(new MetadataSchemaValidator.ValidationError(path + ".apiBindings", "component relationships contain a cycle"));
        }
        return errors;
    }

    private static boolean containsCycle(Map<String, Set<String>> edges) {
        Map<String, VisitState> states = new HashMap<>();
        for (String start : edges.keySet()) {
            if (states.containsKey(start)) continue;
            Deque<Traversal> stack = new ArrayDeque<>();
            states.put(start, VisitState.VISITING);
            stack.push(new Traversal(start, edges.getOrDefault(start, Set.of()).iterator()));
            while (!stack.isEmpty()) {
                Traversal current = stack.peek();
                if (!current.children().hasNext()) {
                    states.put(current.node(), VisitState.VISITED);
                    stack.pop();
                    continue;
                }
                String child = current.children().next();
                VisitState childState = states.get(child);
                if (childState == VisitState.VISITING) return true;
                if (childState == null) {
                    states.put(child, VisitState.VISITING);
                    stack.push(new Traversal(child, edges.getOrDefault(child, Set.of()).iterator()));
                }
            }
        }
        return false;
    }

    private enum VisitState { VISITING, VISITED }

    private record Traversal(String node, Iterator<String> children) {}
}
