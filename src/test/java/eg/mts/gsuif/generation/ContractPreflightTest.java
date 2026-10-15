package eg.mts.gsuif.generation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import eg.mts.gsuif.entity.GsuifPage;
import eg.mts.gsuif.entity.GsuifProject;
import eg.mts.gsuif.entity.GsuifUser;
import eg.mts.gsuif.entity.MetadataVersion;
import eg.mts.gsuif.generation.GenerationSpecification.*;
import eg.mts.gsuif.validator.MetadataSchemaValidator;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ContractPreflightTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test void oldAndFullUpdateVersionsCoexistWithoutRedirect() {
        var registry = new PackagedContractRegistry();
        assertEquals("/openapi/asset-ticket-api.yaml", registry.resource("asset-ticket-api", "1.0.0"));
        assertEquals("/openapi/asset-ticket-api-2.yaml", registry.resource("asset-ticket-api", "2.0.0"));
        assertNull(registry.find("asset-ticket-api", "3.0.0"));
        assertNull(registry.resource("asset-ticket-api", null));
        var loader = new PackagedContractLoader(registry);
        var errors = new ArrayList<String>();
        var legacy = loader.load(new OpenApiSpec("asset-ticket-api", "1.0.0", Map.of()), errors);
        var full = loader.load(new OpenApiSpec("asset-ticket-api", "2.0.0", Map.of()), errors);
        assertTrue(errors.isEmpty(), errors.toString());
        assertFalse(legacy.descriptor().fullUpdate());
        assertFalse(legacy.updateFields().get("title").required());
        assertTrue(full.descriptor().fullUpdate());
        assertTrue(full.updateFields().get("title").required());
        assertTrue(full.updateFields().get("status").required());
        assertFalse(full.operations().get("listAssetTickets").queryFields().get("status").required());
        assertFalse(full.updateFields().get("title").nullable());
        assertFalse(full.updateFields().get("status").nullable());
    }

    @Test void legacyFragmentContextRemainsValidButCompleteOutputRequiresExplicitNewVersion() throws Exception {
        var registry = new PackagedContractRegistry();
        var snapshot = bindings();
        for (var binding : snapshot.withArray("apiBindings")) {
            ((ObjectNode) binding).put("endpointUrl", binding.path("endpointUrl").asText().replace("gadgets", "asset-tickets"));
            if (binding.path("requestMapping").path("body") instanceof ObjectNode body) body.remove("note");
        }
        List<String> ids = List.of("listAssetTickets", "createAssetTicket", "getAssetTicketById",
                "updateAssetTicket", "deleteAssetTicket");
        Map<UUID, String> bindings = new LinkedHashMap<>();
        Map<String, Set<SupportedRole>> roles = new LinkedHashMap<>();
        for (int i = 0; i < ids.size(); i++) {
            bindings.put(new UUID(0, i + 1), ids.get(i));
            roles.put(ids.get(i), Set.of(SupportedRole.ROLE_ADMIN));
        }
        var entity = new EntitySpec("AssetTicket", "asset_ticket", List.of(
                new FieldSpec("title", "title", "String", false, 100, null),
                new FieldSpec("status", "status", "AssetTicketStatus", false, null, "AssetTicketStatus")), false);
        var metadata = version(snapshot);
        var legacy = new GenerationSpecification("1.0.0", "com.example.fixture", entity,
                new OpenApiSpec("asset-ticket-api", "1.0.0", bindings), roles, new AngularSpec("AssetTicket", Map.of()));
        assertNotNull(builder(registry).build(metadata, legacy));
        var preflight = new GenerationCapabilityPreflight(builder(registry), registry);
        var rejected = assertThrows(GenerationValidationException.class,
                () -> preflight.describe(metadata, legacy, GenerationCapabilityPreflight.OutputMode.BACKEND));
        assertTrue(rejected.diagnostics().stream().anyMatch(s -> s.contains("full-update contract profile")),
                rejected.diagnostics().toString());
        var full = new GenerationSpecification(legacy.specVersion(), legacy.basePackage(), entity,
                new OpenApiSpec("asset-ticket-api", "2.0.0", bindings), roles, legacy.angular());
        var descriptor = preflight.describe(metadata, full, GenerationCapabilityPreflight.OutputMode.BACKEND);
        assertEquals("2.0.0", descriptor.contractVersion());
        var schema = CompleteBackendEntitySchema.from(descriptor);
        assertEquals("asset_ticket", schema.tableName());
        assertEquals(List.of("id", "status", "title", "created_at", "updated_at", "created_by",
                "last_modified_by"), schema.columns().stream().map(CompleteBackendEntitySchema.Column::name).toList());
        assertEquals("varchar(100)", schema.columns().get(2).sqlType());
        assertEquals(List.of("OPEN", "CLOSED"), schema.constraints().get(0).enumValues());
        assertTrue(schema.sqlFragment().contains("ck_asset_ticket_status_enum"));
        assertFalse(schema.sqlFragment().contains("StockUnit"));
        var assetMigration = new CompleteBackendInitialMigrationRenderer()
                .render(descriptor, GenerationFilePlan.forDescriptor(descriptor));
        String assetSql = new String(assetMigration.get("backend/src/main/resources/db/migration/V1__initial_schema.sql"),
                java.nio.charset.StandardCharsets.UTF_8);
        assertTrue(assetSql.startsWith(schema.sqlFragment()));
        assertFalse(assetSql.contains("StockUnit") || assetSql.contains("Gadget") || assetSql.contains("WorkOrder"));
        java.nio.file.Files.createDirectories(java.nio.file.Path.of("target/v1-review-examples"));
        java.nio.file.Files.writeString(java.nio.file.Path.of("target/v1-review-examples/AssetTicket.sql"),
                assetSql, java.nio.charset.StandardCharsets.UTF_8);
        var rendered = new CompleteBackendDefinitionRenderer().render(descriptor, GenerationFilePlan.forDescriptor(descriptor));
        String sources = rendered.files().values().stream()
                .map(bytes -> new String(bytes, java.nio.charset.StandardCharsets.UTF_8)).reduce("", String::concat);
        assertTrue(sources.contains("AssetTicketService"));
        assertTrue(sources.contains("AssetTicketStatus"));
        assertFalse(sources.contains("StockUnit"));
        assertFalse(sources.contains("eg.mts.gsuif"));
        var transport = new CompleteBackendApiRenderer().render(descriptor, GenerationFilePlan.forDescriptor(descriptor));
        String transportSource = transport.values().stream()
                .map(bytes -> new String(bytes, java.nio.charset.StandardCharsets.UTF_8)).reduce("", String::concat);
        assertTrue(transportSource.contains("listAssetTickets("));
        assertTrue(transportSource.contains("createAssetTicket("));
        assertTrue(transportSource.contains("deleteAssetTicket("));
        assertTrue(transportSource.contains("service.delete(id)"));
        assertTrue(transportSource.contains("name = \"status\", required = false"));
        assertFalse(transportSource.contains("StockUnit"));
        var scaffold = new CompleteBackendRuntimeRenderer(registry)
                .render(descriptor, GenerationFilePlan.forDescriptor(descriptor));
        assertTrue(scaffold.containsKey("contracts/asset-ticket-api-2.0.0.yaml"));
        String runtimeSources = scaffold.entrySet().stream()
                .filter(e -> !e.getKey().startsWith("contracts/"))
                .map(e -> new String(e.getValue(), java.nio.charset.StandardCharsets.UTF_8))
                .reduce("", String::concat);
        assertTrue(runtimeSources.contains("asset-ticket-generated-backend"));
        assertFalse(runtimeSources.contains("StockUnit"));
    }

    @Test void renamedOperationsAndNullableFieldPassAllOutputPreflights() throws Exception {
        var registry = gadgetRegistry();
        var builder = builder(registry);
        var preflight = new GenerationCapabilityPreflight(builder, registry);
        var snapshot = bindings();
        var spec = gadgetSpec(true);
        var version = version(snapshot);
        for (var mode : GenerationCapabilityPreflight.OutputMode.values())
            assertNotNull(preflight.check(version, spec, mode));
        var errors = new ArrayList<String>();
        var contract = new PackagedContractLoader(registry).load(spec.openApi(), errors);
        assertTrue(errors.isEmpty(), errors.toString());
        assertEquals(PackagedContractRegistry.CrudAction.LIST, contract.operations().get("fetchGadgets").action());
        assertEquals("list", contract.operations().get("fetchGadgets").kind());
        assertTrue(contract.updateFields().get("note").required());
        assertTrue(contract.updateFields().get("note").nullable());
    }

    @Test void invalidMappingsAndVersionsFailClosed() throws Exception {
        assertThrows(IllegalStateException.class,
                () -> new PackagedContractRegistry("/generation/gadget-missing-operation.yaml"));
        assertThrows(IllegalStateException.class,
                () -> new PackagedContractRegistry("/generation/gadget-duplicate-operation.yaml"));
        var wrong = new PackagedContractRegistry("/generation/gadget-wrong-method.yaml");
        var errors = new ArrayList<String>();
        new PackagedContractLoader(wrong).load(new OpenApiSpec("gadget-api", "2.0.0", Map.of()), errors);
        assertTrue(errors.stream().anyMatch(s -> s.contains("CRUD method mismatch")), errors.toString());
        var unsupported = new GenerationSpecification("1.0.0", "com.example.fixture", gadgetSpec(true).entity(),
                new OpenApiSpec("gadget-api", "1.0.0", Map.of()), Map.of(), new AngularSpec("Gadget", Map.of()));
        var error = assertThrows(GenerationValidationException.class,
                () -> builder(gadgetRegistry()).build(version(bindings()), unsupported));
        assertTrue(error.diagnostics().stream().anyMatch(s -> s.contains("contractVersion: unsupported packaged version")));
    }

    @Test void fullUpdateCannotTreatOptionalLegacyFieldsAsRequired() {
        var registry = new PackagedContractRegistry("/generation/invalid-full-update.yaml");
        var errors = new ArrayList<String>();
        new PackagedContractLoader(registry).load(new OpenApiSpec("asset-ticket-api", "1.0.0", Map.of()), errors);
        assertTrue(errors.stream().anyMatch(s -> s.contains("every editable field must be required")), errors.toString());
        assertTrue(errors.stream().anyMatch(s -> s.contains("404 five-field error envelope required")), errors.toString());
    }

    @Test void selectedVersionAndResourceBytesChangeAttemptFingerprint() {
        MetadataVersion metadata = mock(MetadataVersion.class);
        GsuifUser user = mock(GsuifUser.class);
        when(metadata.getId()).thenReturn(new UUID(0, 101));
        when(metadata.getSchemaVersion()).thenReturn("1.0.0");
        when(metadata.getSnapshot()).thenReturn("{\"components\":[],\"apiBindings\":[]}");
        when(user.getId()).thenReturn(new UUID(0, 102));
        var legacy = withAssetVersion("1.0.0");
        var full = withAssetVersion("2.0.0");
        var targets = Set.of(GenerationContext.Target.ENTITY);
        String first = GenerationAttemptFingerprint.request(metadata, user, legacy, targets, "spring-angular", Map.of());
        String second = GenerationAttemptFingerprint.request(metadata, user, full, targets, "spring-angular", Map.of());
        assertNotEquals(first, second);
        String changedFullResource = GenerationAttemptFingerprint.request(metadata, user, full, targets, "spring-angular", Map.of(),
                Map.of("openapi/asset-ticket-api-2.yaml", new byte[] {42}));
        assertNotEquals(second, changedFullResource);
        String unrelatedLegacyResource = GenerationAttemptFingerprint.request(metadata, user, full, targets, "spring-angular", Map.of(),
                Map.of("openapi/asset-ticket-api.yaml", new byte[] {42}));
        assertEquals(second, unrelatedLegacyResource);
    }

    @Test void legacyBuildInputsDoNotSilentlyUseOldContractForNewVersion() {
        assertThrows(GenerationValidationException.class, () -> PackagedConsumerInputs.forBuild(
                withAssetVersion("2.0.0"), Set.of(GenerationContext.Target.CONTROLLER)));
        assertFalse(PackagedConsumerInputs.forBuild(withAssetVersion("1.0.0"),
                Set.of(GenerationContext.Target.CONTROLLER)).isEmpty());
    }

    @Test void relationshipsAndNullabilityMismatchAreRejectedBeforeGeneration() throws Exception {
        var registry = gadgetRegistry();
        var preflight = new GenerationCapabilityPreflight(builder(registry), registry);
        var spec = gadgetSpec(true);
        var mismatch = gadgetSpec(false);
        var error = assertThrows(GenerationValidationException.class,
                () -> preflight.check(version(bindings()), mismatch, GenerationCapabilityPreflight.OutputMode.FULL_STACK));
        assertTrue(error.diagnostics().stream().anyMatch(s -> s.contains("contract.update.note: nullability differs")));
        var fields = new ArrayList<>(spec.entity().fields());
        fields.add(new FieldSpec("related", "related", "List<Gadget>", false, null, null));
        var relationship = new GenerationSpecification(spec.specVersion(), spec.basePackage(),
                new EntitySpec("Gadget", "gadget", fields, false), spec.openApi(), spec.operationRoles(), spec.angular());
        error = assertThrows(GenerationValidationException.class,
                () -> preflight.check(version(bindings()), relationship, GenerationCapabilityPreflight.OutputMode.BACKEND));
        assertTrue(error.diagnostics().stream().anyMatch(s -> s.contains("unsupported type")));
    }

    @Test void backendRejectsContractStringMappedToIntegerWithoutAngularComponents() throws Exception {
        var spec = gadgetSpec(true);
        var fields = new ArrayList<>(spec.entity().fields());
        fields.set(0, new FieldSpec("title", "title", "Integer", false, null, null));
        var changed = new GenerationSpecification(spec.specVersion(), spec.basePackage(),
                new EntitySpec("Gadget", "gadget", fields, false), spec.openApi(),
                spec.operationRoles(), spec.angular());
        var error = assertThrows(GenerationValidationException.class,
                () -> new GenerationCapabilityPreflight(builder(gadgetRegistry()), gadgetRegistry())
                        .check(version(bindings()), changed, GenerationCapabilityPreflight.OutputMode.BACKEND));
        assertTrue(error.diagnostics().stream().anyMatch(s -> s.contains("contract.update.title: type or format differs")),
                error.diagnostics().toString());
        assertTrue(error.diagnostics().stream().anyMatch(s -> s.contains("contract.dto.title: type or format differs")),
                error.diagnostics().toString());
    }

    @Test void backendChecksFormatsAndEnumsWithoutAngularComponents() throws Exception {
        var spec = gadgetSpec(true);
        var preflight = new GenerationCapabilityPreflight(builder(gadgetRegistry()), gadgetRegistry());
        var fields = new ArrayList<>(spec.entity().fields());
        fields.set(0, new FieldSpec("title", "title", "UUID", false, null, null));
        var wrongFormat = new GenerationSpecification(spec.specVersion(), spec.basePackage(),
                new EntitySpec("Gadget", "gadget", fields, false), spec.openApi(), spec.operationRoles(), spec.angular());
        var error = assertThrows(GenerationValidationException.class,
                () -> preflight.check(version(bindings()), wrongFormat, GenerationCapabilityPreflight.OutputMode.BACKEND));
        assertTrue(error.diagnostics().stream().anyMatch(s -> s.contains("contract.update.title: type or format differs")));

        fields = new ArrayList<>(spec.entity().fields());
        fields.set(1, new FieldSpec("status", "status", "String", false, 40, null));
        var wrongEnum = new GenerationSpecification(spec.specVersion(), spec.basePackage(),
                new EntitySpec("Gadget", "gadget", fields, false), spec.openApi(), spec.operationRoles(), spec.angular());
        error = assertThrows(GenerationValidationException.class,
                () -> preflight.check(version(bindings()), wrongEnum, GenerationCapabilityPreflight.OutputMode.BACKEND));
        assertTrue(error.diagnostics().stream().anyMatch(s -> s.contains("contract.update.status: unexpected contract enum")));
    }

    private PackagedContractRegistry gadgetRegistry() {
        return new PackagedContractRegistry("/generation/gadget-contracts.yaml");
    }

    private GenerationSpecification withAssetVersion(String contractVersion) {
        var fixture = gadgetSpec(true);
        return new GenerationSpecification(fixture.specVersion(), fixture.basePackage(), fixture.entity(),
                new OpenApiSpec("asset-ticket-api", contractVersion, Map.of()), Map.of(), fixture.angular());
    }

    private GenerationContextBuilder builder(PackagedContractRegistry registry) {
        return new GenerationContextBuilder(mapper, new MetadataSchemaValidator(), registry);
    }

    private GenerationSpecification gadgetSpec(boolean nullableNote) {
        var operations = List.of("fetchGadgets", "submitGadget", "findGadget", "replaceGadget", "removeGadget");
        Map<UUID, String> selected = new LinkedHashMap<>();
        Map<String, Set<SupportedRole>> roles = new LinkedHashMap<>();
        for (int i = 0; i < operations.size(); i++) {
            selected.put(new UUID(0, i + 1), operations.get(i));
            roles.put(operations.get(i), Set.of(SupportedRole.ROLE_ADMIN));
        }
        return new GenerationSpecification("1.0.0", "com.example.fixture",
                new EntitySpec("Gadget", "gadget", List.of(
                        new FieldSpec("title", "title", "String", false, 100, null),
                        new FieldSpec("status", "status", "GadgetStatus", false, null, "GadgetStatus"),
                        new FieldSpec("note", "note", "String", nullableNote, 100, null)), false),
                new OpenApiSpec("gadget-api", "2.0.0", selected), roles, new AngularSpec("Gadget", Map.of()));
    }

    private ObjectNode bindings() {
        var snapshot = mapper.createObjectNode();
        snapshot.putArray("components");
        var bindings = snapshot.putArray("apiBindings");
        String[] verbs = {"GET", "POST", "GET", "PUT", "DELETE"};
        for (int i = 0; i < verbs.length; i++) {
            ObjectNode binding = bindings.addObject();
            binding.put("id", new UUID(0, i + 1).toString());
            binding.put("httpMethod", verbs[i]);
            binding.put("endpointUrl", "/api/v1/gadgets" + (i > 1 ? "/{id}" : ""));
            binding.putObject("headers");
            ObjectNode request = binding.putObject("requestMapping");
            if (i == 0) request.putObject("query").put("status", "status");
            if (i > 1) request.putObject("path").put("id", "id");
            if (i == 1 || i == 3)
                request.putObject("body").put("title", "title").put("status", "status").put("note", "note");
            ObjectNode response = binding.putObject("responseMapping");
            if (i == 0) response.put("list", "body.data"); else response.put("item", "body");
        }
        return snapshot;
    }

    private MetadataVersion version(ObjectNode snapshot) throws Exception {
        var project = new GsuifProject(); project.setName("Fixture");
        var page = new GsuifPage(); page.setProject(project);
        var version = new MetadataVersion(); version.setPage(page);
        version.setSchemaVersion("1.0.0"); version.setSnapshot(mapper.writeValueAsString(snapshot));
        return version;
    }
}
