package eg.mts.gsuif.generation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import eg.mts.gsuif.entity.GsuifPage;
import eg.mts.gsuif.entity.GsuifProject;
import eg.mts.gsuif.entity.MetadataVersion;
import eg.mts.gsuif.generation.GenerationCapabilityPreflight.OutputMode;
import eg.mts.gsuif.generation.GenerationFilePlan.Entry;
import eg.mts.gsuif.generation.GenerationFilePlan.Owner;
import eg.mts.gsuif.generation.GenerationSpecification.*;
import eg.mts.gsuif.generation.PackagedContractRegistry.CrudAction;
import eg.mts.gsuif.validator.MetadataSchemaValidator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.TreeMap;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.tools.ToolProvider;

import static org.junit.jupiter.api.Assertions.*;

class CompleteOutputDescriptorTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final List<String> ids = List.of("scanStockUnits", "registerStockUnit", "inspectStockUnit",
            "replaceStockUnit", "discardStockUnit");

    @Test void backendDefinitionsUseOnlyRequiredSubsetAndBindExactBytes() throws Exception {
        var descriptor = preflight().describe(version(snapshot("condition")), specification(), OutputMode.BACKEND);
        var plan = GenerationFilePlan.forDescriptor(descriptor);
        var rendered = new CompleteBackendDefinitionRenderer().render(descriptor, plan);
        var required = CompleteBackendDefinitionRenderer.requiredDefinitionPaths(descriptor,
                rendered.handoff().specification());
        assertEquals(required, rendered.files().keySet());
        assertEquals(rendered.handoff().definitions().size(), required.size());
        assertTrue(required.stream().anyMatch(path -> path.endsWith("entity/AuditableEntity.java")));
        var audit = rendered.handoff().definitions().stream()
                .filter(definition -> definition.role().equals("audit base")).findFirst().orElseThrow();
        assertTrue(audit.path().endsWith("entity/AuditableEntity.java"));
        assertArrayEquals(rendered.files().get(audit.path()), audit.bytes());
        assertFalse(required.contains("backend/pom.xml"));
        assertTrue(plan.entries().stream().anyMatch(e -> e.path().equals("backend/pom.xml")));
        String all = rendered.files().values().stream()
                .map(bytes -> new String(bytes, StandardCharsets.UTF_8)).reduce("", String::concat);
        assertFalse(all.contains("eg.mts.gsuif"));
        assertFalse(all.contains("AssetTicket"));
        assertFalse(all.contains("Gadget"));
        assertFalse(all.contains("scanStockUnits")); // operation IDs stay in the typed handoff, not source signatures
        assertEquals(ids, rendered.handoff().specification().operations().stream().map(
                CompleteOutputDescriptor.Operation::operationId).toList());
        assertTrue(all.contains("StockUnitPage list(com.example.inventory.dto.StockUnitCondition condition, String label)"));
        assertTrue(all.contains("required = true"));
        assertTrue(all.contains("java.time.LocalDate retiredOn"));
        assertTrue(all.contains("enum StockUnitCondition { OPEN, CLOSED }"));
        assertFalse(rendered.files().keySet().stream().anyMatch(p -> p.endsWith("StockUnitLabel.java")));
        for (var definition : rendered.handoff().definitions()) {
            byte[] emitted = rendered.files().get(definition.path());
            assertArrayEquals(emitted, definition.bytes());
            assertEquals(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(emitted)),
                    definition.sha256());
        }
        var again = new CompleteBackendDefinitionRenderer().render(descriptor, plan);
        for (String path : required) assertArrayEquals(rendered.files().get(path), again.files().get(path));
        var mutable = rendered.files().get(rendered.handoff().definitions().getFirst().path());
        byte original = mutable[0]; mutable[0] ^= 1;
        assertEquals(original, rendered.files().get(rendered.handoff().definitions().getFirst().path())[0]);
        var handoffBytes = rendered.handoff().definitions().getFirst().bytes();
        handoffBytes[0] ^= 1;
        assertEquals(original, rendered.handoff().definitions().getFirst().bytes()[0]);
    }

    @Test void definitionSubsetRejectsMissingExtraAndIncorrectOwnership() throws Exception {
        var descriptor = preflight().describe(version(snapshot("condition")), specification(), OutputMode.BACKEND);
        var plan = GenerationFilePlan.forDescriptor(descriptor);
        var rendered = new CompleteBackendDefinitionRenderer().render(descriptor, plan);
        var handoff = rendered.handoff().specification();
        var missing = new TreeMap<>(rendered.files());
        missing.remove(handoff.definitions().getFirst().path());
        assertThrows(IllegalArgumentException.class, () -> CompleteBackendDefinitionRenderer.validateDefinitions(
                descriptor, plan, handoff, missing));
        var extra = new TreeMap<>(rendered.files());
        extra.put("backend/src/main/java/com/example/inventory/Unexpected.java", new byte[] {1});
        assertThrows(IllegalArgumentException.class, () -> CompleteBackendDefinitionRenderer.validateDefinitions(
                descriptor, plan, handoff, extra));
        var wrongOwner = new GenerationFilePlan(plan.entries().stream().map(entry ->
                entry.path().endsWith("entity/AuditableEntity.java")
                        ? new Entry(entry.path(), Owner.MODEL, entry.purpose()) : entry).toList());
        assertThrows(IllegalArgumentException.class, () -> CompleteBackendDefinitionRenderer.validateDefinitions(
                descriptor, wrongOwner, handoff, rendered.files()));
        var noAudit = new GenerationFilePlan(plan.entries().stream()
                .filter(entry -> !entry.path().endsWith("entity/AuditableEntity.java")).toList());
        assertThrows(IllegalArgumentException.class, () -> CompleteBackendDefinitionRenderer.validateDefinitions(
                descriptor, noAudit, handoff, rendered.files()));
        var withoutDeferred = new GenerationFilePlan(plan.entries().stream()
                .filter(entry -> !entry.path().equals("backend/pom.xml")
                        && !entry.path().endsWith("Controller.java")).toList());
        assertEquals(rendered.files().keySet(), new CompleteBackendDefinitionRenderer()
                .render(descriptor, withoutDeferred).files().keySet());
    }

    @Test void deterministicDefinitionsCompileWithoutFixtureSources(@TempDir Path scratch) throws Exception {
        var descriptor = preflight().describe(version(snapshot("condition")), specification(), OutputMode.BACKEND);
        var rendered = new CompleteBackendDefinitionRenderer().render(descriptor,
                GenerationFilePlan.forDescriptor(descriptor));
        List<String> sources = new ArrayList<>();
        for (var entry : rendered.files().entrySet()) {
            Path file = scratch.resolve(entry.getKey());
            Files.createDirectories(file.getParent());
            Files.write(file, entry.getValue());
            sources.add(file.toString());
        }
        Path classes = Files.createDirectories(scratch.resolve("classes"));
        List<String> arguments = new ArrayList<>(List.of("-classpath", System.getProperty("java.class.path"),
                "-d", classes.toString()));
        arguments.addAll(sources);
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null,
                arguments.toArray(String[]::new)));
    }

    @Test void exactContractAndRenamedQueryProduceStableDescriptorAndHandoff() throws Exception {
        var preflight = preflight();
        var spec = specification();
        var version = version(snapshot("condition"));
        var descriptor = preflight.describe(version, spec, OutputMode.BACKEND);
        assertEquals("stock-unit-api", descriptor.contractId());
        assertEquals("2.0.0", descriptor.contractVersion());
        assertEquals("com.example.inventory.dto", descriptor.contractDtoPackage());
        assertEquals("StockUnitService", descriptor.contractServiceName());
        assertEquals(1, descriptor.metadataVersionNumber());
        assertEquals("/inventory", descriptor.pageRoute());
        assertEquals(64, descriptor.contractResourceSha256().length());
        assertEquals(List.of("OPEN", "CLOSED"), descriptor.editableFields().get("condition").values());
        assertTrue(descriptor.updateRules().get("retiredOn").required());
        assertTrue(descriptor.updateRules().get("retiredOn").nullable());
        assertTrue(descriptor.createRules().get("retiredOn").required());
        assertTrue(descriptor.createFields().get("retiredOn").nullable());
        assertTrue(descriptor.editableFields().get("retiredOn").nullable());
        assertEquals(List.of(CrudAction.LIST, CrudAction.CREATE, CrudAction.READ, CrudAction.UPDATE, CrudAction.DELETE),
                descriptor.operations().stream().map(CompleteOutputDescriptor.Operation::action).toList());
        assertEquals(ids, descriptor.operations().stream().map(CompleteOutputDescriptor.Operation::operationId).toList());
        assertEquals(Set.of("condition", "label"), descriptor.operations().getFirst().queryFields().keySet());
        assertTrue(descriptor.operations().getFirst().queryFields().get("condition").required());
        assertEquals("StockUnitCondition", descriptor.operations().getFirst().queryFields().get("condition").schema().enumName());
        var scalar = descriptor.operations().getFirst().queryFields().get("label");
        assertFalse(scalar.required());
        assertEquals("string", scalar.schema().type());
        assertEquals("", scalar.schema().enumName());
        assertTrue(scalar.schema().values().isEmpty());
        assertEquals(201, descriptor.operations().get(1).successStatus());
        assertEquals("StockUnitDtoResponse", descriptor.operations().get(1).successEnvelope());
        assertEquals(Map.of(400, "StockUnitErrorResponse", 404, "StockUnitErrorResponse"),
                descriptor.operations().get(3).errorEnvelopes());
        assertEquals(descriptor, preflight.describe(version, spec, OutputMode.BACKEND));

        var plan = GenerationFilePlan.forDescriptor(descriptor);
        String modelPath = "backend/src/main/java/com/example/inventory/service/StockUnitServiceImpl.java";
        assertEquals(List.of(modelPath), plan.entries().stream().filter(e -> e.owner() == Owner.MODEL)
                .map(Entry::path).toList());
        assertTrue(plan.entries().stream().anyMatch(e -> e.path().endsWith("UpdateStockUnitRequest.java")
                && e.owner() == Owner.DETERMINISTIC));
        assertTrue(plan.entries().stream().noneMatch(e -> e.path().endsWith("StockUnitLabel.java")));
        var handoff = ModelHandoffSpec.from(descriptor, plan);
        assertEquals(List.of(modelPath), handoff.writablePaths());
        assertEquals(10, handoff.definitions().size());
        assertTrue(handoff.definitions().stream().anyMatch(d -> d.role().equals("audit base")
                && d.path().endsWith("entity/AuditableEntity.java")));
        assertTrue(handoff.definitions().stream().anyMatch(d -> d.role().equals("page DTO")
                && d.path().endsWith("StockUnitPage.java")));
        assertTrue(handoff.definitions().stream().anyMatch(d -> d.role().equals("contract enum")
                && d.path().endsWith("StockUnitCondition.java")));
        assertTrue(handoff.definitions().stream().noneMatch(d -> d.path().endsWith("StockUnitLabel.java")));
        assertTrue(handoff.definitions().stream().allMatch(d -> plan.entries().stream().anyMatch(e ->
                e.path().equals(d.path()) && e.owner() == Owner.DETERMINISTIC)));
        assertEquals("date", handoff.editableFields().get("retiredOn").format());
        assertTrue(handoff.updateRules().get("retiredOn").required());
        assertEquals(descriptor.contractResourceSha256(), handoff.contractResourceSha256());
        assertEquals(List.of("label", "condition", "retiredOn"),
                handoff.fields().stream().map(FieldSpec::name).toList());
        assertTrue(handoff.definitions().stream().noneMatch(d -> d.path().contains("Gadget") || d.path().contains("AssetTicket")));
        var withoutPage = new GenerationFilePlan(plan.entries().stream()
                .filter(e -> !e.path().endsWith("StockUnitPage.java")).toList());
        assertThrows(IllegalArgumentException.class, () -> ModelHandoffSpec.from(descriptor, withoutPage));
        var withoutEnum = new GenerationFilePlan(plan.entries().stream()
                .filter(e -> !e.path().endsWith("StockUnitCondition.java")).toList());
        assertThrows(IllegalArgumentException.class, () -> ModelHandoffSpec.from(descriptor, withoutEnum));
        var withoutAudit = new GenerationFilePlan(plan.entries().stream()
                .filter(e -> !e.path().endsWith("entity/AuditableEntity.java")).toList());
        assertThrows(IllegalArgumentException.class, () -> ModelHandoffSpec.from(descriptor, withoutAudit));
    }

    @Test void unsupportedRequestConstraintsFailBeforeRendering() throws Exception {
        var registry = new PackagedContractRegistry("/generation/stock-unit-constrained-contracts.yaml");
        var preflight = new GenerationCapabilityPreflight(
                new GenerationContextBuilder(mapper, new MetadataSchemaValidator(), registry), registry);
        var failure = assertThrows(GenerationValidationException.class,
                () -> preflight.describe(version(snapshot("condition")), specification(), OutputMode.BACKEND));
        assertTrue(failure.diagnostics().stream().anyMatch(message ->
                message.contains("contract.create.label: unsupported request-field schema")));
        assertTrue(failure.diagnostics().stream().anyMatch(message ->
                message.contains("contract.update.label: unsupported request-field schema")));
    }

    @Test void modesAndPathAliasesHaveExclusiveOwnership() throws Exception {
        var spec = specification();
        var version = version(snapshot("condition"));
        var frontend = GenerationFilePlan.forDescriptor(preflight().describe(version, spec, OutputMode.FRONTEND));
        assertTrue(frontend.entries().stream().noneMatch(e -> e.owner() == Owner.MODEL));
        assertTrue(frontend.entries().stream().anyMatch(e -> e.path().endsWith("stock-unit.behavior.ts")));
        var full = GenerationFilePlan.forDescriptor(preflight().describe(version, spec, OutputMode.FULL_STACK));
        assertEquals(1, full.entries().stream().filter(e -> e.owner() == Owner.MODEL).count());
        assertThrows(IllegalArgumentException.class, () -> new GenerationFilePlan(List.of(
                new Entry("backend/src/App.java", Owner.DETERMINISTIC, "a"),
                new Entry("BACKEND/src/app.java", Owner.MODEL, "b"))));
        assertThrows(IllegalArgumentException.class, () -> new GenerationFilePlan(List.of(
                new Entry("../escape.java", Owner.MODEL, "bad"))));
        assertThrows(IllegalArgumentException.class, () -> new GenerationFilePlan(List.of(
                new Entry("backend/CON.java", Owner.MODEL, "bad"))));
        assertThrows(IllegalArgumentException.class, () -> new GenerationFilePlan(List.of(
                new Entry("backend/file", Owner.MODEL, "file"),
                new Entry("BACKEND/FILE/child.java", Owner.DETERMINISTIC, "child"))));
    }

    @Test void mismatchedQueryFieldAndFieldContractFailBeforeDescriptor() throws Exception {
        var version = version(snapshot("status"));
        var failure = assertThrows(GenerationValidationException.class,
                () -> preflight().describe(version, specification(), OutputMode.BACKEND));
        assertTrue(failure.diagnostics().stream().anyMatch(s -> s.contains("query.condition")));
        var spec = specification();
        var fields = new ArrayList<>(spec.entity().fields());
        fields.set(0, new FieldSpec("label", "label", "Integer", false, null, null));
        var changed = new GenerationSpecification(spec.specVersion(), spec.basePackage(),
                new EntitySpec("StockUnit", "stock_unit", fields, false), spec.openApi(), spec.operationRoles(), spec.angular());
        failure = assertThrows(GenerationValidationException.class,
                () -> preflight().describe(version(snapshot("condition")), changed, OutputMode.BACKEND));
        assertTrue(failure.diagnostics().stream().anyMatch(s -> s.contains("contract.update.label: type or format differs")));
    }

    @Test void descriptorFreezesValidatedUiBindingAuditAndCatalogInputs() throws Exception {
        var snapshot = snapshot("condition");
        addBoundLabel(snapshot, "Stock label");
        var version = version(snapshot);
        var spec = specification();
        var first = preflight().describe(version, spec, OutputMode.FULL_STACK);
        assertTrue(first.validatedSnapshotJson().contains("Stock label"));
        assertTrue(first.validatedSnapshotJson().contains("linkedComponentIds"));
        assertEquals("StockUnit", first.angularClassName());
        assertEquals("spring-angular", first.framework());
        assertFalse(first.catalogAssets().isEmpty());
        assertFalse(first.enversAudited());

        var audited = new GenerationSpecification(spec.specVersion(), spec.basePackage(),
                new EntitySpec(spec.entity().className(), spec.entity().tableName(), spec.entity().fields(), true),
                spec.openApi(), spec.operationRoles(), spec.angular());
        assertNotEquals(first, preflight().describe(version, audited, OutputMode.FULL_STACK));
        var alternateUi = new GenerationSpecification(spec.specVersion(), spec.basePackage(), spec.entity(),
                spec.openApi(), spec.operationRoles(), new AngularSpec("StockUnitView", Map.of()));
        assertNotEquals(first, preflight().describe(version, alternateUi, OutputMode.FULL_STACK));
        var changedSnapshot = snapshot("condition");
        addBoundLabel(changedSnapshot, "Different label");
        assertNotEquals(first, preflight().describe(version(changedSnapshot), spec, OutputMode.FULL_STACK));
        var changedPage = version(snapshot);
        changedPage.getPage().setRoute("/different-inventory");
        assertNotEquals(first, preflight().describe(changedPage, spec, OutputMode.FULL_STACK));
    }

    @Test void equivalentMapInsertionOrdersCanonicalizeAndInputsCannotMutateDescriptor() throws Exception {
        var spec = specification();
        var firstSnapshot = snapshot("condition");
        var first = preflight().describe(version(firstSnapshot), spec, OutputMode.FULL_STACK);
        Map<UUID, String> reversedBindings = new java.util.LinkedHashMap<>();
        Map<String, Set<SupportedRole>> reversedRoles = new java.util.LinkedHashMap<>();
        for (int i = ids.size() - 1; i >= 0; i--) {
            reversedBindings.put(new UUID(0, i + 1), ids.get(i));
            reversedRoles.put(ids.get(i), Set.of(SupportedRole.ROLE_ADMIN));
        }
        var reordered = new GenerationSpecification(spec.specVersion(), spec.basePackage(), spec.entity(),
                new OpenApiSpec(spec.openApi().contractId(), spec.openApi().contractVersion(), reversedBindings),
                reversedRoles, spec.angular());
        var reorderedSnapshot = mapper.createObjectNode();
        reorderedSnapshot.set("apiBindings", firstSnapshot.path("apiBindings").deepCopy());
        reorderedSnapshot.set("components", firstSnapshot.path("components").deepCopy());
        var equivalent = preflight().describe(version(reorderedSnapshot), reordered, OutputMode.FULL_STACK);
        assertEquals(first, equivalent);
        assertEquals(GenerationFilePlan.forDescriptor(first), GenerationFilePlan.forDescriptor(equivalent));
        assertEquals(ModelHandoffSpec.from(first, GenerationFilePlan.forDescriptor(first)),
                ModelHandoffSpec.from(equivalent, GenerationFilePlan.forDescriptor(equivalent)));
        var renderer = new CompleteBackendDefinitionRenderer();
        var firstRendered = renderer.render(first, GenerationFilePlan.forDescriptor(first));
        var equivalentRendered = renderer.render(equivalent, GenerationFilePlan.forDescriptor(equivalent));
        assertEquals(firstRendered.files().keySet(), equivalentRendered.files().keySet());
        for (String path : firstRendered.files().keySet())
            assertArrayEquals(firstRendered.files().get(path), equivalentRendered.files().get(path));

        var mutableFields = new ArrayList<>(spec.entity().fields());
        var mutableBindings = new java.util.LinkedHashMap<>(spec.openApi().bindingOperations());
        var mutableRoles = new java.util.LinkedHashMap<>(spec.operationRoles());
        var mutableColumns = new java.util.LinkedHashMap<UUID, List<String>>();
        var mutable = new GenerationSpecification(spec.specVersion(), spec.basePackage(),
                new EntitySpec("StockUnit", "stock_unit", mutableFields, false),
                new OpenApiSpec("stock-unit-api", "2.0.0", mutableBindings), mutableRoles,
                new AngularSpec("StockUnit", mutableColumns));
        var mutableVersion = version(snapshot("condition"));
        var frozen = preflight().describe(mutableVersion, mutable, OutputMode.FULL_STACK);
        mutableFields.clear(); mutableBindings.clear(); mutableRoles.clear();
        mutableColumns.put(new UUID(0, 77), new ArrayList<>(List.of("label")));
        mutableVersion.setSnapshot("{}");
        assertEquals(3, frozen.fields().size());
        assertEquals(5, frozen.operations().size());
        assertTrue(frozen.angularTableColumns().isEmpty());
        assertTrue(frozen.validatedSnapshotJson().contains("stock-units"));
        assertThrows(UnsupportedOperationException.class, () -> frozen.fields().clear());
        assertThrows(UnsupportedOperationException.class, () -> frozen.catalogAssets().clear());
        assertThrows(UnsupportedOperationException.class, () -> frozen.angularTableColumns().clear());
    }

    @Test void unsupportedQueryAndPathParameterShapesFailExplicitly() {
        Map<String, Object> schemas = Map.of("Condition", Map.of("type", "string", "enum", List.of("OPEN", "CLOSED")),
                "Label", Map.of("type", "string"));
        List<String> errors = new ArrayList<>();
        var valid = PackagedContractLoader.parseQueryParameters(List.of(Map.of(
                "name", "condition", "in", "query", "required", true,
                "schema", Map.of("$ref", "#/components/schemas/Condition")), Map.of(
                "name", "label", "in", "query", "required", false,
                "schema", Map.of("$ref", "#/components/schemas/Label"))),
                CrudAction.LIST, schemas, "scanStockUnits", errors);
        assertTrue(errors.isEmpty(), errors.toString());
        assertTrue(valid.get("condition").required());
        assertEquals("Condition", valid.get("condition").schema().enumName());
        assertFalse(valid.get("label").required());
        assertEquals("", valid.get("label").schema().enumName());

        var unsupported = List.of(
                Map.of("name", "tags", "in", "query", "schema", Map.of("type", "array", "items", Map.of("type", "string"))),
                Map.of("name", "condition", "in", "header", "schema", Map.of("type", "string")),
                Map.of("name", "condition", "in", "query", "style", "deepObject", "schema", Map.of("type", "string")),
                Map.of("name", "condition", "in", "query", "schema", Map.of("type", "string", "enum", List.of(1, 2))));
        var rejected = PackagedContractLoader.parseQueryParameters(unsupported, CrudAction.LIST,
                schemas, "scanStockUnits", errors);
        assertTrue(rejected.isEmpty());
        assertEquals(4, errors.size(), errors.toString());
        assertTrue(errors.stream().allMatch(e -> e.contains("unsupported parameter")));
        PackagedContractLoader.validatePathParameters("/api/v1/stock-units/{id}", List.of(Map.of(
                "name", "id", "in", "path", "required", false,
                "schema", Map.of("type", "string", "format", "uuid"))), errors);
        assertTrue(errors.stream().anyMatch(e -> e.contains("unsupported path parameter shape")));
    }

    private void addBoundLabel(ObjectNode snapshot, String label) {
        UUID id = new UUID(0, 100);
        ObjectNode component = snapshot.withArray("components").addObject();
        component.put("id", id.toString()); component.put("type", "text-field");
        component.put("fieldKey", "label"); component.put("label", label);
        component.putObject("position").put("row", 0).put("col", 0);
        component.putObject("size").put("width", 6).put("height", 1);
        component.put("visibility", true); component.put("disabled", false);
        ((ObjectNode) snapshot.withArray("apiBindings").get(0)).putArray("linkedComponentIds").add(id.toString());
    }

    private GenerationCapabilityPreflight preflight() {
        var registry = new PackagedContractRegistry("/generation/stock-unit-contracts.yaml");
        return new GenerationCapabilityPreflight(
                new GenerationContextBuilder(mapper, new MetadataSchemaValidator(), registry), registry);
    }

    private GenerationSpecification specification() {
        Map<UUID, String> bindings = new java.util.LinkedHashMap<>();
        Map<String, Set<SupportedRole>> roles = new java.util.LinkedHashMap<>();
        for (int i = 0; i < ids.size(); i++) {
            bindings.put(new UUID(0, i + 1), ids.get(i));
            roles.put(ids.get(i), Set.of(SupportedRole.ROLE_ADMIN));
        }
        return new GenerationSpecification("1.0.0", "com.example.inventory",
                new EntitySpec("StockUnit", "stock_unit", List.of(
                        new FieldSpec("label", "label", "String", false, 100, null),
                        new FieldSpec("condition", "condition", "StockUnitCondition", false, null, "StockUnitCondition"),
                        new FieldSpec("retiredOn", "retired_on", "LocalDate", true, null, null)), false),
                new OpenApiSpec("stock-unit-api", "2.0.0", bindings), roles,
                new AngularSpec("StockUnit", Map.of()));
    }

    private ObjectNode snapshot(String queryName) {
        var snapshot = mapper.createObjectNode();
        snapshot.putArray("components");
        var bindings = snapshot.putArray("apiBindings");
        String[] verbs = {"GET", "POST", "GET", "PUT", "DELETE"};
        for (int i = 0; i < verbs.length; i++) {
            ObjectNode binding = bindings.addObject();
            binding.put("id", new UUID(0, i + 1).toString());
            binding.put("httpMethod", verbs[i]);
            binding.put("endpointUrl", "/api/v1/stock-units" + (i > 1 ? "/{id}" : ""));
            binding.putObject("headers");
            ObjectNode request = binding.putObject("requestMapping");
            if (i == 0) request.putObject("query").put(queryName, queryName).put("label", "label");
            if (i > 1) request.putObject("path").put("id", "id");
            if (i == 1 || i == 3) request.putObject("body")
                    .put("label", "label").put("condition", "condition").put("retiredOn", "retiredOn");
            ObjectNode response = binding.putObject("responseMapping");
            if (i == 0) response.put("list", "body.data"); else response.put("item", "body");
        }
        return snapshot;
    }

    private MetadataVersion version(ObjectNode snapshot) throws Exception {
        var project = new GsuifProject(); project.setName("Fixture");
        var page = new GsuifPage(); page.setProject(project);
        page.setName("Inventory"); page.setRoute("/inventory");
        var version = new MetadataVersion(); version.setPage(page);
        version.setVersion(1); version.setSchemaVersion("1.0.0");
        version.setSnapshot(mapper.writeValueAsString(snapshot));
        return version;
    }
}
