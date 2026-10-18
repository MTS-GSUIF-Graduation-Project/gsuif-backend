package eg.mts.gsuif.generation;

import eg.mts.gsuif.generation.GenerationFilePlan.Owner;
import eg.mts.gsuif.generation.PackagedContractRegistry.CrudAction;
import freemarker.template.Configuration;
import freemarker.template.TemplateExceptionHandler;

import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/** Renders the exact-contract transport seam, without scaffolding or assembling an app. */
final class CompleteBackendApiRenderer {
    private static final Map<CrudAction, String> SERVICE_METHODS = Map.of(
            CrudAction.CREATE, "create", CrudAction.READ, "getById", CrudAction.LIST, "list",
            CrudAction.UPDATE, "update", CrudAction.DELETE, "delete");
    private final Configuration templates = new Configuration(Configuration.VERSION_2_3_34);

    CompleteBackendApiRenderer() {
        templates.setClassForTemplateLoading(getClass(), "/");
        templates.setDefaultEncoding(StandardCharsets.UTF_8.name());
        templates.setTemplateExceptionHandler(TemplateExceptionHandler.RETHROW_HANDLER);
        templates.setLogTemplateExceptions(false);
    }

    Map<String, byte[]> render(CompleteOutputDescriptor descriptor, GenerationFilePlan plan) {
        if (descriptor == null || descriptor.mode() == GenerationCapabilityPreflight.OutputMode.FRONTEND)
            throw new IllegalArgumentException("Backend API requires BACKEND or FULL_STACK mode");
        String root = "backend/src/main/java/" + descriptor.basePackage().replace('.', '/') + "/";
        String apiPath = root + "api/" + descriptor.contractInterfaceName() + ".java";
        String controllerPath = root + "controller/" + descriptor.entityName() + "Controller.java";
        Set<String> required = Set.of(apiPath, controllerPath);
        Map<String, Owner> owners = new HashMap<>();
        plan.entries().forEach(entry -> owners.put(entry.path(), entry.owner()));
        for (String path : required) if (owners.get(path) != Owner.DETERMINISTIC)
            throw new IllegalArgumentException("API/controller path absent or incorrectly owned: " + path);

        EnumMap<CrudAction, CompleteOutputDescriptor.Operation> actions = new EnumMap<>(CrudAction.class);
        for (var operation : descriptor.operations())
            if (actions.putIfAbsent(operation.action(), operation) != null)
                throw new IllegalArgumentException("Duplicate CRUD action");
        if (actions.size() != CrudAction.values().length)
            throw new IllegalArgumentException("Five CRUD actions required");
        List<Map<String, Object>> methods = new ArrayList<>();
        for (CrudAction action : List.of(CrudAction.LIST, CrudAction.CREATE, CrudAction.READ,
                CrudAction.UPDATE, CrudAction.DELETE)) methods.add(method(descriptor, actions.get(action)));
        Map<String, Object> model = Map.of("basePackage", descriptor.basePackage(),
                "interfaceName", descriptor.contractInterfaceName(), "controllerName", descriptor.entityName() + "Controller",
                "serviceName", descriptor.contractServiceName(), "methods", methods);
        Map<String, byte[]> files = new TreeMap<>();
        files.put(apiPath, renderTemplate("api.java.ftl", model));
        files.put(controllerPath, renderTemplate("controller.java.ftl", model));
        return validate(plan, required, files);
    }

    static Map<String, byte[]> validate(GenerationFilePlan plan, Set<String> required, Map<String, byte[]> emitted) {
        if (!required.equals(emitted.keySet())) throw new IllegalArgumentException("API/controller output differs from required subset");
        Map<String, Owner> owners = new HashMap<>();
        plan.entries().forEach(entry -> owners.put(entry.path(), entry.owner()));
        Map<String, byte[]> copy = new TreeMap<>();
        emitted.forEach((path, bytes) -> {
            GenerationArtifactStore.safeRelative(path);
            if (owners.get(path) != Owner.DETERMINISTIC || bytes == null)
                throw new IllegalArgumentException("Unplanned or incorrectly owned API/controller output");
            copy.put(path, bytes.clone());
        });
        return Collections.unmodifiableMap(copy);
    }

    private static Map<String, Object> method(CompleteOutputDescriptor d, CompleteOutputDescriptor.Operation op) {
        CrudAction action = op.action();
        String verb = switch (action) {
            case LIST, READ -> "GET";
            case CREATE -> "POST";
            case UPDATE -> "PUT";
            case DELETE -> "DELETE";
        };
        boolean item = action == CrudAction.READ || action == CrudAction.UPDATE || action == CrudAction.DELETE;
        if (!verb.equals(op.method()) || !op.path().startsWith("/")
                || item != op.path().endsWith("/{id}") || op.path().contains("\"")
                || !GenerationContextBuilder.identifier(op.operationId(), "[a-z][A-Za-z0-9]*")
                || op.roles().isEmpty() || op.successStatus() != (action == CrudAction.CREATE ? 201 : 200)
                || op.successEnvelope() == null || op.successEnvelope().isBlank()
                || !op.queryFields().isEmpty() && action != CrudAction.LIST)
            throw new IllegalArgumentException("Unsupported selected CRUD transport operation: " + op.operationId());
        String expectedResponse = action == CrudAction.LIST ? d.pageDtoName()
                : action == CrudAction.DELETE ? "" : d.dtoName();
        if (!expectedResponse.equals(op.responseDto())
                || (action == CrudAction.CREATE && !d.createRequestName().equals(op.requestDto()))
                || (action == CrudAction.UPDATE && !d.updateRequestName().equals(op.requestDto()))
                || (action != CrudAction.CREATE && action != CrudAction.UPDATE && op.requestDto() != null && !op.requestDto().isEmpty()))
            throw new IllegalArgumentException("Selected CRUD DTO differs from rendered service contract");
        List<Map<String, Object>> params = new ArrayList<>();
        if (item) params.add(Map.of("type", "java.util.UUID", "name", "id",
                "annotation", "@org.springframework.web.bind.annotation.PathVariable(\"id\")"));
        if (action == CrudAction.LIST) op.queryFields().forEach((name, parameter) -> {
            if (!GenerationContextBuilder.identifier(name, "[a-z][A-Za-z0-9]*"))
                throw new IllegalArgumentException("Unsupported query name");
            String type = scalarType(parameter.schema());
            if (!parameter.schema().enumName().isEmpty()) type = d.basePackage() + ".dto." + type;
            String temporalFormat = switch (parameter.schema().format()) {
                case "date" -> " @org.springframework.format.annotation.DateTimeFormat(iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE)";
                case "date-time" -> " @org.springframework.format.annotation.DateTimeFormat(iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE_TIME)";
                default -> "";
            };
            params.add(Map.of("type", type, "name", name, "annotation",
                    "@org.springframework.web.bind.annotation.RequestParam(name = \"" + name
                    + "\", required = " + parameter.required()
                    + (parameter.defaultValue() == null ? "" : ", defaultValue = \"" + parameter.defaultValue() + "\"")
                    + ")" + temporalFormat));
        });
        List<Map<String, String>> bounds = new ArrayList<>();
        if (action == CrudAction.LIST) op.queryFields().forEach((name, parameter) -> {
            if (parameter.minimum() != null) bounds.add(Map.of("name", name, "condition",
                    name + " == null || " + name + " < " + parameter.minimum()));
            if (parameter.maximum() != null) bounds.add(Map.of("name", name, "condition",
                    name + " == null || " + name + " > " + parameter.maximum()));
        });
        if (action == CrudAction.CREATE || action == CrudAction.UPDATE)
            params.add(Map.of("type", d.basePackage() + ".dto."
                    + (action == CrudAction.CREATE ? d.createRequestName() : d.updateRequestName()),
                    "name", "request", "annotation", "@jakarta.validation.Valid @org.springframework.web.bind.annotation.RequestBody"));
        String payloadType = action == CrudAction.LIST ? d.pageDtoName()
                : action == CrudAction.DELETE ? "Void" : d.dtoName();
        if (action != CrudAction.DELETE) payloadType = d.basePackage() + ".dto." + payloadType;
        String arguments = action == CrudAction.LIST ? String.join(", ", op.queryFields().keySet())
                : switch (action) { case CREATE -> "request"; case READ, DELETE -> "id";
                    case UPDATE -> "id, request"; default -> throw new IllegalStateException(); };
        String roleExpression = "hasAnyAuthority(" + op.roles().stream().map(role -> "'" + role.name() + "'")
                .sorted().reduce((a, b) -> a + ", " + b).orElseThrow() + ")";
        Map<String, Object> model = new TreeMap<>();
        model.put("operationId", op.operationId()); model.put("verb", verb); model.put("path", op.path());
        model.put("serviceMethod", SERVICE_METHODS.get(action)); model.put("arguments", arguments);
        model.put("payloadType", payloadType); model.put("status", op.successStatus());
        model.put("roles", roleExpression); model.put("parameters", List.copyOf(params));
        model.put("delete", action == CrudAction.DELETE);
        model.put("item", item); model.put("write", action == CrudAction.CREATE || action == CrudAction.UPDATE);
        model.put("bounds", List.copyOf(bounds));
        return Collections.unmodifiableMap(model);
    }

    private static String scalarType(PackagedContractLoader.FieldSchema schema) {
        if (!schema.enumName().isEmpty()) return schema.enumName();
        return switch (schema.type() + ":" + schema.format()) {
            case "string:" -> "String"; case "string:uuid" -> "java.util.UUID";
            case "string:date" -> "java.time.LocalDate"; case "string:date-time" -> "java.time.LocalDateTime";
            case "integer:int32" -> "Integer"; case "integer:int64" -> "Long";
            case "number:" -> "java.math.BigDecimal"; case "boolean:" -> "Boolean";
            default -> throw new IllegalArgumentException("Unsupported query scalar");
        };
    }

    private byte[] renderTemplate(String template, Map<String, Object> model) {
        try {
            StringWriter writer = new StringWriter();
            templates.getTemplate("templates/complete-backend/" + template).process(model, writer);
            return writer.toString().replace("\r\n", "\n").replace('\r', '\n').getBytes(StandardCharsets.UTF_8);
        } catch (Exception ex) { throw new IllegalArgumentException("Could not render backend API template: " + template, ex); }
    }
}
