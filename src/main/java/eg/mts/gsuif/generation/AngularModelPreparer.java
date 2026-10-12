package eg.mts.gsuif.generation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import eg.mts.gsuif.generation.GenerationSpecification.FieldSpec;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Prepares the Angular template model from a validated generation context. */
final class AngularModelPreparer {
    Map<String, Object> prepare(GenerationContext context) throws Exception {
        GenerationSpecification s = context.specification();
        JsonNode components = context.snapshot().path("components");
        Set<String> selectedComponents = GenerationContextBuilder.selectedComponentIds(
                context.snapshot(), s.openApi().bindingOperations(), context.metadataVersion().getSchemaVersion());
        Map<String, String> rules = new LinkedHashMap<>();
        ObjectMapper json = new ObjectMapper();
        for (JsonNode binding : context.snapshot().path("apiBindings")) {
            java.util.UUID bindingId;
            try { bindingId = java.util.UUID.fromString(binding.path("id").asText()); } catch (Exception ex) { continue; }
            if (!s.openApi().bindingOperations().containsKey(bindingId) || !binding.has("visibilityRule")) continue;
            for (String linked : GenerationContextBuilder.bindingComponentIds(binding))
                rules.put(linked.replace("-", ""), json.writeValueAsString(preserveNumericEquals(binding.path("visibilityRule"), json)));
        }
        List<Map<String, Object>> controls = new ArrayList<>();
        Map<String, FieldSpec> fields = new HashMap<>();
        for (var f : s.entity().fields()) fields.put(f.name(), f);
        for (int i = 0; i < components.size(); i++) {
            JsonNode c = components.get(i);
            if (!selectedComponents.contains(c.path("id").asText())) continue;
            String type = c.path("type").asText();
            Map<String, Object> control = new LinkedHashMap<>();
            control.put("type", type);
            control.put("id", c.path("id").asText().replace("-", ""));
            control.put("row", c.path("position").path("row").asText());
            control.put("col", c.path("position").path("col").asText());
            control.put("gridRow", Integer.toString(c.path("position").path("row").intValue() + 1));
            control.put("gridCol", Integer.toString(c.path("position").path("col").intValue() + 1));
            control.put("width", c.path("size").path("width").asText());
            control.put("height", c.path("size").path("height").asText());
            control.put("disabled", c.path("disabled").asBoolean());
            control.put("visible", c.path("visibility").asBoolean());
            AngularComponentHandlers.Handler handler = AngularComponentHandlers.find(type);
            if (handler == null)
                throw new GenerationValidationException(List.of("snapshot.components[" + i + "] (" + c.path("id").asText() + ").type: unsupported Angular component"));
            control.put("template", handler.template());
            control.putAll(handler.prepare(c, s.angular(), fields, "snapshot.components[" + i + "]").values());
            controls.add(control);
        }
        @SuppressWarnings("unchecked")
        Map<String, Map<String, Object>> dtoProperties = (Map<String, Map<String, Object>>) ((Map<String, Object>) context.templateModel().get("api")).get("dtoProperties");
        @SuppressWarnings("unchecked")
        List<String> dtoRequired = (List<String>) ((Map<String, Object>) context.templateModel().get("api")).get("dtoRequired");
        List<Map<String, String>> properties = new ArrayList<>();
        for (var property : dtoProperties.entrySet()) {
            String name = property.getKey(); Map<String, Object> definition = property.getValue();
            String tsType = definition.containsKey("$ref") || "string".equals(definition.get("type")) ? "string"
                    : "boolean".equals(definition.get("type")) ? "boolean" : "number";
            properties.add(Map.of("name", name, "type", tsType, "optional", dtoRequired.contains(name) ? "" : "?"));
        }
        String kebab = s.angular().className().replaceAll("([a-z])([A-Z])", "$1-$2").toLowerCase(Locale.ROOT);
        @SuppressWarnings("unchecked")
        Map<String, List<String>> options = (Map<String, List<String>>) ((Map<String, Object>) context.templateModel().get("api")).get("enumOptions");
        Map<String, List<String>> selectedOptions = new LinkedHashMap<>();
        for (var field : s.entity().fields()) if (field.enumType() != null) {
            List<String> values = options.get(field.enumType());
            if (values == null || values.isEmpty()) throw new GenerationValidationException(List.of("specification.fields." + field.name() + ": enum options absent from contract"));
            selectedOptions.put(field.name(), values);
        }
        Map<String, Object> model = Map.of("className", s.angular().className(), "kebab", kebab,
                "properties", properties, "controls", controls, "options", selectedOptions, "rules", rules);
        return model;
    }

    private static JsonNode preserveNumericEquals(JsonNode rule, ObjectMapper mapper) {
        if (!rule.isObject()) return rule;
        ObjectNode copy = ((ObjectNode) rule).deepCopy();
        if ("field".equals(copy.path("op").asText()) && copy.path("equals").isNumber()) {
            ObjectNode number = mapper.createObjectNode();
            number.put("$jsonNumber", copy.path("equals").toString());
            copy.set("equals", number);
        }
        if (copy.has("rule")) copy.set("rule", preserveNumericEquals(copy.path("rule"), mapper));
        if (copy.path("rules").isArray()) {
            ArrayNode children = mapper.createArrayNode();
            for (JsonNode child : copy.path("rules")) children.add(preserveNumericEquals(child, mapper));
            copy.set("rules", children);
        }
        return copy;
    }

}
