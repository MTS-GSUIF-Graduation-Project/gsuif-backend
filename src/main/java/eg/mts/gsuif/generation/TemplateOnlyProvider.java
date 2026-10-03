package eg.mts.gsuif.generation;

import com.fasterxml.jackson.databind.JsonNode;
import eg.mts.gsuif.generation.GenerationContext.Target;
import eg.mts.gsuif.generation.GenerationSpecification.FieldSpec;
import eg.mts.gsuif.generation.GenerationSpecification.SupportedRole;
import freemarker.template.Configuration;
import freemarker.template.TemplateExceptionHandler;

import java.io.InputStream;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Comparator;
import org.yaml.snakeyaml.Yaml;

/** Phase 1 provider. Rendering is fully buffered; callers own publication and run persistence. */
public final class TemplateOnlyProvider implements AICodeGenerationProvider {
    private static final List<String> SUPPORT = List.of(
            "eg/mts/gsuif/dto/ApiResponse.java", "eg/mts/gsuif/dto/PagedBody.java",
            "eg/mts/gsuif/entity/AuditableEntity.java", "eg/mts/gsuif/audit/AuditorAwareImpl.java",
            "eg/mts/gsuif/config/JpaAuditingConfig.java");
    private final GenerationContextBuilder builder;
    private final Configuration templates = new Configuration(Configuration.VERSION_2_3_34);

    public TemplateOnlyProvider(GenerationContextBuilder builder) {
        this.builder = builder;
        templates.setClassForTemplateLoading(getClass(), "/");
        templates.setDefaultEncoding("UTF-8");
        templates.setTemplateExceptionHandler(TemplateExceptionHandler.RETHROW_HANDLER);
        templates.setLogTemplateExceptions(false);
    }

    @Override public GenerationResult generate(GenerationContext supplied) {
        if (supplied == null) throw new GenerationValidationException(List.of("context: required"));
        GenerationContext context = builder.build(supplied.metadataVersion(), supplied.specification(), supplied.targets(), supplied.framework());
        GenerationCatalog catalog = GenerationCatalog.load();
        GenerationSpecification spec = context.specification();
        Map<String, Object> model = new LinkedHashMap<>(context.templateModel());
        Map<String, Object> api = new LinkedHashMap<>((Map<String, Object>) context.templateModel().get("api"));
        Map<String, String> expressions = new HashMap<>();
        for (String key : context.targets().contains(Target.CONTROLLER)
                ? List.of("createMethod", "getMethod", "listMethod", "updateMethod", "deleteMethod") : List.<String>of()) {
            String operation = (String) api.get(key);
            List<SupportedRole> roles = spec.operationRoles().get(operation).stream().sorted(Comparator.comparing(Enum::name)).toList();
            if (roles == null || roles.isEmpty()) throw new GenerationValidationException(List.of("specification.operationRoles." + operation + ": required"));
            expressions.put(key, roles.size() == 1 ? "hasAuthority('" + roles.get(0) + "')" :
                    "hasAnyAuthority('" + roles.get(0) + "','" + roles.get(1) + "')");
        }
        api.put("operationRoles", expressions);
        model.put("api", api);
        String root = "src/main/java/" + spec.basePackage().replace('.', '/') + "/";
        List<GenerationResult.Artifact> pending = new ArrayList<>();
        try {
            if (context.targets().contains(Target.ENTITY))
                pending.add(artifact(root + "entity/" + spec.entity().className() + ".java", render(catalog.entity().path(), model), catalog.entity().version(), catalog.version()));
            if (context.targets().contains(Target.CONTROLLER)) {
                pending.add(artifact(root + "controller/" + spec.entity().className() + "Controller.java", render(catalog.controller().path(), model), catalog.controller().version(), catalog.version()));
                for (String source : SUPPORT) pending.add(artifact("src/main/java/" + source, resource("/consumer-support/" + source), catalog.providerVersion(), catalog.version()));
            }
            if (context.targets().contains(Target.ANGULAR)) renderAngular(context, pending, catalog);
        } catch (GenerationValidationException ex) { throw ex; }
        catch (Exception ex) { throw new GenerationValidationException(List.of("render: " + ex.getMessage())); }
        return new GenerationResult(pending, List.of());
    }

    private void renderAngular(GenerationContext context, List<GenerationResult.Artifact> output, GenerationCatalog catalog) throws Exception {
        GenerationSpecification s = context.specification();
        JsonNode components = context.snapshot().path("components");
        StringBuilder ts = new StringBuilder("import { Component } from '@angular/core';\nimport { CommonModule } from '@angular/common';\nimport { FormsModule } from '@angular/forms';\n\nexport interface " + s.angular().className() + "Dto {\n");
        StringBuilder html = new StringBuilder();
        Map<String, FieldSpec> fields = new HashMap<>();
        for (var f : s.entity().fields()) fields.put(f.name(), f);
        for (int i = 0; i < components.size(); i++) {
            JsonNode c = components.get(i);
            String type = c.path("type").asText();
            String key = c.path("fieldKey").asText();
            if (!List.of("text-field", "select", "date-field", "table").contains(type))
                throw new GenerationValidationException(List.of("snapshot.components[" + i + "].type: unsupported Angular component"));
            if ("table".equals(type)) {
                html.append("<table><thead><tr>");
                java.util.UUID tableId = java.util.UUID.fromString(c.path("id").asText());
                List<String> columns = s.angular().tableColumns().get(tableId);
                for (String column : columns) html.append("<th>" + escapeHtml(column) + "</th>");
                html.append("</tr></thead><tbody><tr *ngFor=\"let row of rows\">");
                for (String column : columns) html.append("<td>{{ row." + column + " }}</td>");
                html.append("</tr></tbody></table>\n");
                continue;
            }
            FieldSpec f = fields.get(key);
            if (f == null) throw new GenerationValidationException(List.of("snapshot.components[" + i + "].fieldKey: missing DTO property"));
            String expected = "date-field".equals(type) ? "LocalDate" : "select".equals(type) ? f.enumType() : "String";
            if (expected == null || !expected.equals(f.javaType())) throw new GenerationValidationException(List.of("snapshot.components[" + i + "].fieldKey: incompatible DTO type"));
            String label = escapeHtml(c.path("label").asText());
            if ("select".equals(type)) html.append("<label>" + label + "<select [(ngModel)]=\"model." + key + "\"><option *ngFor=\"let option of options." + key + "\" [value]=\"option\">{{ option }}</option></select></label>\n");
            else html.append("<label>" + label + "<input type=\"" + ("date-field".equals(type) ? "date" : "text") + "\" [(ngModel)]=\"model." + key + "\"></label>\n");
        }
        @SuppressWarnings("unchecked")
        Map<String, Map<String, Object>> dtoProperties = (Map<String, Map<String, Object>>) ((Map<String, Object>) context.templateModel().get("api")).get("dtoProperties");
        @SuppressWarnings("unchecked")
        List<String> dtoRequired = (List<String>) ((Map<String, Object>) context.templateModel().get("api")).get("dtoRequired");
        for (var property : dtoProperties.entrySet()) {
            String name = property.getKey(); Map<String, Object> definition = property.getValue();
            FieldSpec field = fields.get(name);
            String tsType = definition.containsKey("$ref") || "string".equals(definition.get("type")) ? "string"
                    : "boolean".equals(definition.get("type")) ? "boolean" : "number";
            ts.append("  " + name + (dtoRequired.contains(name) ? ": " : "?: ") + tsType + ";\n");
        }
        String kebab = s.angular().className().replaceAll("([a-z])([A-Z])", "$1-$2").toLowerCase(java.util.Locale.ROOT);
        ts.append("}\n@Component({ selector: 'app-generated-" + kebab + "', standalone: true, imports: [CommonModule, FormsModule], templateUrl: './" + kebab + ".component.html' })\n");
        ts.append("export class " + s.angular().className() + "Component {\n  model = {} as " + s.angular().className() + "Dto;\n  rows: " + s.angular().className() + "Dto[] = [];\n  options: Record<string, string[]> = {");
        Map<String, List<String>> options = enumOptions(s.openApi().contractId());
        for (var field : s.entity().fields()) if (field.enumType() != null) {
            List<String> values = options.get(field.enumType());
            if (values == null || values.isEmpty()) throw new GenerationValidationException(List.of("specification.fields." + field.name() + ": enum options absent from contract"));
            ts.append("\n    " + field.name() + ": [");
            for (int i = 0; i < values.size(); i++) ts.append((i == 0 ? "" : ", ") + "'" + values.get(i) + "'");
            ts.append("],");
        }
        ts.append("\n  };\n}\n");
        String base = "src/app/generated/" + kebab;
        output.add(artifact(base + ".component.ts", ts.toString(), catalog.providerVersion(), catalog.version()));
        output.add(artifact(base + ".component.html", html.toString(), catalog.providerVersion(), catalog.version()));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, List<String>> enumOptions(String contractId) throws Exception {
        String path = switch (contractId) {
            case "asset-ticket-api" -> "/openapi/asset-ticket-api.yaml";
            case "service-request-api" -> "/openapi/service-request-api.yaml";
            default -> throw new IllegalArgumentException("unsupported contract");
        };
        try (InputStream in = TemplateOnlyProvider.class.getResourceAsStream(path)) {
            Map<String, Object> root = new Yaml().load(in);
            Map<String, Object> schemas = (Map<String, Object>) ((Map<String, Object>) root.get("components")).get("schemas");
            Map<String, List<String>> result = new HashMap<>();
            for (var entry : schemas.entrySet()) {
                Map<String, Object> schema = (Map<String, Object>) entry.getValue();
                if (schema.get("enum") instanceof List<?> values)
                    result.put(entry.getKey(), values.stream().map(String::valueOf).toList());
            }
            return result;
        }
    }

    private static String escapeHtml(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&#39;");
    }

    private String render(String path, Map<String, Object> model) throws Exception {
        StringWriter writer = new StringWriter();
        templates.getTemplate(path).process(model, writer);
        return writer.toString();
    }

    private static String resource(String path) throws Exception {
        try (InputStream in = TemplateOnlyProvider.class.getResourceAsStream(path)) {
            if (in == null) throw new IllegalStateException("missing support source: " + path);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static GenerationResult.Artifact artifact(String path, String content, String templateVersion, String catalogVersion) throws Exception {
        if (path.startsWith("/") || path.contains("..") || path.contains("\\") || path.contains(":"))
            throw new GenerationValidationException(List.of("artifact.relativePath: unsafe path"));
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
        return new GenerationResult.Artifact(path, bytes, java.util.HexFormat.of().formatHex(digest), templateVersion, catalogVersion);
    }
}
