package eg.mts.gsuif.generation;

import eg.mts.gsuif.generation.GenerationSpecification.FieldSpec;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;

/**
 * Effective validation for one generated request-DTO field: the selected contract's constraints
 * reconciled with the field's persistence column. Nothing here is specific to a sample entity; the
 * same rules apply to every registered contract.
 *
 * <ul>
 *   <li>Contract constraints are enforced as declared ({@code minLength}, {@code maxLength},
 *       {@code pattern}, {@code minimum}, {@code maximum}, nullability).</li>
 *   <li>A plain string column stores at most {@code columnLength} (default 255) characters. When the
 *       contract declares no {@code maxLength}, the column length is enforced and reported as a
 *       contract gap, so the API answers 400 instead of failing at the database.</li>
 *   <li>A contract that accepts values the column cannot store, or whose bounds cannot be satisfied,
 *       is a conflict and stops generation.</li>
 * </ul>
 */
final class RequestValidationRules {
    static final int DEFAULT_TEXT_COLUMN_LENGTH = 255;
    private static final BigInteger INT32_MIN = BigInteger.valueOf(Integer.MIN_VALUE);
    private static final BigInteger INT32_MAX = BigInteger.valueOf(Integer.MAX_VALUE);
    private static final BigInteger INT64_MIN = BigInteger.valueOf(Long.MIN_VALUE);
    private static final BigInteger INT64_MAX = BigInteger.valueOf(Long.MAX_VALUE);

    /** One enforced rule and where it came from, for the generated README. */
    record Rule(String description, String source) { }

    record Evaluation(List<String> annotations, List<Rule> rules, List<String> gaps, List<String> errors) { }

    private RequestValidationRules() { }

    /**
     * @param path diagnostic prefix such as {@code contract.create.title}
     * @param entity the persistence field, or null when the contract field has no entity counterpart
     */
    static Evaluation evaluate(String path, FieldSpec entity, PackagedContractLoader.FieldSchema schema) {
        List<String> annotations = new ArrayList<>();
        List<Rule> rules = new ArrayList<>();
        List<String> gaps = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        if (!schema.nullable()) {
            annotations.add("@jakarta.validation.constraints.NotNull");
            rules.add(new Rule("not null", "contract (nullable: false)"));
        }
        var declared = schema.constraints();
        boolean plainString = "string".equals(schema.type()) && schema.format().isEmpty() && schema.enumName().isEmpty();
        if (plainString) {
            int column = entity == null || entity.columnLength() == null ? DEFAULT_TEXT_COLUMN_LENGTH : entity.columnLength();
            Integer max = declared.maxLength();
            String maxSource = "contract (maxLength)";
            if (max == null) {
                max = column;
                maxSource = "persistence column varchar(" + column + ")";
                gaps.add(path + ": contract declares no maxLength; enforcing the persistence column length " + column);
            } else if (max > column) {
                errors.add(path + ": contract maxLength " + max + " exceeds the persistence column length " + column
                        + " (accepted values could not be stored)");
            }
            Integer min = declared.minLength();
            if (min != null && min > Math.min(max, column))
                errors.add(path + ": contract minLength " + min + " exceeds the enforceable maximum length "
                        + Math.min(max, column) + " (no value can satisfy it)");
            annotations.add("@jakarta.validation.constraints.Size(" + (min == null ? "" : "min = " + min + ", ")
                    + "max = " + max + ")");
            if (min != null) rules.add(new Rule("length >= " + min, "contract (minLength)"));
            rules.add(new Rule("length <= " + max, maxSource));
            if (declared.pattern() != null) {
                if (declared.pattern().chars().anyMatch(c -> c < 0x20))
                    errors.add(path + ": contract pattern contains control characters, which are unsupported");
                else {
                    // JSON Schema patterns are unanchored (search semantics); @Pattern matches the whole value.
                    annotations.add("@jakarta.validation.constraints.Pattern(regexp = \""
                            + javaString("(?s).*(?:" + declared.pattern() + ").*") + "\")");
                    rules.add(new Rule("matches /" + declared.pattern() + "/ (unanchored, Java regex dialect)",
                            "contract (pattern)"));
                }
            }
        }
        if ("integer".equals(schema.type())) {
            boolean int32 = "int32".equals(schema.format());
            BigInteger low = int32 ? INT32_MIN : INT64_MIN, high = int32 ? INT32_MAX : INT64_MAX;
            String range = int32 ? "int32" : "int64";
            for (var bound : List.of(new Object[]{"minimum", declared.minimum()}, new Object[]{"maximum", declared.maximum()})) {
                if (bound[1] == null) continue;
                BigInteger value = (BigInteger) bound[1];
                if (value.compareTo(low) < 0 || value.compareTo(high) > 0) {
                    errors.add(path + ": contract " + bound[0] + " " + value + " is outside the " + range + " range");
                    continue;
                }
                boolean minimum = bound[0].equals("minimum");
                annotations.add("@jakarta.validation.constraints." + (minimum ? "Min" : "Max") + "(" + value + "L)");
                rules.add(new Rule((minimum ? "value >= " : "value <= ") + value, "contract (" + bound[0] + ")"));
            }
            rules.add(new Rule("JSON integer within " + range, "contract (type/format)"));
        }
        if (!schema.enumName().isEmpty())
            rules.add(new Rule("one of " + schema.values(), "contract (enum " + schema.enumName() + ")"));
        else if (!plainString && !"integer".equals(schema.type()))
            rules.add(new Rule("JSON " + schema.type() + (schema.format().isEmpty() ? "" : " (" + schema.format() + ")"),
                    "contract (type/format)"));
        else if (plainString)
            rules.add(new Rule("JSON string", "contract (type)"));
        return new Evaluation(List.copyOf(annotations), List.copyOf(rules), List.copyOf(gaps), List.copyOf(errors));
    }

    /** Escapes a value for a Java string literal; control characters are rejected by the caller. */
    static String javaString(String value) {
        StringBuilder escaped = new StringBuilder();
        for (char c : value.toCharArray()) {
            if (c == '\\') escaped.append("\\\\");
            else if (c == '"') escaped.append("\\\"");
            else if (c > 0x7e) escaped.append(String.format("\\u%04x", (int) c));
            else escaped.append(c);
        }
        return escaped.toString();
    }
}
