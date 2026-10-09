package eg.mts.gsuif.validator;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Set;

/** Pure display decision for 1.2.0 binding rules. Never grants endpoint access. */
public final class VisibilityRuleEvaluator {
    static final int MAX_DEPTH = 64;

    private VisibilityRuleEvaluator() {}

    public record Context(Set<String> permissions, Set<String> roles, JsonNode fields) {}

    public static boolean evaluate(JsonNode rule, Context context) {
        return evaluateInternal(rule, context, 0) == Result.TRUE;
    }

    private enum Result { TRUE, FALSE, INVALID }

    private static Result evaluateInternal(JsonNode rule, Context context, int depth) {
        if (rule == null || !rule.isObject() || context == null || depth > MAX_DEPTH) return Result.INVALID;
        JsonNode opNode = rule.get("op");
        if (opNode == null || !opNode.isTextual()) return Result.INVALID;
        String op = opNode.asText();
        if ("permission".equals(op) || "role".equals(op)) {
            if (rule.size() != 2 || !nonempty(rule.get("value"))) return Result.INVALID;
            Set<String> values = "permission".equals(op) ? context.permissions() : context.roles();
            if (values == null) return Result.INVALID;
            return values.contains(rule.get("value").asText()) ? Result.TRUE : Result.FALSE;
        }
        if ("field".equals(op)) {
            JsonNode expected = rule.get("equals");
            if (rule.size() != 3 || !nonempty(rule.get("field")) || expected == null || expected.isContainerNode()) return Result.INVALID;
            if (context.fields() == null || !context.fields().isObject()) return Result.INVALID;
            JsonNode actual = context.fields().get(rule.get("field").asText());
            if (actual == null || actual.isContainerNode()) return Result.INVALID;
            return expected.equals(actual) ? Result.TRUE : Result.FALSE;
        }
        if ("NOT".equals(op)) {
            if (rule.size() != 2 || !rule.has("rule")) return Result.INVALID;
            Result child = evaluateInternal(rule.get("rule"), context, depth + 1);
            return child == Result.INVALID ? Result.INVALID : child == Result.TRUE ? Result.FALSE : Result.TRUE;
        }
        if ("AND".equals(op) || "OR".equals(op)) {
            JsonNode children = rule.get("rules");
            if (rule.size() != 2 || children == null || !children.isArray() || children.isEmpty()) return Result.INVALID;
            boolean anyTrue = false, anyFalse = false;
            for (JsonNode child : children) {
                Result result = evaluateInternal(child, context, depth + 1);
                if (result == Result.INVALID) return Result.INVALID;
                anyTrue |= result == Result.TRUE;
                anyFalse |= result == Result.FALSE;
            }
            return "AND".equals(op) ? (anyFalse ? Result.FALSE : Result.TRUE) : (anyTrue ? Result.TRUE : Result.FALSE);
        }
        return Result.INVALID;
    }

    private static boolean nonempty(JsonNode value) {
        return value != null && value.isTextual() && !value.asText().isEmpty();
    }
}
