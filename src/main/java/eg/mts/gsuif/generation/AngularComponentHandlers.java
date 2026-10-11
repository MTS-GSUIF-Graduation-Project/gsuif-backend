package eg.mts.gsuif.generation;

import com.fasterxml.jackson.databind.JsonNode;
import eg.mts.gsuif.generation.GenerationSpecification.AngularSpec;
import eg.mts.gsuif.generation.GenerationSpecification.FieldSpec;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiPredicate;

/** Registration and typed preparation for supported Angular component structures. */
final class AngularComponentHandlers {
    interface Details {
        Map<String, Object> values();
    }

    record Field(String key, String label) implements Details {
        public Map<String, Object> values() { return Map.of("key", key, "label", label); }
    }
    record Table(List<Column> columns) implements Details {
        public Map<String, Object> values() {
            return Map.of("columns", columns.stream().map(column -> Map.of("key", column.key(), "label", column.label())).toList());
        }
    }
    record Column(String key, String label) { }
    record Form(String label, String submitLabel) implements Details {
        public Map<String, Object> values() { return Map.of("label", label, "submitLabel", submitLabel); }
    }
    record Navigation(List<Item> items) implements Details {
        public Map<String, Object> values() {
            return Map.of("items", items.stream().map(item -> Map.of("label", item.label(), "route", item.route())).toList());
        }
    }
    record Item(String label, String route) { }
    record Modal(String label) implements Details {
        public Map<String, Object> values() { return Map.of("label", label); }
    }
    record Pagination(int pageSize) implements Details {
        public Map<String, Object> values() { return Map.of("pageSize", pageSize); }
    }

    record ValidationContext(AngularSpec spec, Map<String, Object> dtoProperties,
                             Set<String> selectedListComponents, Set<UUID> legacyTables) { }

    interface Handler {
        Details prepare(JsonNode component, AngularSpec spec, Map<String, FieldSpec> fields, String path);
        default String template() { throw new IllegalStateException("unregistered Angular template"); }
        default void validate(JsonNode component, String path, ValidationContext context, List<String> errors) { }
        default boolean validField(FieldSpec field, Map<String, Object> property, Map<String, List<String>> enums) { return false; }
        default boolean requiresField() { return false; }
        default boolean needsBinding() { return true; }
        default boolean requiresVersion11() { return false; }
    }

    private static final Map<String, Handler> HANDLERS = register();

    private AngularComponentHandlers() { }

    static Handler find(String type) { return HANDLERS.get(type); }

    private static Map<String, Handler> register() {
        Map<String, Handler> handlers = new LinkedHashMap<>();
        Handler field = (component, spec, fields, path) -> {
            String key = component.path("fieldKey").asText();
            if (!fields.containsKey(key)) throw new GenerationValidationException(List.of(path + ".fieldKey: missing DTO property"));
            return new Field(key, component.path("label").asText());
        };
        handlers.put("text-field", field("field", field, (spec, property) ->
                "String".equals(spec.javaType()) && "string".equals(property.get("type")) && refName(property).isEmpty()));
        handlers.put("select", field("select", field, (spec, property) ->
                spec.enumType() != null && spec.enumType().equals(refName(property))));
        handlers.put("date-field", field("date-field", field, (spec, property) ->
                "LocalDate".equals(spec.javaType()) && "date".equals(property.get("format"))));
        handlers.put("table", new Handler() {
            public Details prepare(JsonNode component, AngularSpec spec, Map<String, FieldSpec> fields, String path) {
                List<Column> columns = new ArrayList<>();
                if (component.has("tableConfig")) {
                    for (JsonNode column : component.path("tableConfig").path("columns"))
                        columns.add(new Column(column.path("fieldKey").asText(), column.path("label").asText()));
                } else {
                    UUID id = UUID.fromString(component.path("id").asText());
                    for (String key : spec.tableColumns().get(id)) columns.add(new Column(key, key));
                }
                return new Table(List.copyOf(columns));
            }
            public String template() { return templatePath("table"); }
            public void validate(JsonNode component, String path, ValidationContext context, List<String> errors) {
                UUID id;
                try { id = UUID.fromString(component.path("id").asText()); }
                catch (Exception ex) { errors.add(path + ".id: invalid UUID"); return; }
                if (component.has("tableConfig")) {
                    if (context.spec().tableColumns().containsKey(id))
                        errors.add(path + " (" + id + ").tableConfig.columns: conflicts with specification.angular.tableColumns." + id);
                    JsonNode columns = component.path("tableConfig").path("columns");
                    Set<String> seen = new java.util.HashSet<>();
                    for (int j = 0; j < columns.size(); j++) {
                        String column = columns.get(j).path("fieldKey").asText();
                        if (!validColumn(column, context.dtoProperties()) || !seen.add(column))
                            errors.add(path + ".tableConfig.columns[" + j + "].fieldKey: absent from DTO, invalid or duplicate property");
                    }
                } else {
                    context.legacyTables().add(id);
                    List<String> columns = context.spec().tableColumns().get(id);
                    if (columns == null || columns.isEmpty() || columns.stream().anyMatch(java.util.Objects::isNull)
                            || new java.util.HashSet<>(columns).size() != columns.size())
                        errors.add(path + ".tableColumns: nonempty distinct columns required");
                    else for (int j = 0; j < columns.size(); j++)
                        if (!validColumn(columns.get(j), context.dtoProperties()))
                            errors.add(path + ".tableColumns[" + j + "]: absent from DTO or invalid property");
                }
                if (!context.selectedListComponents().contains(id.toString()))
                    errors.add(path + ": table requires selected list binding");
            }
        });
        handlers.put("form", page("form", (component, spec, fields, path) ->
                new Form(component.path("label").asText(), component.path("formConfig").path("submitLabel").asText())));
        handlers.put("navigation", page("navigation", (component, spec, fields, path) -> {
            List<Item> items = new ArrayList<>();
            for (JsonNode item : component.path("navigationConfig").path("items"))
                items.add(new Item(item.path("label").asText(), item.path("route").asText()));
            return new Navigation(List.copyOf(items));
        }));
        handlers.put("modal", page("modal", (component, spec, fields, path) -> new Modal(component.path("label").asText())));
        handlers.put("pagination", new Handler() {
            public Details prepare(JsonNode component, AngularSpec spec, Map<String, FieldSpec> fields, String path) {
                return new Pagination(component.path("paginationConfig").path("pageSize").intValue());
            }
            public String template() { return templatePath("pagination"); }
            public boolean needsBinding() { return false; }
            public boolean requiresVersion11() { return true; }
            public void validate(JsonNode component, String path, ValidationContext context, List<String> errors) {
                JsonNode pageSize = component.path("paginationConfig").path("pageSize");
                if (!pageSize.canConvertToInt() || pageSize.intValue() < 1)
                    errors.add(path + " (" + component.path("id").asText() + ").paginationConfig.pageSize: expected positive 32-bit integer");
            }
        });
        return Map.copyOf(handlers);
    }

    private static String templatePath(String name) { return "/templates/freemarker/angular/" + name + ".ftl"; }

    private static Handler field(String name, Handler delegate, BiPredicate<FieldSpec, Map<String, Object>> compatible) {
        return new Handler() {
            public Details prepare(JsonNode component, AngularSpec spec, Map<String, FieldSpec> fields, String path) {
                return delegate.prepare(component, spec, fields, path);
            }
            public String template() { return templatePath(name); }
            public boolean validField(FieldSpec field, Map<String, Object> property, Map<String, List<String>> enums) {
                return compatible.test(field, property) &&
                        (field.enumType() == null || !name.equals("select") || enums.containsKey(field.enumType()));
            }
            public boolean requiresField() { return true; }
        };
    }

    private static String refName(Map<String, Object> property) {
        Object ref = property.get("$ref");
        return ref instanceof String text ? text.substring(text.lastIndexOf('/') + 1) : "";
    }

    private static boolean validColumn(String column, Map<String, Object> dto) {
        return column != null && column.matches("[a-z][A-Za-z0-9]*") && dto.containsKey(column);
    }

    private static Handler page(String name, Handler delegate) {
        return new Handler() {
            public Details prepare(JsonNode component, AngularSpec spec, Map<String, FieldSpec> fields, String path) {
                return delegate.prepare(component, spec, fields, path);
            }
            public boolean needsBinding() { return false; }
            public boolean requiresVersion11() { return true; }
            public String template() { return templatePath(name); }
        };
    }
}
