package ${api.validatorPackage};

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Deterministic request validator generated from the selected packaged OpenAPI contract.
 *
 * <p>SCRUM-50 requires generated {@code validate()} implementations but does not prescribe
 * their Java signature. This byte-in/findings-out contract keeps the generated validator
 * independent of GSUIF and Spring runtime types.
 */
public final class ${api.validatorClass} {
    public enum Code {
        REQUIRED_FIELD,
        WRONG_TYPE,
        INVALID_ENUM,
        DUPLICATE_VALUE,
        BOUNDARY_VIOLATION,
        MALFORMED_JSON,
        UNKNOWN_SCHEMA
    }

    public record Finding(String path, Code code, String detail) { }

    private static final Pattern DUPLICATE_FIELD =
            Pattern.compile("Duplicate field ['\\\"]([^'\\\"]+)['\\\"]");
    private static final Comparator<Finding> ORDER = Comparator
            .comparing(Finding::path)
            .thenComparing(finding -> finding.code().name())
            .thenComparing(Finding::detail);

    private final ObjectMapper json;

    public ${api.validatorClass}() {
        json = new ObjectMapper();
        json.enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
    }

    public List<Finding> validate(String schemaName, byte[] jsonUtf8) {
        if (schemaName == null || jsonUtf8 == null) {
            return List.of(new Finding("$", Code.MALFORMED_JSON, "schemaName and jsonUtf8 are required"));
        }
        JsonNode root;
        try {
            root = json.readTree(jsonUtf8);
        } catch (JsonProcessingException failure) {
            Matcher duplicate = DUPLICATE_FIELD.matcher(String.valueOf(failure.getOriginalMessage()));
            if (duplicate.find()) {
                return List.of(new Finding("$." + duplicate.group(1), Code.DUPLICATE_VALUE,
                        "duplicate JSON object member"));
            }
            return List.of(new Finding("$", Code.MALFORMED_JSON, "invalid UTF-8 JSON object"));
        } catch (Exception failure) {
            return List.of(new Finding("$", Code.MALFORMED_JSON, "invalid UTF-8 JSON object"));
        }
        if (root == null || !root.isObject()) {
            return List.of(new Finding("$", Code.WRONG_TYPE, "expected JSON object"));
        }
        List<Finding> findings = new ArrayList<>();
        switch (schemaName) {
<#list api.validatorSchemas as schema>
            case "${schema.name?j_string}" -> validate${schema.name}(root, findings);
</#list>
            default -> findings.add(new Finding("$", Code.UNKNOWN_SCHEMA, "unsupported schema " + schemaName));
        }
        findings.sort(ORDER);
        return List.copyOf(findings);
    }

<#list api.validatorSchemas as schema>
    private static void validate${schema.name}(JsonNode root, List<Finding> findings) {
<#list schema.fields as field>
        validate${schema.name}${field.name?cap_first}(root, findings);
</#list>
    }

<#list schema.fields as field>
    private static void validate${schema.name}${field.name?cap_first}(JsonNode root, List<Finding> findings) {
        JsonNode value = root.get("${field.name?j_string}");
        if (value == null) {
<#if field.required>
            findings.add(new Finding("$.${field.name?j_string}", Code.REQUIRED_FIELD, "required field is missing"));
</#if>
            return;
        }
        if (value.isNull()) {
<#if !field.nullable>
            findings.add(new Finding("$.${field.name?j_string}", Code.WRONG_TYPE, "null is not allowed"));
</#if>
            return;
        }
<#if field.jsonType == "string">
        if (!value.isTextual()) {
            findings.add(new Finding("$.${field.name?j_string}", Code.WRONG_TYPE, "expected string"));
            return;
        }
<#elseif field.jsonType == "integer">
        if (!value.isIntegralNumber()) {
            findings.add(new Finding("$.${field.name?j_string}", Code.WRONG_TYPE, "expected integer"));
            return;
        }
<#elseif field.jsonType == "number">
        if (!value.isNumber()) {
            findings.add(new Finding("$.${field.name?j_string}", Code.WRONG_TYPE, "expected number"));
            return;
        }
<#elseif field.jsonType == "boolean">
        if (!value.isBoolean()) {
            findings.add(new Finding("$.${field.name?j_string}", Code.WRONG_TYPE, "expected boolean"));
            return;
        }
</#if>
<#if field.enumValues?size gt 0>
        if (!Set.of(<#list field.enumValues as option>"${option?j_string}"<#sep>, </#list>).contains(value.textValue())) {
            findings.add(new Finding("$.${field.name?j_string}", Code.INVALID_ENUM, "value is outside the contracted enum"));
        }
</#if>
<#if field.maxLength gt 0>
        if (value.isTextual() && value.textValue().length() > ${field.maxLength?c}) {
            findings.add(new Finding("$.${field.name?j_string}", Code.BOUNDARY_VIOLATION,
                    "length exceeds ${field.maxLength?c}"));
        }
</#if>
    }

</#list>
</#list>
}
