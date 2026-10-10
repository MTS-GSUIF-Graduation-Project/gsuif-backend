package eg.mts.gsuif.generation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import eg.mts.gsuif.entity.MetadataVersion;
import eg.mts.gsuif.generation.GenerationContext.Target;
import eg.mts.gsuif.generation.GenerationSpecification.*;
import eg.mts.gsuif.validator.MetadataSchemaValidator;
import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.util.*;

/** Resolves only packaged contracts and immutable metadata snapshots. */
public final class GenerationContextBuilder {
    private static final Map<String, String> CONTRACTS = Map.of(
            "asset-ticket-api", "/openapi/asset-ticket-api.yaml",
            "service-request-api", "/openapi/service-request-api.yaml");
    private static final Set<String> TYPES = Set.of("String", "UUID", "LocalDate", "LocalDateTime", "Integer", "Long", "BigDecimal", "Boolean");
    private static final Set<String> RESERVED = Set.of("abstract", "assert", "boolean", "break", "byte", "case", "catch", "char", "class", "const", "continue", "default", "do", "double", "else", "enum", "extends", "final", "finally", "float", "for", "goto", "if", "implements", "import", "instanceof", "int", "interface", "long", "native", "new", "package", "private", "protected", "public", "return", "short", "static", "strictfp", "super", "switch", "synchronized", "this", "throw", "throws", "transient", "try", "void", "volatile", "while", "true", "false", "null", "var", "yield", "record", "sealed", "permits");
    private final ObjectMapper mapper;
    private final MetadataSchemaValidator schemaValidator;

    public GenerationContextBuilder(ObjectMapper mapper, MetadataSchemaValidator schemaValidator) {
        this.mapper = Objects.requireNonNull(mapper);
        this.schemaValidator = Objects.requireNonNull(schemaValidator);
    }

    public GenerationContext build(MetadataVersion version, GenerationSpecification spec, Set<Target> targets, String framework) {
        List<String> errors = new ArrayList<>();
        JsonNode snapshot = null;
        if (version == null) errors.add("metadataVersion: required");
        else {
            try { snapshot = mapper.readTree(version.getSnapshot()); }
            catch (Exception ex) { errors.add("metadataVersion.snapshot: invalid JSON"); }
            schemaValidator.validate(version.getSchemaVersion(), snapshot).forEach(e -> errors.add(e.path() + ": " + e.message()));
            if (version.getPage() == null || version.getPage().getProject() == null || version.getPage().getProject().getName() == null)
                errors.add("metadataVersion.page.project: required");
        }
        if (targets == null || targets.isEmpty() || targets.stream().anyMatch(Objects::isNull)) errors.add("context.targets: expected supported nonempty targets");
        if (!"spring-angular".equals(framework)) errors.add("context.framework: expected spring-angular");
        if (spec == null) errors.add("specification: required");
        Map<String, Object> api = spec == null ? Map.of() : validateSpecification(spec, snapshot, targets, errors);
        if (!errors.isEmpty()) throw new GenerationValidationException(errors);

        Map<String, Object> entity = new LinkedHashMap<>();
        entity.put("className", spec.entity().className()); entity.put("tableName", spec.entity().tableName());
        entity.put("auditBasePackage", "eg.mts.gsuif.entity"); entity.put("enversAudited", spec.entity().enversAudited());
        entity.put("enumPackage", api.get("dtoPackage"));
        List<Map<String, Object>> fields = new ArrayList<>();
        for (FieldSpec field : spec.entity().fields()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("name", field.name()); item.put("columnName", field.columnName());
            item.put("javaType", field.javaType()); item.put("nullable", field.nullable());
            if (field.columnLength() != null) item.put("columnLength", field.columnLength());
            if (field.enumType() != null) item.put("enumType", field.enumType());
            fields.add(Map.copyOf(item));
        }
        entity.put("fields", List.copyOf(fields));
        Map<String, Object> model = Map.of("project", Map.of("basePackage", spec.basePackage(), "name", version.getPage().getProject().getName()),
                "entity", Map.copyOf(entity), "api", api);
        GenerationCatalog catalog = GenerationCatalog.load();
        return new GenerationContext(version, snapshot.deepCopy(), spec, Set.copyOf(targets), framework, model, catalog);
    }

    public GenerationContext build(MetadataVersion version, GenerationSpecification spec) {
        return build(version, spec, EnumSet.allOf(Target.class), "spring-angular");
    }

    private Map<String, Object> validateSpecification(GenerationSpecification s, JsonNode snapshot, Set<Target> targets, List<String> errors) {
        if (!"1.0.0".equals(s.specVersion())) errors.add("specification.specVersion: supported version is 1.0.0");
        if (!identifier(s.basePackage(), "[a-z][a-z0-9_]*(\\.[a-z][a-z0-9_]*)+")) errors.add("specification.basePackage: invalid Java package");
        EntitySpec entity = s.entity();
        if (entity == null) errors.add("specification.entity: required");
        else {
            if (!identifier(entity.className(), "[A-Z][A-Za-z0-9]*")) errors.add("specification.entity.className: invalid class name");
            if (!identifier(entity.tableName(), "[a-z][a-z0-9_]*")) errors.add("specification.entity.tableName: invalid table name");
            if (entity.fields() == null) errors.add("specification.entity.fields: required");
            else {
                Set<String> names = new HashSet<>(Set.of("id", "createdAt", "updatedAt", "createdBy", "lastModifiedBy"));
                Set<String> columns = new HashSet<>(Set.of("id", "created_at", "updated_at", "created_by", "last_modified_by"));
                for (int i = 0; i < entity.fields().size(); i++) {
                    FieldSpec f = entity.fields().get(i);
                    String p = "specification.entity.fields[" + i + "]";
                    if (f == null) { errors.add(p + ": required"); continue; }
                    if (!identifier(f.name(), "[a-z][A-Za-z0-9]*") || !names.add(f.name())) errors.add(p + ".name: invalid or duplicate");
                    if (!identifier(f.columnName(), "[a-z][a-z0-9_]*") || !columns.add(f.columnName())) errors.add(p + ".columnName: invalid or duplicate");
                    if (f.javaType() == null || !(TYPES.contains(f.javaType()) || f.javaType().equals(f.enumType()) && identifier(f.enumType(), "[A-Z][A-Za-z0-9]*"))) errors.add(p + ".javaType: unsupported type");
                    if (f.enumType() != null && (!f.enumType().equals(f.javaType()) || TYPES.contains(f.enumType()))) errors.add(p + ".enumType: invalid enum");
                    if (f.columnLength() != null && (f.columnLength() < 1 || !"String".equals(f.javaType()))) errors.add(p + ".columnLength: requires positive String length");
                }
            }
        }
        OpenApiSpec openApi = s.openApi();
        if (openApi == null) { errors.add("specification.openApi: required"); return Map.of(); }
        if (openApi.contractId() == null || !CONTRACTS.containsKey(openApi.contractId())) errors.add("specification.openApi.contractId: unsupported packaged contract");
        Contract contract = loadContract(openApi, errors);
        Map<String, Object> api = contract == null ? Map.of() : contract.api();
        Map<String, Operation> operations = contract == null ? Map.of() : contract.operations();
        if (contract != null && entity != null && entity.fields() != null) {
            for (int i = 0; i < entity.fields().size(); i++) {
                FieldSpec field = entity.fields().get(i);
                if (field != null && field.enumType() != null && !contract.enums().containsKey(field.enumType()))
                    errors.add("specification.entity.fields[" + i + "].enumType: absent from selected packaged contract");
            }
        }
        if (entity != null && api.get("interfaceClass") != null && !api.get("interfaceClass").equals(entity.className() + "Api"))
            errors.add("specification.entity.className: does not match packaged API tag");
        Map<UUID, String> selected = openApi.bindingOperations();
        if (selected == null) errors.add("specification.openApi.bindingOperations: required");
        else if (snapshot != null && snapshot.path("apiBindings").isArray()) {
            Set<UUID> seenIds = new HashSet<>(); Set<String> seenOperations = new HashSet<>();
            JsonNode bindings = snapshot.path("apiBindings");
            for (int i = 0; i < bindings.size(); i++) {
                JsonNode b = bindings.get(i); String p = "specification.openApi.bindingOperations[" + i + "]";
                UUID id;
                try { id = UUID.fromString(b.path("id").asText()); } catch (Exception ex) { errors.add(p + ": invalid binding UUID"); continue; }
                if (!selected.containsKey(id)) continue;
                if (!seenIds.add(id)) errors.add(p + ": duplicate binding ID");
                String operationId = selected.get(id);
                Operation operation = operationId == null ? null : operations.get(operationId);
                if (operation == null || !seenOperations.add(operationId)) { errors.add(p + ": missing, unknown or duplicate operation"); continue; }
                if (!operation.verb().equals(b.path("httpMethod").asText()) || !operation.path().equals(b.path("endpointUrl").asText())) errors.add(p + ": method/path mismatch");
                JsonNode request = b.path("requestMapping"); JsonNode response = b.path("responseMapping");
                if (operation.kind().equals("list") && !"body.data".equals(response.path("list").asText())) errors.add(p + ".responseMapping.list: expected body.data");
                if ((operation.kind().equals("item") || operation.kind().equals("void")) && !"body".equals(response.path("item").asText())) errors.add(p + ".responseMapping.item: expected body");
                if (!operation.request().isEmpty()) {
                    JsonNode body = request.path("body");
                    if (!body.isObject()) errors.add(p + ".requestMapping.body: required");
                    else for (String field : operation.requestFields()) if (!field.equals(body.path(field).asText()))
                        errors.add(p + ".requestMapping.body." + field + ": DTO field mapping required");
                }
                if (operation.path().contains("{id}") && !"id".equals(request.path("path").path("id").asText())) errors.add(p + ".requestMapping.path.id: required");
                if (operation.kind().equals("list") && !"status".equals(request.path("query").path("status").asText())) errors.add(p + ".requestMapping.query.status: required");
            }
            if (!selected.keySet().equals(seenIds)) errors.add("specification.openApi.bindingOperations: unknown binding ID");
            if (targets != null && targets.contains(Target.CONTROLLER) && seenOperations.size() != operations.size()) errors.add("specification.openApi.bindingOperations: all contract operations required");
        }
        if (s.operationRoles() == null) errors.add("specification.operationRoles: required");
        else {
            for (String id : selected == null ? Set.<String>of() : new HashSet<>(selected.values())) {
                if (id == null || id.isBlank()) { errors.add("specification.operationRoles: blank operation ID"); continue; }
                Set<SupportedRole> roles = s.operationRoles().get(id);
                if (roles == null || roles.isEmpty() || roles.stream().anyMatch(Objects::isNull)) errors.add("specification.operationRoles." + id + ": nonempty ROLE_ADMIN and/or ROLE_USER required");
            }
            for (String id : s.operationRoles().keySet()) if (id == null || !operations.containsKey(id) || selected == null || !selected.containsValue(id)) errors.add("specification.operationRoles." + id + ": unselected operation");
        }
        if (s.angular() == null) errors.add("specification.angular: required");
        else {
            if (!identifier(s.angular().className(), "[A-Z][A-Za-z0-9]*")) errors.add("specification.angular.className: invalid class name");
            if (s.angular().tableColumns() == null) errors.add("specification.angular.tableColumns: required");
            if (snapshot != null && targets != null && targets.contains(Target.ANGULAR)) validateAngular(s, snapshot, contract, errors);
        }
        return api;
    }

    private void validateAngular(GenerationSpecification s, JsonNode snapshot, Contract contract, List<String> errors) {
        if (contract == null || s.entity() == null || s.entity().fields() == null || s.angular().tableColumns() == null) return;
        Map<String, FieldSpec> fields = new HashMap<>();
        for (FieldSpec field : s.entity().fields()) if (field != null && field.name() != null) fields.put(field.name(), field);
        Map<String, Object> dto = contract.dtoProperties();
        JsonNode components = snapshot.path("components"); JsonNode bindings = snapshot.path("apiBindings");
        Map<UUID, String> selectedOperations = s.openApi().bindingOperations();
        Set<String> selectedComponents = selectedComponentIds(snapshot, selectedOperations);
        Set<UUID> tables = new HashSet<>();
        for (int i = 0; i < components.size(); i++) {
            JsonNode component = components.get(i); String p = "snapshot.components[" + i + "]";
            if (selectedOperations != null && !selectedComponents.contains(component.path("id").asText())) continue;
            String type = component.path("type").asText(); String key = component.path("fieldKey").asText();
            if (!Set.of("text-field", "select", "date-field", "table").contains(type)) { errors.add(p + ".type: unsupported Angular component"); continue; }
            if (component.has("tableConfig")) {
                errors.add(p + ".tableConfig: configured-table generation is deferred");
                continue;
            }
            boolean selectedLink = false;
            for (JsonNode binding : bindings) {
                UUID bindingId;
                try { bindingId = UUID.fromString(binding.path("id").asText()); } catch (Exception ex) { continue; }
                if (s.openApi().bindingOperations() == null || !s.openApi().bindingOperations().containsKey(bindingId)) continue;
                for (JsonNode linkedId : binding.path("linkedComponentIds"))
                    if (linkedId.asText().equals(component.path("id").asText())) selectedLink = true;
            }
            if (!selectedLink) errors.add(p + ": selected API binding link required");
            if ("table".equals(type)) {
                UUID id;
                try { id = UUID.fromString(component.path("id").asText()); } catch (Exception ex) { errors.add(p + ".id: invalid UUID"); continue; }
                tables.add(id); List<String> columns = s.angular().tableColumns().get(id);
                if (columns == null || columns.isEmpty() || columns.stream().anyMatch(Objects::isNull) || new HashSet<>(columns).size() != columns.size()) errors.add(p + ".tableColumns: nonempty distinct columns required");
                else for (int j = 0; j < columns.size(); j++) if (!identifier(columns.get(j), "[a-z][A-Za-z0-9]*") || !dto.containsKey(columns.get(j))) errors.add(p + ".tableColumns[" + j + "]: absent from DTO or invalid property");
                boolean linked = false;
                for (JsonNode binding : bindings) {
                    if (selectedOperations == null) break;
                    UUID bindingId;
                    try { bindingId = UUID.fromString(binding.path("id").asText()); } catch (Exception ex) { continue; }
                    Operation op = contract.operations().get(selectedOperations.get(bindingId));
                    if (op == null || !op.kind().equals("list")) continue;
                    for (JsonNode linkedId : binding.path("linkedComponentIds")) if (linkedId.asText().equals(id.toString())) linked = true;
                }
                if (!linked) errors.add(p + ": table requires selected list binding");
                continue;
            }
            FieldSpec field = fields.get(key); Map<String, Object> property = nested(dto, key);
            if (field == null || property.isEmpty()) { errors.add(p + ".fieldKey: absent from entity and DTO"); continue; }
            String ref = refName(property.get("$ref"));
            boolean valid = switch (type) {
                case "text-field" -> "String".equals(field.javaType()) && "string".equals(property.get("type")) && ref.isEmpty();
                case "date-field" -> "LocalDate".equals(field.javaType()) && "date".equals(property.get("format"));
                case "select" -> field.enumType() != null && field.enumType().equals(ref) && contract.enums().containsKey(ref);
                default -> false;
            };
            if (!valid) errors.add(p + ".fieldKey: incompatible DTO type");
        }
        for (UUID id : s.angular().tableColumns().keySet()) if (!tables.contains(id)) errors.add("specification.angular.tableColumns." + id + ": unknown table");
        if (selectedOperations != null) for (String id : selectedComponents) {
            boolean present = false;
            for (JsonNode component : components) if (id.equals(component.path("id").asText())) { present = true; break; }
            if (!present) errors.add("snapshot.apiBindings.linkedComponentIds: selected component " + id + " absent from snapshot");
        }
    }

    static Set<String> selectedComponentIds(JsonNode snapshot, Map<UUID, String> selectedOperations) {
        Set<String> ids = new HashSet<>();
        if (selectedOperations == null) return ids;
        for (JsonNode binding : snapshot.path("apiBindings")) {
            UUID id;
            try { id = UUID.fromString(binding.path("id").asText()); } catch (Exception ex) { continue; }
            if (selectedOperations.containsKey(id))
                for (JsonNode linked : binding.path("linkedComponentIds")) ids.add(linked.asText());
        }
        return ids;
    }

    private record Operation(String verb, String path, String kind, String request, Set<String> requestFields) { }
    private record Contract(Map<String, Object> api, Map<String, Operation> operations,
                            Map<String, Object> dtoProperties, Map<String, List<String>> enums) { }

    @SuppressWarnings("unchecked")
    private Contract loadContract(OpenApiSpec spec, List<String> errors) {
        String resource = spec.contractId() == null ? null : CONTRACTS.get(spec.contractId()); if (resource == null) return null;
        try (InputStream in = getClass().getResourceAsStream(resource)) {
            Map<String, Object> root = new Yaml().load(in);
            if (!Objects.equals(spec.contractVersion(), nested(root, "info").get("version"))) errors.add("specification.openApi.contractVersion: packaged version mismatch");
            Map<String, Object> paths = nested(root, "paths"); Map<String, Object> schemas = nested(root, "components", "schemas");
            Map<String, Operation> operations = new LinkedHashMap<>(); Map<String, String> methods = new HashMap<>();
            String tag = null, dto = null, page = null, status = null, create = null, update = null, dtoPackage = null;
            for (var pathEntry : paths.entrySet()) {
                Map<String, Object> verbs = (Map<String, Object>) pathEntry.getValue();
                for (String verb : List.of("get", "post", "put", "delete")) if (verbs.get(verb) instanceof Map<?, ?> raw) {
                    Map<String, Object> op = (Map<String, Object>) raw;
                    String id = String.valueOf(op.get("operationId")); String kind = verb.equals("delete") ? "void" : id.startsWith("list") ? "list" : "item";
                    String payload = String.valueOf(op.get("x-gsuif-payload-java-type"));
                    String request = refName(nested(op, "requestBody", "content", "application/json", "schema").get("$ref"));
                    String code = verb.equals("post") ? "201" : "200";
                    String envelopeName = refName(nested(op, "responses", code, "content", "application/json", "schema").get("$ref"));
                    Map<String, Object> envelope = nested(schemas, envelopeName);
                    List<String> required = (List<String>) envelope.get("required");
                    Map<String, Object> properties = nested(envelope, "properties");
                    if (required == null || required.size() != 5 || !required.containsAll(List.of("status", "clientMessage", "statusCode", "body", "errors")) || properties.size() != 5)
                        errors.add("contract." + id + ": invalid five-field envelope");
                    String body = refName(nested(envelope, "properties", "body").get("$ref"));
                    if (kind.equals("void") ? !payload.equals("java.lang.Void") : !payload.endsWith("." + body)) errors.add("contract." + id + ": payload/envelope mismatch");
                    if (tag == null) tag = String.valueOf(((List<?>) op.get("tags")).getFirst());
                    else if (!tag.equals(String.valueOf(((List<?>) op.get("tags")).getFirst()))) errors.add("contract." + id + ": mixed API tags");
                    if (!kind.equals("void")) {
                        if (dtoPackage == null) dtoPackage = payload.substring(0, payload.lastIndexOf('.'));
                        else if (!dtoPackage.equals(payload.substring(0, payload.lastIndexOf('.')))) errors.add("contract." + id + ": mixed DTO packages");
                        if (kind.equals("list")) page = body; else dto = body;
                    }
                    if (verb.equals("post")) create = request;
                    if (verb.equals("put")) update = request;
                    String methodKey = verb.equals("post") ? "createMethod" : verb.equals("put") ? "updateMethod" : verb.equals("delete") ? "deleteMethod" : kind.equals("list") ? "listMethod" : "getMethod";
                    if (methods.put(methodKey, id) != null) errors.add("contract." + id + ": duplicate CRUD operation");
                    operations.put(id, new Operation(verb.toUpperCase(Locale.ROOT), pathEntry.getKey(), kind, request,
                            Set.copyOf(nested(nested(schemas, request), "properties").keySet())));
                }
            }
            if (methods.size() != 5 || tag == null || dto == null || page == null || create == null || update == null) errors.add("contract: five CRUD operations required");
            status = tag + "Status";
            Map<String, List<String>> enums = new HashMap<>();
            for (var entry : schemas.entrySet()) if (nested(schemas, entry.getKey()).get("enum") instanceof List<?> values)
                enums.put(entry.getKey(), values.stream().map(String::valueOf).toList());
            Map<String, Object> api = new HashMap<>();
            api.put("dtoPackage", dtoPackage); api.put("responsePackage", "eg.mts.gsuif.dto"); api.put("statusEnumPackage", dtoPackage);
            api.put("servicePackage", dtoPackage.substring(0, dtoPackage.lastIndexOf('.')) + ".service");
            api.put("interfacePackage", dtoPackage.substring(0, dtoPackage.lastIndexOf('.')) + ".api");
            api.put("dtoClass", dto); api.put("pageDtoClass", page); api.put("createRequestClass", create); api.put("updateRequestClass", update);
            api.put("statusEnumClass", status); api.put("serviceInterface", tag + "Service"); api.put("interfaceClass", tag + "Api");
            api.put("dtoProperties", nested(nested(schemas, dto), "properties"));
            api.put("dtoRequired", List.copyOf((List<String>) nested(schemas, dto).get("required")));
            api.putAll(methods);
            return new Contract(Map.copyOf(api), Map.copyOf(operations), nested(nested(schemas, dto), "properties"), Map.copyOf(enums));
        } catch (Exception ex) { errors.add("contract: packaged contract cannot be read: " + ex.getClass().getSimpleName()); return null; }
    }

    private static boolean identifier(String value, String regex) {
        if (value == null || !value.matches(regex)) return false;
        for (String part : value.split("\\.")) if (RESERVED.contains(part)) return false;
        return true;
    }
    @SuppressWarnings("unchecked")
    private static Map<String, Object> nested(Map<String, Object> map, String... keys) {
        Object current = map;
        for (String key : keys) { if (!(current instanceof Map<?, ?> node)) return Map.of(); current = node.get(key); }
        return current instanceof Map<?, ?> node ? (Map<String, Object>) node : Map.of();
    }
    private static String refName(Object ref) {
        if (!(ref instanceof String value)) return "";
        return value.substring(value.lastIndexOf('/') + 1);
    }
}
