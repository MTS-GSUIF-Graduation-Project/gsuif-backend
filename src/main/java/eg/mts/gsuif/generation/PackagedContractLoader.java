package eg.mts.gsuif.generation;

import eg.mts.gsuif.generation.GenerationSpecification.OpenApiSpec;
import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.util.*;

/** Loads and validates the supported CRUD shape once for each generation request. */
final class PackagedContractLoader {
    private final PackagedContractRegistry registry;

    PackagedContractLoader(PackagedContractRegistry registry) { this.registry = registry; }

    record Operation(String verb, String path, String kind, String request, Set<String> requestFields) { }
    record Contract(Map<String, Object> api, Map<String, Operation> operations,
                            Map<String, Object> dtoProperties, Map<String, List<String>> enums) { }

    @SuppressWarnings("unchecked")
    Contract load(OpenApiSpec spec, List<String> errors) {
        String resource = spec.contractId() == null ? null : registry.resource(spec.contractId()); if (resource == null) return null;
        try (InputStream in = getClass().getResourceAsStream(resource)) {
            Map<String, Object> root = new Yaml().load(in);
            if (!Objects.equals(spec.contractVersion(), nested(root, "info").get("version"))) errors.add("specification.openApi.contractVersion: packaged version mismatch");
            Map<String, Object> paths = nested(root, "paths"); Map<String, Object> schemas = nested(root, "components", "schemas");
            Map<String, Operation> operations = new LinkedHashMap<>(); Map<String, String> methods = new HashMap<>();
            String tag = null, dto = null, page = null, status = null, create = null, update = null, dtoPackage = null;
            for (var pathEntry : paths.entrySet()) {
                Map<String, Object> verbs = (Map<String, Object>) pathEntry.getValue();
                for (String key : verbs.keySet()) {
                    if (!Set.of("get", "post", "put", "delete", "parameters", "summary", "description", "servers").contains(key))
                        errors.add("contract.paths." + pathEntry.getKey() + "." + key + ": unsupported operation; five CRUD operations required");
                    else if (Set.of("get", "post", "put", "delete").contains(key) && !(verbs.get(key) instanceof Map<?, ?>))
                        errors.add("contract.paths." + pathEntry.getKey() + "." + key + ": operation object required");
                }
                for (String verb : List.of("get", "post", "put", "delete")) if (verbs.get(verb) instanceof Map<?, ?> raw) {
                    Map<String, Object> op = (Map<String, Object>) raw;
                    String id = String.valueOf(op.get("operationId")); String kind = verb.equals("delete") ? "void" : id.startsWith("list") ? "list" : "item";
                    if (op.get("operationId") == null || id.isBlank())
                        errors.add("contract.paths." + pathEntry.getKey() + "." + verb + ".operationId: required");
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
                    if (operations.put(id, new Operation(verb.toUpperCase(Locale.ROOT), pathEntry.getKey(), kind, request,
                            Set.copyOf(nested(nested(schemas, request), "properties").keySet()))) != null)
                        errors.add("contract." + id + ": duplicate operation ID");
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
            api.put("enumOptions", Map.copyOf(enums));
            api.putAll(methods);
            return new Contract(Map.copyOf(api), Map.copyOf(operations), nested(nested(schemas, dto), "properties"), Map.copyOf(enums));
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
}
