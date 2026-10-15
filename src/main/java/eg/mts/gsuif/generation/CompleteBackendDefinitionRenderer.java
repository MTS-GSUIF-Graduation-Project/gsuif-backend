package eg.mts.gsuif.generation;

import eg.mts.gsuif.generation.GenerationCapabilityPreflight.OutputMode;
import eg.mts.gsuif.generation.GenerationFilePlan.Owner;
import eg.mts.gsuif.generation.GenerationSpecification.FieldSpec;
import freemarker.template.Configuration;
import freemarker.template.TemplateExceptionHandler;

import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/** Renders only the required backend definitions; scaffold and controller remain later work. */
final class CompleteBackendDefinitionRenderer {
    private final Configuration templates = new Configuration(Configuration.VERSION_2_3_34);

    CompleteBackendDefinitionRenderer() {
        templates.setClassForTemplateLoading(getClass(), "/");
        templates.setDefaultEncoding(StandardCharsets.UTF_8.name());
        templates.setTemplateExceptionHandler(TemplateExceptionHandler.RETHROW_HANDLER);
        templates.setLogTemplateExceptions(false);
    }

    static final class Rendered {
        private final Map<String, byte[]> files;
        private final RenderedModelHandoff handoff;

        private Rendered(Map<String, byte[]> files, RenderedModelHandoff handoff) {
            this.files = copy(files);
            this.handoff = handoff;
        }

        Map<String, byte[]> files() { return copy(files); }
        RenderedModelHandoff handoff() { return handoff; }
    }

    Rendered render(CompleteOutputDescriptor descriptor, GenerationFilePlan plan) {
        requireBackend(descriptor);
        ModelHandoffSpec specification = ModelHandoffSpec.from(descriptor, plan);
        String root = root(descriptor);
        String entity = descriptor.entityName();
        Map<String, byte[]> files = new TreeMap<>();
        put(files, root + "entity/AuditableEntity.java", renderTemplate("auditable-entity.java.ftl",
                Map.of("packageName", descriptor.basePackage())));
        put(files, root + "entity/" + entity + ".java", entitySource(descriptor));
        put(files, root + "repository/" + entity + "Repository.java", repositorySource(descriptor));
        put(files, root + "service/" + descriptor.contractServiceName() + ".java", serviceSource(descriptor));
        put(files, root + "api/ApiResponse.java", renderTemplate("api-response.java.ftl",
                Map.of("packageName", descriptor.basePackage())));
        put(files, root + "dto/" + descriptor.createRequestName() + ".java",
                dtoSource(descriptor, descriptor.createRequestName(), descriptor.createFields(), descriptor.createRules()));
        put(files, root + "dto/" + descriptor.updateRequestName() + ".java",
                dtoSource(descriptor, descriptor.updateRequestName(), descriptor.editableFields(), descriptor.updateRules()));
        put(files, root + "dto/" + descriptor.dtoName() + ".java", responseDtoSource(descriptor));
        put(files, root + "dto/" + descriptor.pageDtoName() + ".java", pageSource(descriptor));
        for (String name : new TreeSet<>(GenerationFilePlan.enumNames(descriptor)))
            put(files, root + "dto/" + name + ".java", enumSource(descriptor, name));
        return validateDefinitions(descriptor, plan, specification, files);
    }

    /** Deliberately narrower than full GenerationFilePlan completeness. */
    static Set<String> requiredDefinitionPaths(CompleteOutputDescriptor descriptor, ModelHandoffSpec handoff) {
        requireBackend(descriptor);
        Set<String> required = new TreeSet<>();
        for (ModelHandoffSpec.Definition definition : handoff.definitions()) required.add(definition.path());
        return Collections.unmodifiableSet(required);
    }

    static Rendered validateDefinitions(CompleteOutputDescriptor descriptor, GenerationFilePlan plan,
                                        ModelHandoffSpec handoff, Map<String, byte[]> emitted) {
        if (!ModelHandoffSpec.from(descriptor, plan).equals(handoff))
            throw new IllegalArgumentException("Backend handoff differs from selected descriptor and file plan");
        Set<String> required = requiredDefinitionPaths(descriptor, handoff);
        Map<String, Owner> planned = new HashMap<>();
        for (GenerationFilePlan.Entry entry : plan.entries()) planned.put(entry.path(), entry.owner());
        for (String path : required)
            if (planned.get(path) != Owner.DETERMINISTIC)
                throw new IllegalArgumentException("Required backend definition is not deterministic-owned: " + path);
        if (!required.equals(emitted.keySet()))
            throw new IllegalArgumentException("Backend definition output differs from required subset");
        for (var entry : emitted.entrySet()) {
            GenerationArtifactStore.safeRelative(entry.getKey());
            if (entry.getValue() == null) throw new IllegalArgumentException("Missing backend definition bytes");
        }
        Map<String, byte[]> immutable = copy(emitted);
        return new Rendered(immutable, RenderedModelHandoff.from(handoff, immutable));
    }

    private String entitySource(CompleteOutputDescriptor d) {
        List<Map<String, Object>> fields = new ArrayList<>();
        for (FieldSpec field : d.fields()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("name", field.name()); item.put("columnName", field.columnName());
            item.put("javaType", field.javaType()); item.put("nullable", field.nullable());
            if (field.columnLength() != null) item.put("columnLength", field.columnLength());
            if (field.enumType() != null) item.put("enumType", field.enumType());
            fields.add(item);
        }
        Map<String, Object> entity = new LinkedHashMap<>();
        entity.put("className", d.entityName()); entity.put("tableName", d.tableName());
        entity.put("auditBasePackage", d.basePackage() + ".entity");
        entity.put("enumPackage", d.basePackage() + ".dto");
        entity.put("explicitVarcharEnumMapping", true);
        entity.put("explicitAuditInheritedFields", true);
        entity.put("enversAudited", d.enversAudited()); entity.put("fields", fields);
        try {
            StringWriter writer = new StringWriter();
            templates.getTemplate("templates/freemarker/entity.ftl")
                    .process(Map.of("project", Map.of("basePackage", d.basePackage()), "entity", entity), writer);
            return writer.toString();
        } catch (Exception ex) { throw new IllegalArgumentException("Could not render backend entity definition", ex); }
    }

    private String dtoSource(CompleteOutputDescriptor d, String name,
                                    Map<String, PackagedContractLoader.FieldSchema> schemas,
                                    Map<String, PackagedContractLoader.UpdateField> rules) {
        if (schemas.isEmpty() || !schemas.keySet().equals(rules.keySet()))
            throw new IllegalArgumentException("Request definition fields disagree with selected contract");
        List<Map<String, Object>> members = new ArrayList<>();
        for (String field : new TreeSet<>(schemas.keySet())) {
            var schema = schemas.get(field);
            var rule = rules.get(field);
            if (rule.nullable() != schema.nullable())
                throw new IllegalArgumentException("Request definition nullability differs from selected contract");
            List<String> annotations = new ArrayList<>();
            annotations.add("@com.fasterxml.jackson.annotation.JsonProperty(value = \"" + field
                    + "\", required = " + rule.required() + ")");
            if (!schema.nullable()) annotations.add("@jakarta.validation.constraints.NotNull");
            members.add(member(javaType(schema), field, annotations));
        }
        return recordSource(d, name, members);
    }

    private String responseDtoSource(CompleteOutputDescriptor d) {
        Set<String> expected = new HashSet<>(Set.of("id"));
        d.fields().forEach(field -> expected.add(field.name()));
        if (!expected.equals(d.responseFields().keySet()))
            throw new IllegalArgumentException("Response DTO fields disagree with entity definition");
        var id = d.responseFields().get("id");
        if (!"string".equals(id.type()) || !"uuid".equals(id.format()) || id.nullable())
            throw new IllegalArgumentException("Response DTO requires a nonnullable UUID id");
        List<Map<String, Object>> members = new ArrayList<>();
        members.add(member("java.util.UUID", "id", List.of()));
        for (FieldSpec field : d.fields())
            members.add(member(javaType(d.responseFields().get(field.name())), field.name(), List.of()));
        return recordSource(d, d.dtoName(), members);
    }

    private String pageSource(CompleteOutputDescriptor d) {
        return recordSource(d, d.pageDtoName(), List.of(
                member("java.util.List<" + d.dtoName() + ">", "data", List.of()),
                member("int", "totalPages", List.of()),
                member("long", "totalElements", List.of()),
                member("int", "size", List.of()),
                member("int", "number", List.of())));
    }

    private String enumSource(CompleteOutputDescriptor d, String name) {
        Set<List<String>> candidates = new HashSet<>();
        d.editableFields().values().forEach(schema -> { if (name.equals(schema.enumName())) candidates.add(schema.values()); });
        d.createFields().values().forEach(schema -> { if (name.equals(schema.enumName())) candidates.add(schema.values()); });
        d.responseFields().values().forEach(schema -> { if (name.equals(schema.enumName())) candidates.add(schema.values()); });
        d.operations().forEach(op -> op.queryFields().values().forEach(parameter -> {
            var schema = parameter.schema();
            if (name.equals(schema.enumName())) candidates.add(schema.values());
        }));
        if (candidates.size() != 1 || candidates.iterator().next().isEmpty())
            throw new IllegalArgumentException("Enum values missing or inconsistent for " + name);
        List<String> values = candidates.iterator().next();
        if (values.stream().anyMatch(value -> !value.matches("[A-Z][A-Z0-9_]*")))
            throw new IllegalArgumentException("Unsupported enum identifier for " + name);
        return renderTemplate("enum.java.ftl", Map.of("packageName", d.basePackage() + ".dto",
                "name", name, "values", values));
    }

    private String repositorySource(CompleteOutputDescriptor d) {
        return renderTemplate("repository.java.ftl", Map.of("packageName", d.basePackage(),
                "entityName", d.entityName()));
    }

    private String serviceSource(CompleteOutputDescriptor d) {
        CompleteOutputDescriptor.Operation list = d.operations().stream()
                .filter(op -> op.action() == PackagedContractRegistry.CrudAction.LIST).findFirst().orElseThrow();
        List<Map<String, Object>> parameters = new ArrayList<>();
        list.queryFields().forEach((name, parameter) -> {
            String type = javaType(parameter.schema());
            if (!parameter.schema().enumName().isEmpty()) type = d.basePackage() + ".dto." + type;
            parameters.add(member(type, name, List.of()));
        });
        return renderTemplate("service.java.ftl", Map.of("packageName", d.basePackage(),
                "dtoPackage", d.basePackage() + ".dto", "serviceName", d.contractServiceName(),
                "dtoName", d.dtoName(), "createName", d.createRequestName(), "pageName", d.pageDtoName(),
                "updateName", d.updateRequestName(), "listParameters", parameters));
    }

    private String recordSource(CompleteOutputDescriptor d, String name, List<Map<String, Object>> members) {
        return renderTemplate("record.java.ftl", Map.of("packageName", d.basePackage() + ".dto",
                "name", name, "members", members));
    }

    private String renderTemplate(String name, Map<String, Object> model) {
        try {
            StringWriter writer = new StringWriter();
            templates.getTemplate("templates/complete-backend/" + name).process(model, writer);
            return writer.toString();
        } catch (Exception ex) { throw new IllegalArgumentException("Could not render backend definition template: " + name, ex); }
    }

    private static Map<String, Object> member(String type, String name, List<String> annotations) {
        return Map.of("type", type, "name", name, "annotations", List.copyOf(annotations));
    }

    private static String javaType(PackagedContractLoader.FieldSchema schema) {
        if (schema == null) throw new IllegalArgumentException("Missing selected field schema");
        if (!schema.enumName().isEmpty()) return schema.enumName();
        return switch (schema.type() + ":" + schema.format()) {
            case "string:" -> "String";
            case "string:uuid" -> "java.util.UUID";
            case "string:date" -> "java.time.LocalDate";
            case "string:date-time" -> "java.time.LocalDateTime";
            case "integer:int32" -> "Integer";
            case "integer:int64" -> "Long";
            case "number:" -> "java.math.BigDecimal";
            case "boolean:" -> "Boolean";
            default -> throw new IllegalArgumentException("Unsupported selected scalar field schema");
        };
    }

    private static String root(CompleteOutputDescriptor descriptor) {
        return "backend/src/main/java/" + descriptor.basePackage().replace('.', '/') + "/";
    }

    private static void requireBackend(CompleteOutputDescriptor descriptor) {
        if (descriptor == null || descriptor.mode() == OutputMode.FRONTEND)
            throw new IllegalArgumentException("Backend definitions require BACKEND or FULL_STACK mode");
    }

    private static void put(Map<String, byte[]> files, String path, String source) {
        String canonical = source.replace("\r\n", "\n").replace('\r', '\n');
        if (files.putIfAbsent(path, canonical.getBytes(StandardCharsets.UTF_8)) != null)
            throw new IllegalArgumentException("Duplicate backend definition path: " + path);
    }

    private static Map<String, byte[]> copy(Map<String, byte[]> files) {
        Map<String, byte[]> frozen = new TreeMap<>();
        files.forEach((path, bytes) -> frozen.put(path, bytes.clone()));
        return Collections.unmodifiableMap(frozen);
    }
}
