package eg.mts.gsuif.generation;

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

class CompleteOutputDescriptorContractTest {
    private final CompleteOutputTestFixture fixture = new CompleteOutputTestFixture();

    @Test void backendDefinitionsUseOnlyRequiredSubsetAndBindExactBytes() throws Exception {
        var descriptor = fixture.preflight().describe(fixture.version(fixture.snapshot("condition")), fixture.specification(), OutputMode.BACKEND);
        var plan = GenerationFilePlan.forDescriptor(descriptor);
        var rendered = new CompleteBackendDefinitionRenderer().render(descriptor, plan);
        var required = CompleteBackendDefinitionRenderer.requiredDefinitionPaths(descriptor,
                rendered.handoff().specification());
        assertEquals(required, rendered.files().keySet());
        assertEquals(rendered.handoff().definitions().size(), required.size() + 1);
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
        assertEquals(fixture.ids, rendered.handoff().specification().operations().stream().map(
                CompleteOutputDescriptor.Operation::operationId).toList());
        assertTrue(all.contains("StockUnitPage list(com.example.inventory.dto.StockUnitCondition condition, String label)"));
        assertTrue(all.contains("required = true"));
        assertTrue(all.contains("java.time.LocalDate retiredOn"));
        assertTrue(all.contains("enum StockUnitCondition { OPEN, CLOSED }"));
        assertFalse(rendered.files().keySet().stream().anyMatch(p -> p.endsWith("StockUnitLabel.java")));
        var authoritative = new TreeMap<>(rendered.files());
        String exceptionPath = ModelHandoffSpec.requestedRecordNotFoundPath(descriptor);
        authoritative.put(exceptionPath, new CompleteBackendErrorRenderer().render(descriptor, plan).get(exceptionPath));
        assertEquals(authoritative.keySet(), rendered.handoff().definitions().stream()
                .map(RenderedModelHandoff.Definition::path).collect(java.util.stream.Collectors.toSet()));
        for (var definition : rendered.handoff().definitions()) {
            byte[] emitted = authoritative.get(definition.path());
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

    @Test void missingRecordExceptionHandoffRejectsMissingOrWrongOwnershipAndDefendsBytes() throws Exception {
        var descriptor = fixture.preflight().describe(fixture.version(fixture.snapshot("condition")),
                fixture.specification(), OutputMode.BACKEND);
        var plan = GenerationFilePlan.forDescriptor(descriptor);
        var rendered = new CompleteBackendDefinitionRenderer().render(descriptor, plan);
        String path = ModelHandoffSpec.requestedRecordNotFoundPath(descriptor);
        var definition = rendered.handoff().definitions().stream()
                .filter(item -> item.path().equals(path)).findFirst().orElseThrow();
        assertEquals("missing-record exception", definition.role());
        assertFalse(rendered.files().containsKey(path));
        byte[] emitted = new CompleteBackendErrorRenderer().render(descriptor, plan).get(path);
        assertArrayEquals(emitted, definition.bytes());
        assertEquals(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(emitted)),
                definition.sha256());
        assertThrows(IllegalArgumentException.class,
                () -> RenderedModelHandoff.from(rendered.handoff().specification(), rendered.files()));
        var absent = new GenerationFilePlan(plan.entries().stream().filter(entry -> !entry.path().equals(path)).toList());
        assertThrows(IllegalArgumentException.class, () -> ModelHandoffSpec.from(descriptor, absent));
        assertThrows(IllegalArgumentException.class, () -> new CompleteBackendDefinitionRenderer().render(descriptor, absent));
        var wrongOwner = new GenerationFilePlan(plan.entries().stream().map(entry -> entry.path().equals(path)
                ? new Entry(entry.path(), Owner.MODEL, entry.purpose()) : entry).toList());
        assertThrows(IllegalArgumentException.class, () -> ModelHandoffSpec.from(descriptor, wrongOwner));
        assertThrows(IllegalArgumentException.class,
                () -> new CompleteBackendDefinitionRenderer().render(descriptor, wrongOwner));
        byte first = definition.bytes()[0];
        byte[] mutable = definition.bytes();
        mutable[0] ^= 1;
        assertEquals(first, definition.bytes()[0]);
        emitted[0] ^= 1;
        assertEquals(first, definition.bytes()[0]);
    }

    @Test void definitionSubsetRejectsMissingExtraAndIncorrectOwnership() throws Exception {
        var descriptor = fixture.preflight().describe(fixture.version(fixture.snapshot("condition")), fixture.specification(), OutputMode.BACKEND);
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
        var descriptor = fixture.preflight().describe(fixture.version(fixture.snapshot("condition")), fixture.specification(), OutputMode.BACKEND);
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
        var preflight = fixture.preflight();
        var spec = fixture.specification();
        var version = fixture.version(fixture.snapshot("condition"));
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
        assertEquals(fixture.ids, descriptor.operations().stream().map(CompleteOutputDescriptor.Operation::operationId).toList());
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
        assertEquals(11, handoff.definitions().size());
        assertTrue(handoff.definitions().stream().anyMatch(d -> d.role().equals("audit base")
                && d.path().endsWith("entity/AuditableEntity.java")));
        assertTrue(handoff.definitions().stream().anyMatch(d -> d.role().equals("page DTO")
                && d.path().endsWith("StockUnitPage.java")));
        assertTrue(handoff.definitions().stream().anyMatch(d -> d.role().equals("contract enum")
                && d.path().endsWith("StockUnitCondition.java")));
        assertTrue(handoff.definitions().stream().anyMatch(d -> d.role().equals("missing-record exception")
                && d.path().endsWith("exception/RequestedRecordNotFoundException.java")));
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

    @Test void registeredContractRejectsJavaKeywordQueryAndOperationNames() throws Exception {
        var queryRegistry = new PackagedContractRegistry(
                "/generation/stock-unit-keyword-query-contracts.yaml");
        var queryPreflight = new GenerationCapabilityPreflight(
                new GenerationContextBuilder(fixture.mapper, new MetadataSchemaValidator(), queryRegistry), queryRegistry);
        var invalidQuery = assertThrows(GenerationValidationException.class,
                () -> queryPreflight.describe(fixture.version(fixture.snapshot("class")), fixture.specification(), OutputMode.BACKEND));
        assertTrue(invalidQuery.getMessage().contains("parameters.class: unsupported parameter shape"),
                invalidQuery.getMessage());
        assertThrows(IllegalStateException.class, () -> new PackagedContractRegistry(
                "/generation/stock-unit-keyword-operation-contracts.yaml"));
        var valid = fixture.preflight().describe(fixture.version(fixture.snapshot("condition")), fixture.specification(), OutputMode.BACKEND);
        assertTrue(new CompleteBackendApiRenderer().render(valid, GenerationFilePlan.forDescriptor(valid))
                .keySet().stream().anyMatch(path -> path.endsWith("StockUnitApi.java")));
    }

    @Test void unsupportedRequestConstraintsFailBeforeRendering() throws Exception {
        var registry = new PackagedContractRegistry("/generation/stock-unit-constrained-contracts.yaml");
        var preflight = new GenerationCapabilityPreflight(
                new GenerationContextBuilder(fixture.mapper, new MetadataSchemaValidator(), registry), registry);
        var failure = assertThrows(GenerationValidationException.class,
                () -> preflight.describe(fixture.version(fixture.snapshot("condition")), fixture.specification(), OutputMode.BACKEND));
        assertTrue(failure.diagnostics().stream().anyMatch(message ->
                message.contains("contract.create.label: unsupported request-field schema")));
        assertTrue(failure.diagnostics().stream().anyMatch(message ->
                message.contains("contract.update.label: unsupported request-field schema")));
    }

    @Test void modesAndPathAliasesHaveExclusiveOwnership() throws Exception {
        var spec = fixture.specification();
        var version = fixture.version(fixture.snapshot("condition"));
        var frontend = GenerationFilePlan.forDescriptor(fixture.preflight().describe(version, spec, OutputMode.FRONTEND));
        assertTrue(frontend.entries().stream().noneMatch(e -> e.owner() == Owner.MODEL));
        assertTrue(frontend.entries().stream().anyMatch(e -> e.path().endsWith("stock-unit.behavior.ts")));
        var full = GenerationFilePlan.forDescriptor(fixture.preflight().describe(version, spec, OutputMode.FULL_STACK));
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
        var version = fixture.version(fixture.snapshot("status"));
        var failure = assertThrows(GenerationValidationException.class,
                () -> fixture.preflight().describe(version, fixture.specification(), OutputMode.BACKEND));
        assertTrue(failure.diagnostics().stream().anyMatch(s -> s.contains("query.condition")));
        var spec = fixture.specification();
        var fields = new ArrayList<>(spec.entity().fields());
        fields.set(0, new FieldSpec("label", "label", "Integer", false, null, null));
        var changed = new GenerationSpecification(spec.specVersion(), spec.basePackage(),
                new EntitySpec("StockUnit", "stock_unit", fields, false), spec.openApi(), spec.operationRoles(), spec.angular());
        failure = assertThrows(GenerationValidationException.class,
                () -> fixture.preflight().describe(fixture.version(fixture.snapshot("condition")), changed, OutputMode.BACKEND));
        assertTrue(failure.diagnostics().stream().anyMatch(s -> s.contains("contract.update.label: type or format differs")));
    }

    @Test void descriptorFreezesValidatedUiBindingAuditAndCatalogInputs() throws Exception {
        var snapshot = fixture.snapshot("condition");
        fixture.addBoundLabel(snapshot, "Stock label");
        var version = fixture.version(snapshot);
        var spec = fixture.specification();
        var first = fixture.preflight().describe(version, spec, OutputMode.FULL_STACK);
        assertTrue(first.validatedSnapshotJson().contains("Stock label"));
        assertTrue(first.validatedSnapshotJson().contains("linkedComponentIds"));
        assertEquals("StockUnit", first.angularClassName());
        assertEquals("spring-angular", first.framework());
        assertFalse(first.catalogAssets().isEmpty());
        assertFalse(first.enversAudited());

        var audited = new GenerationSpecification(spec.specVersion(), spec.basePackage(),
                new EntitySpec(spec.entity().className(), spec.entity().tableName(), spec.entity().fields(), true),
                spec.openApi(), spec.operationRoles(), spec.angular());
        assertNotEquals(first, fixture.preflight().describe(version, audited, OutputMode.FULL_STACK));
        var alternateUi = new GenerationSpecification(spec.specVersion(), spec.basePackage(), spec.entity(),
                spec.openApi(), spec.operationRoles(), new AngularSpec("StockUnitView", Map.of()));
        assertNotEquals(first, fixture.preflight().describe(version, alternateUi, OutputMode.FULL_STACK));
        var changedSnapshot = fixture.snapshot("condition");
        fixture.addBoundLabel(changedSnapshot, "Different label");
        assertNotEquals(first, fixture.preflight().describe(fixture.version(changedSnapshot), spec, OutputMode.FULL_STACK));
        var changedPage = fixture.version(snapshot);
        changedPage.getPage().setRoute("/different-inventory");
        assertNotEquals(first, fixture.preflight().describe(changedPage, spec, OutputMode.FULL_STACK));
    }

    @Test void equivalentMapInsertionOrdersCanonicalizeAndInputsCannotMutateDescriptor() throws Exception {
        var spec = fixture.specification();
        var firstSnapshot = fixture.snapshot("condition");
        var first = fixture.preflight().describe(fixture.version(firstSnapshot), spec, OutputMode.FULL_STACK);
        Map<UUID, String> reversedBindings = new java.util.LinkedHashMap<>();
        Map<String, Set<SupportedRole>> reversedRoles = new java.util.LinkedHashMap<>();
        for (int i = fixture.ids.size() - 1; i >= 0; i--) {
            reversedBindings.put(new UUID(0, i + 1), fixture.ids.get(i));
            reversedRoles.put(fixture.ids.get(i), Set.of(SupportedRole.ROLE_ADMIN));
        }
        var reordered = new GenerationSpecification(spec.specVersion(), spec.basePackage(), spec.entity(),
                new OpenApiSpec(spec.openApi().contractId(), spec.openApi().contractVersion(), reversedBindings),
                reversedRoles, spec.angular());
        var reorderedSnapshot = fixture.mapper.createObjectNode();
        reorderedSnapshot.set("apiBindings", firstSnapshot.path("apiBindings").deepCopy());
        reorderedSnapshot.set("components", firstSnapshot.path("components").deepCopy());
        var equivalent = fixture.preflight().describe(fixture.version(reorderedSnapshot), reordered, OutputMode.FULL_STACK);
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
        var runtime = new CompleteBackendRuntimeRenderer(new PackagedContractRegistry(
                "/generation/stock-unit-contracts.yaml"));
        var firstRuntime = runtime.render(first, GenerationFilePlan.forDescriptor(first));
        var equivalentRuntime = runtime.render(equivalent, GenerationFilePlan.forDescriptor(equivalent));
        assertEquals(firstRuntime.keySet(), equivalentRuntime.keySet());
        for (String path : firstRuntime.keySet())
            assertArrayEquals(firstRuntime.get(path), equivalentRuntime.get(path));

        var mutableFields = new ArrayList<>(spec.entity().fields());
        var mutableBindings = new java.util.LinkedHashMap<>(spec.openApi().bindingOperations());
        var mutableRoles = new java.util.LinkedHashMap<>(spec.operationRoles());
        var mutableColumns = new java.util.LinkedHashMap<UUID, List<String>>();
        var mutable = new GenerationSpecification(spec.specVersion(), spec.basePackage(),
                new EntitySpec("StockUnit", "stock_unit", mutableFields, false),
                new OpenApiSpec("stock-unit-api", "2.0.0", mutableBindings), mutableRoles,
                new AngularSpec("StockUnit", mutableColumns));
        var mutableVersion = fixture.version(fixture.snapshot("condition"));
        var frozen = fixture.preflight().describe(mutableVersion, mutable, OutputMode.FULL_STACK);
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

}
