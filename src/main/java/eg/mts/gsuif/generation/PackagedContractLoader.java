package eg.mts.gsuif.generation;

import eg.mts.gsuif.generation.GenerationSpecification.OpenApiSpec;
import eg.mts.gsuif.generation.PackagedContractRegistry.CrudAction;
import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.util.*;

/** Reads a registered version and checks each explicit CRUD mapping against OpenAPI. */
final class PackagedContractLoader {
    private final PackagedContractRegistry registry;

    PackagedContractLoader(PackagedContractRegistry registry) { this.registry = registry; }

    record Operation(String verb, String path, String kind, String request, Set<String> requestFields,
                     CrudAction action) { }
    record UpdateField(boolean required, boolean nullable) { }
    record FieldSchema(String type, String format, String enumName, List<String> values, boolean nullable) { }
    record Contract(Map<String, Object> api, Map<String, Operation> operations,
                    Map<String, Object> dtoProperties, Map<String, List<String>> enums,
                    PackagedContractRegistry.Entry descriptor, Map<String, UpdateField> updateFields,
                    Map<String, FieldSchema> updateSchemas, Map<String, FieldSchema> dtoSchemas) { }

    @SuppressWarnings("unchecked")
    Contract load(OpenApiSpec spec, List<String> errors) {
        var descriptor = spec.contractId() == null ? null : registry.find(spec.contractId(), spec.contractVersion());
        if (descriptor == null) return null;
        String resource = descriptor.resource();
        try (InputStream in = getClass().getResourceAsStream(resource)) {
            if (in == null) throw new IllegalStateException("missing OpenAPI resource");
            Map<String, Object> root = new Yaml().load(in);
            if (!Objects.equals(descriptor.version(), nested(root, "info").get("version")))
                errors.add("specification.openApi.contractVersion: packaged version mismatch");
            Map<String, Object> paths = nested(root, "paths");
            Map<String, Object> schemas = nested(root, "components", "schemas");
            Map<String, Operation> operations = new LinkedHashMap<>();
            Map<String, String> methods = new HashMap<>();
            Map<String, CrudAction> actionsById = new HashMap<>();
            descriptor.operations().forEach((action, id) -> actionsById.put(id, action));
            String tag = null, dto = null, page = null, create = null, update = null, dtoPackage = null;
            String collectionPath = null;
            for (var pathEntry : paths.entrySet()) {
                if (!(pathEntry.getValue() instanceof Map<?, ?>)) {
                    errors.add("contract.paths." + pathEntry.getKey() + ": path item required");
                    continue;
                }
                Map<String, Object> verbs = (Map<String, Object>) pathEntry.getValue();
                for (String key : verbs.keySet()) {
                    if (!Set.of("get", "post", "put", "delete", "parameters", "summary", "description", "servers").contains(key))
                        errors.add("contract.paths." + pathEntry.getKey() + "." + key + ": unsupported operation; five CRUD operations required");
                    else if (Set.of("get", "post", "put", "delete").contains(key) && !(verbs.get(key) instanceof Map<?, ?>))
                        errors.add("contract.paths." + pathEntry.getKey() + "." + key + ": operation object required");
                }
                for (String verb : List.of("get", "post", "put", "delete")) if (verbs.get(verb) instanceof Map<?, ?> raw) {
                    Map<String, Object> op = (Map<String, Object>) raw;
                    String id = String.valueOf(op.get("operationId"));
                    if (op.get("operationId") == null || id.isBlank()) {
                        errors.add("contract.paths." + pathEntry.getKey() + "." + verb + ".operationId: required");
                        continue;
                    }
                    CrudAction action = actionsById.get(id);
                    if (action == null) {
                        errors.add("contract." + id + ": operation absent from explicit CRUD mapping");
                        continue;
                    }
                    String expectedVerb = switch (action) {
                        case LIST, READ -> "get";
                        case CREATE -> "post";
                        case UPDATE -> "put";
                        case DELETE -> "delete";
                    };
                    if (!verb.equals(expectedVerb)) errors.add("contract." + id + ": CRUD method mismatch");
                    boolean itemAction = EnumSet.of(CrudAction.READ, CrudAction.UPDATE, CrudAction.DELETE).contains(action);
                    String path = pathEntry.getKey();
                    boolean itemPath = path.endsWith("/{id}") && path.indexOf('{') == path.length() - "{id}".length();
                    if (!path.startsWith("/") || path.contains("?") || itemPath != itemAction
                            || (!itemPath && (path.contains("{") || path.endsWith("/"))))
                        errors.add("contract." + id + ": CRUD path mismatch");
                    String operationCollection = itemAction && itemPath ? path.substring(0, path.length() - "/{id}".length()) : path;
                    if (collectionPath == null) collectionPath = operationCollection;
                    else if (!collectionPath.equals(operationCollection))
                        errors.add("contract." + id + ": CRUD collection path mismatch");
                    String kind = switch (action) {
                        case LIST -> "list";
                        case DELETE -> "void";
                        default -> "item";
                    };
                    String payload = String.valueOf(op.get("x-gsuif-payload-java-type"));
                    String request = refName(nested(op, "requestBody", "content", "application/json", "schema").get("$ref"));
                    String code = action == CrudAction.CREATE ? "201" : "200";
                    String envelopeName = refName(nested(op, "responses", code, "content", "application/json", "schema").get("$ref"));
                    Map<String, Object> envelope = nested(schemas, envelopeName);
                    List<String> required = (List<String>) envelope.get("required");
                    Map<String, Object> properties = nested(envelope, "properties");
                    if (required == null || required.size() != 5 || !required.containsAll(List.of("status", "clientMessage", "statusCode", "body", "errors")) || properties.size() != 5)
                        errors.add("contract." + id + ": invalid five-field envelope");
                    String body = refName(nested(envelope, "properties", "body").get("$ref"));
                    if (kind.equals("void") ? !payload.equals("java.lang.Void") : !payload.endsWith("." + body))
                        errors.add("contract." + id + ": payload/envelope mismatch");
                    if (descriptor.fullUpdate() && EnumSet.of(CrudAction.READ, CrudAction.UPDATE, CrudAction.DELETE).contains(action)) {
                        String missingName = refName(nested(op, "responses", "404", "content", "application/json", "schema").get("$ref"));
                        if (!validErrorEnvelope(nested(schemas, missingName)))
                            errors.add("contract." + id + ": 404 five-field error envelope required");
                    }
                    if (descriptor.fullUpdate() && action == CrudAction.UPDATE) {
                        String invalidName = refName(nested(op, "responses", "400", "content", "application/json", "schema").get("$ref"));
                        if (!validErrorEnvelope(nested(schemas, invalidName)))
                            errors.add("contract." + id + ": 400 five-field error envelope required");
                    }
                    String operationTag = String.valueOf(((List<?>) op.get("tags")).getFirst());
                    if (tag == null) tag = operationTag;
                    else if (!tag.equals(operationTag)) errors.add("contract." + id + ": mixed API tags");
                    if (!kind.equals("void")) {
                        if (dtoPackage == null) dtoPackage = payload.substring(0, payload.lastIndexOf('.'));
                        else if (!dtoPackage.equals(payload.substring(0, payload.lastIndexOf('.'))))
                            errors.add("contract." + id + ": mixed DTO packages");
                        if (kind.equals("list")) page = body; else dto = body;
                    }
                    if (action == CrudAction.CREATE) create = request;
                    if (action == CrudAction.UPDATE) update = request;
                    String methodKey = switch (action) {
                        case CREATE -> "createMethod";
                        case READ -> "getMethod";
                        case LIST -> "listMethod";
                        case UPDATE -> "updateMethod";
                        case DELETE -> "deleteMethod";
                    };
                    if (methods.put(methodKey, id) != null) errors.add("contract." + id + ": duplicate CRUD operation");
                    if (operations.put(id, new Operation(verb.toUpperCase(Locale.ROOT), pathEntry.getKey(), kind, request,
                            Set.copyOf(nested(nested(schemas, request), "properties").keySet()), action)) != null)
                        errors.add("contract." + id + ": duplicate operation ID");
                }
            }
            if (methods.size() != 5 || tag == null || dto == null || page == null || create == null || update == null)
                errors.add("contract: five mapped CRUD operations required");
            for (String id : descriptor.operations().values()) if (!operations.containsKey(id))
                errors.add("contract." + id + ": mapped operation missing from OpenAPI");
            String status = tag + "Status";
            Map<String, List<String>> enums = new HashMap<>();
            for (var entry : schemas.entrySet()) if (nested(schemas, entry.getKey()).get("enum") instanceof List<?> values)
                enums.put(entry.getKey(), values.stream().map(String::valueOf).toList());
            Map<String, Object> api = new HashMap<>();
            api.put("dtoPackage", dtoPackage);
            api.put("responsePackage", "eg.mts.gsuif.dto");
            api.put("statusEnumPackage", dtoPackage);
            api.put("servicePackage", dtoPackage.substring(0, dtoPackage.lastIndexOf('.')) + ".service");
            api.put("interfacePackage", dtoPackage.substring(0, dtoPackage.lastIndexOf('.')) + ".api");
            api.put("dtoClass", dto); api.put("pageDtoClass", page);
            api.put("createRequestClass", create); api.put("updateRequestClass", update);
            api.put("statusEnumClass", status); api.put("serviceInterface", tag + "Service");
            api.put("interfaceClass", tag + "Api");
            api.put("dtoProperties", nested(nested(schemas, dto), "properties"));
            api.put("dtoRequired", List.copyOf((List<String>) nested(schemas, dto).get("required")));
            api.put("enumOptions", Map.copyOf(enums));
            api.putAll(methods);
            Map<String, Object> updateSchema = nested(schemas, update);
            Map<String, Object> updateProperties = nested(updateSchema, "properties");
            List<String> updateRequired = (List<String>) updateSchema.getOrDefault("required", List.of());
            Map<String, UpdateField> updateFields = new LinkedHashMap<>();
            Map<String, FieldSchema> updateSchemas = new LinkedHashMap<>();
            for (var field : updateProperties.entrySet()) {
                Map<String, Object> property = nested(updateProperties, field.getKey());
                updateFields.put(field.getKey(), new UpdateField(updateRequired.contains(field.getKey()),
                        Boolean.TRUE.equals(property.get("nullable"))));
                updateSchemas.put(field.getKey(), fieldSchema(property, schemas));
            }
            Map<String, FieldSchema> dtoSchemas = new LinkedHashMap<>();
            for (var field : nested(nested(schemas, dto), "properties").entrySet())
                dtoSchemas.put(field.getKey(), fieldSchema(nested(nested(schemas, dto), "properties", field.getKey()), schemas));
            if (descriptor.fullUpdate()) {
                if (updateFields.isEmpty() || !updateFields.keySet().equals(Set.copyOf(updateRequired)))
                    errors.add("contract.update: every editable field must be required for full update");
                if (!nested(schemas, dto).containsKey("properties"))
                    errors.add("contract.update: response DTO required");
                else for (String field : updateFields.keySet())
                    if (!nested(nested(schemas, dto), "properties").containsKey(field))
                        errors.add("contract.update." + field + ": absent from response DTO");
            }
            return new Contract(Map.copyOf(api), Map.copyOf(operations), nested(nested(schemas, dto), "properties"),
                    Map.copyOf(enums), descriptor, Map.copyOf(updateFields), Map.copyOf(updateSchemas), Map.copyOf(dtoSchemas));
        } catch (Exception ex) {
            errors.add("specification.openApi.contractId (" + spec.contractId() + "): packaged contract "
                    + resource + " has an unsupported or unreadable shape (" + ex.getClass().getSimpleName() + ")");
            return null;
        }
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

    private static FieldSchema fieldSchema(Map<String, Object> property, Map<String, Object> schemas) {
        String enumName = refName(property.get("$ref"));
        Map<String, Object> resolved = enumName.isEmpty() ? property : nested(schemas, enumName);
        Object rawValues = resolved.get("enum");
        List<String> values = rawValues instanceof List<?> list ? list.stream().map(String::valueOf).toList() : List.of();
        return new FieldSchema(String.valueOf(resolved.getOrDefault("type", "")),
                String.valueOf(resolved.getOrDefault("format", "")), enumName, values,
                Boolean.TRUE.equals(property.get("nullable")) || Boolean.TRUE.equals(resolved.get("nullable")));
    }

    private static boolean validErrorEnvelope(Map<String, Object> schema) {
        Object required = schema.get("required");
        Map<String, Object> properties = nested(schema, "properties");
        return required instanceof List<?> names && names.size() == 5
                && names.containsAll(List.of("status", "clientMessage", "statusCode", "body", "errors"))
                && properties.size() == 5
                && "string".equals(nested(properties, "status").get("type"))
                && "string".equals(nested(properties, "clientMessage").get("type"))
                && "integer".equals(nested(properties, "statusCode").get("type"))
                && Boolean.TRUE.equals(nested(properties, "body").get("nullable"))
                && "object".equals(nested(properties, "errors").get("type"));
    }
}
