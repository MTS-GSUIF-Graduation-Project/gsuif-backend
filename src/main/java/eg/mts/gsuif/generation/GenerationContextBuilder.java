package eg.mts.gsuif.generation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import eg.mts.gsuif.entity.MetadataVersion;
import eg.mts.gsuif.generation.GenerationContext.Target;
import eg.mts.gsuif.generation.GenerationSpecification.*;
import eg.mts.gsuif.validator.MetadataSchemaValidator;

import java.util.*;

/** Resolves only packaged contracts and immutable metadata snapshots. */
public final class GenerationContextBuilder {
    private static final Set<String> TYPES = Set.of("String", "UUID", "LocalDate", "LocalDateTime", "Integer", "Long", "BigDecimal", "Boolean");
    private static final Set<String> RESERVED = Set.of("abstract", "assert", "boolean", "break", "byte", "case", "catch", "char", "class", "const", "continue", "default", "do", "double", "else", "enum", "extends", "final", "finally", "float", "for", "goto", "if", "implements", "import", "instanceof", "int", "interface", "long", "native", "new", "package", "private", "protected", "public", "return", "short", "static", "strictfp", "super", "switch", "synchronized", "this", "throw", "throws", "transient", "try", "void", "volatile", "while", "true", "false", "null", "var", "yield", "record", "sealed", "permits");
    private final ObjectMapper mapper;
    private final MetadataSchemaValidator schemaValidator;
    private final PackagedContractRegistry contractRegistry;
    private final PackagedContractLoader contractLoader;

    public GenerationContextBuilder(ObjectMapper mapper, MetadataSchemaValidator schemaValidator) {
        this(mapper, schemaValidator, new PackagedContractRegistry());
    }

    GenerationContextBuilder(ObjectMapper mapper, MetadataSchemaValidator schemaValidator,
                             PackagedContractRegistry contractRegistry) {
        this.mapper = Objects.requireNonNull(mapper);
        this.schemaValidator = Objects.requireNonNull(schemaValidator);
        this.contractRegistry = Objects.requireNonNull(contractRegistry);
        this.contractLoader = new PackagedContractLoader(contractRegistry);
    }

    public GenerationContext build(MetadataVersion version, GenerationSpecification spec, Set<Target> targets, String framework) {
        return build(version, spec, targets, framework, false);
    }

    GenerationContext build(MetadataVersion version, GenerationSpecification spec, Set<Target> targets,
                            String framework, boolean completeOutputProfile) {
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
        Map<String, Object> api = spec == null ? Map.of() : validateSpecification(spec, snapshot, targets,
                version == null ? null : version.getSchemaVersion(), completeOutputProfile, errors);
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

    private Map<String, Object> validateSpecification(GenerationSpecification s, JsonNode snapshot, Set<Target> targets,
                                                       String schemaVersion, boolean completeOutputProfile,
                                                       List<String> errors) {
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
                    if (f.columnLength() != null && (f.columnLength() < 1 || !("String".equals(f.javaType())
                            || completeOutputProfile && f.enumType() != null)))
                        errors.add(p + ".columnLength: requires positive String length"
                                + (completeOutputProfile ? " or enum length" : ""));
                }
            }
        }
        OpenApiSpec openApi = s.openApi();
        if (openApi == null) { errors.add("specification.openApi: required"); return Map.of(); }
        if (!contractRegistry.containsId(openApi.contractId()))
            errors.add("specification.openApi.contractId: unsupported packaged contract");
        else if (contractRegistry.find(openApi.contractId(), openApi.contractVersion()) == null)
            errors.add("specification.openApi.contractVersion: unsupported packaged version");
        PackagedContractLoader.Contract contract = contractLoader.load(openApi, errors);
        Map<String, Object> api = contract == null ? Map.of() : contract.api();
        Map<String, PackagedContractLoader.Operation> operations = contract == null ? Map.of() : contract.operations();
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
                PackagedContractLoader.Operation operation = operationId == null ? null : operations.get(operationId);
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
                if (operation.kind().equals("list")) {
                    for (String query : operation.queryFields().keySet())
                        if (!query.equals(request.path("query").path(query).asText()))
                            errors.add(p + ".requestMapping.query." + query + ": required");
                    if (request.path("query").isObject()) request.path("query").fieldNames().forEachRemaining(query -> {
                        if (!operation.queryFields().containsKey(query))
                            errors.add(p + ".requestMapping.query." + query + ": absent from contract");
                    });
                }
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
            if (snapshot != null && targets != null && targets.contains(Target.ANGULAR)) validateAngular(s, snapshot, contract, schemaVersion, errors);
        }
        return api;
    }

    private void validateAngular(GenerationSpecification s, JsonNode snapshot, PackagedContractLoader.Contract contract, String schemaVersion, List<String> errors) {
        if (contract == null || s.entity() == null || s.entity().fields() == null || s.angular().tableColumns() == null) return;
        Map<String, FieldSpec> fields = new HashMap<>();
        for (FieldSpec field : s.entity().fields()) if (field != null && field.name() != null) fields.put(field.name(), field);
        Map<String, Object> dto = contract.dtoProperties();
        JsonNode components = snapshot.path("components"); JsonNode bindings = snapshot.path("apiBindings");
        Map<UUID, String> selectedOperations = s.openApi().bindingOperations();
        Set<String> selectedComponents = selectedComponentIds(snapshot, selectedOperations, schemaVersion);
        Map<String, Integer> ruleOwners = new HashMap<>();
        for (int j = 0; j < bindings.size(); j++) {
            JsonNode binding = bindings.get(j);
            UUID bindingId;
            try { bindingId = UUID.fromString(binding.path("id").asText()); } catch (Exception ex) { continue; }
            if (selectedOperations == null || !selectedOperations.containsKey(bindingId) || !binding.has("visibilityRule")) continue;
            Set<String> affected = bindingComponentIds(binding);
            if (affected.isEmpty()) errors.add("snapshot.apiBindings[" + j + "].visibilityRule: selected binding has no referenced components");
            for (String linked : affected) {
                Integer previous = ruleOwners.putIfAbsent(linked, j);
                if (previous != null)
                    errors.add("snapshot.apiBindings[" + j + "].visibilityRule: component " + linked
                            + " also has a visibility rule at snapshot.apiBindings[" + previous + "].visibilityRule");
            }
        }
        Set<UUID> legacyTables = new HashSet<>();
        Set<String> selectedListComponents = new HashSet<>();
        for (JsonNode binding : bindings) {
            UUID bindingId;
            try { bindingId = UUID.fromString(binding.path("id").asText()); } catch (Exception ex) { continue; }
            if (selectedOperations == null || !selectedOperations.containsKey(bindingId)) continue;
            String operationId = selectedOperations.get(bindingId);
            if (operationId == null) continue;
            PackagedContractLoader.Operation operation = contract.operations().get(operationId);
            if (operation != null && "list".equals(operation.kind()))
                selectedListComponents.addAll(bindingComponentIds(binding));
        }
        var handlerContext = new AngularComponentHandlers.ValidationContext(s.angular(), dto, selectedListComponents, legacyTables);
        for (int i = 0; i < components.size(); i++) {
            JsonNode component = components.get(i); String p = "snapshot.components[" + i + "]";
            if (selectedOperations != null && !selectedComponents.contains(component.path("id").asText())) continue;
            String type = component.path("type").asText(); String key = component.path("fieldKey").asText();
            AngularComponentHandlers.Handler handler = AngularComponentHandlers.find(type);
            if (handler == null) { errors.add(p + " (" + component.path("id").asText() + ").type: unsupported Angular component"); continue; }
            if (!Set.of("1.1.0", "1.2.0").contains(schemaVersion) && handler.requiresVersion11()) {
                errors.add(p + " (component " + component.path("id").asText()
                        + ").type: unsupported Angular component for schema " + schemaVersion);
                continue;
            }
            boolean selectedLink = false;
            for (JsonNode binding : bindings) {
                UUID bindingId;
                try { bindingId = UUID.fromString(binding.path("id").asText()); } catch (Exception ex) { continue; }
                if (s.openApi().bindingOperations() == null || !s.openApi().bindingOperations().containsKey(bindingId)) continue;
                if (bindingReferences(binding, component.path("id").asText())) selectedLink = true;
            }
            if (!selectedLink && handler.needsBinding())
                errors.add(p + " (" + component.path("id").asText() + "): selected API binding link required");
            for (String coordinate : List.of("row", "col")) {
                JsonNode value = component.path("position").path(coordinate);
                if (!value.canConvertToInt() || value.intValue() == Integer.MAX_VALUE)
                    errors.add(p + " (" + component.path("id").asText() + ").position." + coordinate
                            + ": exceeds supported CSS grid line range");
            }
            for (String span : List.of("width", "height")) {
                JsonNode value = component.path("size").path(span);
                if (!value.canConvertToInt() || value.intValue() < 1)
                    errors.add(p + " (" + component.path("id").asText() + ").size." + span
                            + ": exceeds supported CSS grid span range");
            }
            handler.validate(component, p, handlerContext, errors);
            if (!handler.requiresField()) continue;
            FieldSpec field = fields.get(key); Map<String, Object> property = dtoProperty(dto, key);
            String fieldPath = p + ".fieldKey (component " + component.path("id").asText() + ")";
            if (field == null || property.isEmpty()) { errors.add(fieldPath + ": absent from entity and DTO"); continue; }
            if (!handler.validField(field, property, contract.enums())) errors.add(fieldPath + ": incompatible DTO type");
        }
        for (UUID id : s.angular().tableColumns().keySet()) if (!legacyTables.contains(id)) errors.add("specification.angular.tableColumns." + id + ": unknown table");
        if (selectedOperations != null) for (String id : selectedComponents) {
            boolean present = false;
            for (JsonNode component : components) if (id.equals(component.path("id").asText())) { present = true; break; }
            if (!present) errors.add(selectedComponentReferencePath(bindings, selectedOperations, id)
                    + ": selected component " + id + " absent from snapshot");
        }
    }

    private static String selectedComponentReferencePath(JsonNode bindings, Map<UUID, String> selectedOperations, String componentId) {
        for (int i = 0; i < bindings.size(); i++) {
            JsonNode binding = bindings.get(i);
            UUID bindingId;
            try { bindingId = UUID.fromString(binding.path("id").asText()); }
            catch (Exception ex) { continue; }
            if (!selectedOperations.containsKey(bindingId)) continue;
            JsonNode linked = binding.path("linkedComponentIds");
            for (int j = 0; j < linked.size(); j++)
                if (componentId.equals(linked.get(j).asText()))
                    return "snapshot.apiBindings[" + i + "].linkedComponentIds[" + j + "]";
            if (componentId.equals(binding.path("parentComponentId").asText()))
                return "snapshot.apiBindings[" + i + "].parentComponentId";
            JsonNode children = binding.path("childComponentIds");
            for (int j = 0; j < children.size(); j++)
                if (componentId.equals(children.get(j).asText()))
                    return "snapshot.apiBindings[" + i + "].childComponentIds[" + j + "]";
        }
        return "snapshot.apiBindings";
    }

    static Set<String> selectedComponentIds(JsonNode snapshot, Map<UUID, String> selectedOperations, String schemaVersion) {
        Set<String> ids = new HashSet<>();
        if (selectedOperations == null) return ids;
        for (JsonNode binding : snapshot.path("apiBindings")) {
            UUID id;
            try { id = UUID.fromString(binding.path("id").asText()); } catch (Exception ex) { continue; }
            if (selectedOperations.containsKey(id)) {
                ids.addAll(bindingComponentIds(binding));
            }
        }
        if (Set.of("1.1.0", "1.2.0").contains(schemaVersion)) {
            Set<String> referenced = new HashSet<>();
            for (JsonNode binding : snapshot.path("apiBindings")) referenced.addAll(bindingComponentIds(binding));
            for (JsonNode component : snapshot.path("components")) {
                // An unbound component is part of the page and must be rendered or diagnosed.
                // Components attached only to unselected operations retain the selected-binding boundary.
                if (!referenced.contains(component.path("id").asText()))
                    ids.add(component.path("id").asText());
            }
        }
        return ids;
    }

    private static boolean bindingReferences(JsonNode binding, String componentId) {
        return bindingComponentIds(binding).contains(componentId);
    }

    static Set<String> bindingComponentIds(JsonNode binding) {
        Set<String> ids = new LinkedHashSet<>();
        for (JsonNode linked : binding.path("linkedComponentIds")) ids.add(linked.asText());
        if (binding.hasNonNull("parentComponentId")) ids.add(binding.path("parentComponentId").asText());
        for (JsonNode child : binding.path("childComponentIds")) ids.add(child.asText());
        return ids;
    }

    private static boolean identifier(String value, String regex) {
        if (value == null || !value.matches(regex)) return false;
        for (String part : value.split("\\.")) if (RESERVED.contains(part)) return false;
        return true;
    }
    @SuppressWarnings("unchecked")
    private static Map<String, Object> dtoProperty(Map<String, Object> dto, String key) {
        Object value = dto.get(key);
        return value instanceof Map<?, ?> property ? (Map<String, Object>) property : Map.of();
    }
}
