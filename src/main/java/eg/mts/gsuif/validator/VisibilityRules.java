package eg.mts.gsuif.validator;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/** Semantic checks that complement the recursive 1.2.0 visibility-rule schema. */
final class VisibilityRules {
    private VisibilityRules() {}

    static List<MetadataSchemaValidator.ValidationError> validate(JsonNode record, String path) {
        List<MetadataSchemaValidator.ValidationError> errors = new ArrayList<>();
        JsonNode bindings = record.path("apiBindings");
        if (!bindings.isArray()) return errors;
        for (int i = 0; i < bindings.size(); i++) {
            JsonNode binding = bindings.get(i);
            JsonNode rule = binding != null && binding.isObject() ? binding.get("visibilityRule") : null;
            if (rule != null && rule.isContainerNode() && exceedsMaxDepth(rule)) {
                errors.add(new MetadataSchemaValidator.ValidationError(
                        path + ".apiBindings[" + i + "].visibilityRule",
                        "visibility rule exceeds maximum nesting depth of " + VisibilityRuleEvaluator.MAX_DEPTH));
            }
        }
        return errors;
    }

    private static boolean exceedsMaxDepth(JsonNode rule) {
        Deque<RuleDepth> stack = new ArrayDeque<>();
        stack.push(new RuleDepth(rule, 0));
        while (!stack.isEmpty()) {
            RuleDepth current = stack.pop();
            if (current.depth() > VisibilityRuleEvaluator.MAX_DEPTH) return true;
            JsonNode child = current.rule().get("rule");
            if (child != null) stack.push(new RuleDepth(child, current.depth() + 1));
            JsonNode children = current.rule().get("rules");
            if (children != null && children.isArray()) {
                for (JsonNode nested : children) stack.push(new RuleDepth(nested, current.depth() + 1));
            }
        }
        return false;
    }

    private record RuleDepth(JsonNode rule, int depth) {}
}
