package eg.mts.gsuif.generation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import eg.mts.gsuif.entity.GsuifPage;
import eg.mts.gsuif.entity.GsuifProject;
import eg.mts.gsuif.entity.MetadataVersion;
import eg.mts.gsuif.generation.GenerationContext.Target;
import eg.mts.gsuif.generation.GenerationSpecification.*;
import eg.mts.gsuif.validator.MetadataSchemaValidator;
import eg.mts.gsuif.validator.VisibilityRuleEvaluator;
import freemarker.template.Configuration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.yaml.snakeyaml.Yaml;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.io.StringWriter;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class GenerationEngineTest {
    @TempDir Path temporaryDirectory;
    private final ObjectMapper mapper = new ObjectMapper();
    private final GenerationContextBuilder builder = new GenerationContextBuilder(mapper, new MetadataSchemaValidator());

    @Test void providerSubstitutionAndBufferedEntity() throws Exception {
        var version = version("1.0.0", emptySnapshot());
        var spec = spec("AssetTicket", "asset-ticket-api", Map.of(), Map.of());
        AICodeGenerationProvider fake = new AICodeGenerationProvider() {
            @Override public String getProviderName() { return "fake"; }
            @Override public boolean isAvailable() { return true; }
            @Override public GenerationResult generate(GenerationContext context) {
                assertEquals("AssetTicket", context.specification().entity().className());
                assertEquals(Set.of(Target.ENTITY), context.targets());
                return new GenerationResult(List.of(), List.of("fake"));
            }
        };
        assertEquals("fake", fake.getProviderName());
        assertTrue(fake.isAvailable());
        AICodeGenerationProvider templateProvider = new TemplateOnlyProvider(builder);
        assertEquals("TemplateOnlyProvider", templateProvider.getProviderName());
        assertTrue(templateProvider.isAvailable());
        assertEquals(List.of("fake"), new GenerationEngine(builder, fake).generate(version, spec, Set.of(Target.ENTITY), "spring-angular").diagnostics());
        var result = GenerationEngine.templateOnly(builder).generate(version, spec, Set.of(Target.ENTITY), "spring-angular");
        var context = builder.build(version, spec, Set.of(Target.ENTITY), "spring-angular");
        assertEquals("1.0.0", context.catalog().entity().version());
        assertTrue(context.catalog().standardRefs().contains("STD-33"));
        assertNotNull(result.consumerBuild(), "Entity-only output still requires audit support and dependencies");
        assertEquals(6, result.artifacts().size());
        var artifact = result.artifacts().getFirst();
        assertEquals("src/main/java/com/example/fixture/entity/AssetTicket.java", artifact.relativePath());
        assertEquals(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(artifact.bytes())), artifact.sha256());
        assertEquals(GenerationCatalog.load().version(), artifact.catalogVersion());
        assertEquals(GenerationCatalog.load().entity().version(), artifact.templateVersion());
        assertTrue(new String(artifact.bytes(), StandardCharsets.UTF_8).contains("class AssetTicket extends AuditableEntity"));
    }

    @Test void runtimeRejectsCatalogEntriesWithoutConfirmedImplementation() throws Exception {
        String packaged;
        try (var input = getClass().getResourceAsStream("/components.yaml")) {
            assertNotNull(input);
            packaged = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
        for (String status : List.of("PLANNED", "DEFERRED")) {
            String altered = packaged.replaceFirst("(?s)(- id: BE-02.*?implementation_status: )CONFIRMED", "$1" + status);
            assertTrue(assertThrows(GenerationValidationException.class,
                    () -> GenerationCatalog.parse(new Yaml().load(altered)))
                    .diagnostics().stream().anyMatch(message -> message.contains("BE-02 generation implementation")));
        }
    }

    @Test void indexedValidationAndSchemaDispatch() throws Exception {
        var version = version("1.0.0", emptySnapshot());
        var original = spec("AssetTicket", "asset-ticket-api", Map.of(), Map.of());
        var invalid = new GenerationSpecification("1.0.0", "com.example.fixture",
                new EntitySpec("AssetTicket", "asset_ticket", List.of(new FieldSpec("../escape", "title", "String", false, 10, null)), false),
                original.openApi(), original.operationRoles(), original.angular());
        var failure = assertThrows(GenerationValidationException.class,
                () -> builder.build(version, invalid, Set.of(Target.ENTITY), "spring-angular"));
        assertTrue(failure.diagnostics().stream().anyMatch(d -> d.contains("fields[0].name")));
        version.setSchemaVersion("9.0.0");
        assertTrue(assertThrows(GenerationValidationException.class,
                () -> builder.build(version, original, Set.of(Target.ENTITY), "spring-angular"))
                .diagnostics().stream().anyMatch(d -> d.contains("schemaVersion")));
        assertThrows(GenerationValidationException.class, () -> builder.build(version("1.0.0", emptySnapshot()), original,
                Set.of(Target.ENTITY), "unknown"));
        var unknownContract = new GenerationSpecification("1.0.0", original.basePackage(), original.entity(),
                new OpenApiSpec(null, "1.0.0", Map.of()), original.operationRoles(), original.angular());
        assertTrue(assertThrows(GenerationValidationException.class,
                () -> builder.build(version("1.0.0", emptySnapshot()), unknownContract, Set.of(Target.ENTITY), "spring-angular"))
                .diagnostics().stream().anyMatch(d -> d.contains("openApi.contractId")));
    }

    @Test void entityEnumMustExistInSelectedPackagedContract() throws Exception {
        for (String entity : List.of("AssetTicket", "ServiceRequest")) {
            String contract = entity.equals("AssetTicket") ? "asset-ticket-api" : "service-request-api";
            var original = spec(entity, contract, Map.of(), Map.of());
            List<FieldSpec> fields = new ArrayList<>(original.entity().fields());
            fields.set(1, new FieldSpec("status", "status", "MissingStatus", false, null, "MissingStatus"));
            var invalid = new GenerationSpecification(original.specVersion(), original.basePackage(),
                    new EntitySpec(entity, original.entity().tableName(), fields, false),
                    original.openApi(), original.operationRoles(), original.angular());
            for (Target target : List.of(Target.ENTITY, Target.CONTROLLER)) {
                var failure = assertThrows(GenerationValidationException.class,
                        () -> GenerationEngine.templateOnly(builder).generate(version("1.0.0", emptySnapshot()),
                                invalid, Set.of(target), "spring-angular"));
                assertTrue(failure.diagnostics().stream().anyMatch(d -> d.contains("fields[1].enumType")
                        && d.contains("selected packaged contract")), failure.diagnostics().toString());
            }
        }
    }

    @Test void bothContractsDeriveControllerNamesAndEnforceRoles() throws Exception {
        for (String entity : List.of("AssetTicket", "ServiceRequest")) {
            var snapshot = bindings(entity);
            String contract = entity.equals("AssetTicket") ? "asset-ticket-api" : "service-request-api";
            Map<UUID, String> selected = new LinkedHashMap<>();
            Map<String, Set<SupportedRole>> roles = new LinkedHashMap<>();
            String[] methods = {"list" + entity + "s", "create" + entity, "get" + entity + "ById", "update" + entity, "delete" + entity};
            for (int i = 0; i < 5; i++) {
                selected.put(new UUID(0, i + 1), methods[i]);
                roles.put(methods[i], i == 0 ? EnumSet.of(SupportedRole.ROLE_ADMIN, SupportedRole.ROLE_USER) : EnumSet.of(SupportedRole.ROLE_ADMIN));
            }
            var spec = spec(entity, contract, selected, roles);
            var result = GenerationEngine.templateOnly(builder).generate(version("1.0.0", snapshot), spec, Set.of(Target.CONTROLLER), "spring-angular");
            assertEquals(6, result.artifacts().size());
            String controller = new String(result.artifacts().getFirst().bytes(), StandardCharsets.UTF_8);
            assertTrue(controller.contains("implements " + entity + "Api"));
            assertTrue(controller.contains("hasAnyAuthority('ROLE_ADMIN','ROLE_USER')"));
            assertEquals(5, result.artifacts().stream().filter(a -> a.relativePath().contains("eg/mts/gsuif/")).count());
            for (var artifact : result.artifacts()) if (artifact.relativePath().contains("eg/mts/gsuif/"))
                assertArrayEquals(Files.readAllBytes(Path.of(artifact.relativePath())), artifact.bytes());
            roles.remove(methods[1]);
            assertTrue(assertThrows(GenerationValidationException.class,
                    () -> builder.build(version("1.0.0", snapshot), spec(entity, contract, selected, roles), Set.of(Target.CONTROLLER), "spring-angular"))
                    .diagnostics().stream().anyMatch(d -> d.contains("operationRoles." + methods[1])));
        }
    }

    @Test void registeredThirdContractGeneratesAndCompilesAngular() throws Exception {
        ObjectNode snapshot = bindings("CaseRecord");
        ObjectNode status = snapshot.withArray("components").addObject();
        status.put("id", new UUID(0, 51).toString());
        status.put("type", "select").put("label", "Case status").put("fieldKey", "status");
        status.putObject("position").put("row", 0).put("col", 1);
        status.putObject("size").put("width", 1).put("height", 1);
        status.put("visibility", true).put("disabled", false);
        ((ObjectNode) snapshot.withArray("apiBindings").get(0)).putArray("linkedComponentIds")
                .add(status.path("id").asText());
        ObjectNode navigation = snapshot.withArray("components").addObject();
        navigation.put("id", new UUID(0, 50).toString());
        navigation.put("type", "navigation").put("label", "Cases");
        navigation.putObject("position").put("row", 0).put("col", 0);
        navigation.putObject("size").put("width", 1).put("height", 1);
        navigation.put("visibility", true).put("disabled", false);
        navigation.putObject("navigationConfig").putArray("items")
                .addObject().put("label", "All cases").put("route", "/cases");
        String operation = "listCaseRecords";
        var specification = spec("CaseRecord", "case-record-api", Map.of(new UUID(0, 1), operation),
                Map.of(operation, EnumSet.of(SupportedRole.ROLE_USER)));
        var result = GenerationEngine.templateOnly(builder).generate(version("1.1.0", snapshot), specification,
                Set.of(Target.ANGULAR), "spring-angular");
        String html = new String(result.artifacts().get(1).bytes(), StandardCharsets.UTF_8);
        assertTrue(html.contains("href=\"/cases\""));
        assertTrue(html.contains("All cases"));
        assertTrue(html.contains("Case status"));
        String typescript = new String(result.artifacts().getFirst().bytes(), StandardCharsets.UTF_8);
        assertTrue(typescript.contains("status: ['OPEN', 'CLOSED']"));
        stageAngularForCompilation(result);
    }

    @Test void dateAngularForBothSchemaVersions() throws Exception {
        ObjectNode snapshot = bindings("ServiceRequest");
        ObjectNode date = snapshot.withArray("components").addObject();
        date.put("id", new UUID(0, 10).toString()); date.put("type", "date-field");
        date.put("label", "Due date"); date.put("fieldKey", "dueDate");
        date.putObject("position").put("row", 0).put("col", 0);
        date.putObject("size").put("width", 6).put("height", 1);
        date.put("visibility", true); date.put("disabled", false);
        ((ObjectNode) snapshot.withArray("apiBindings").get(0)).putArray("linkedComponentIds").add(new UUID(0, 10).toString());
        Map<UUID, String> selected = new LinkedHashMap<>();
        Map<String, Set<SupportedRole>> roles = new LinkedHashMap<>();
        String[] methods = {"listServiceRequests", "createServiceRequest", "getServiceRequestById", "updateServiceRequest", "deleteServiceRequest"};
        for (int i = 0; i < 5; i++) { selected.put(new UUID(0, i + 1), methods[i]); roles.put(methods[i], EnumSet.of(SupportedRole.ROLE_ADMIN)); }
        var spec = spec("ServiceRequest", "service-request-api", selected, roles);
        for (String schema : List.of("1.0.0", "1.1.0")) {
            var result = GenerationEngine.templateOnly(builder).generate(version(schema, snapshot), spec, Set.of(Target.ANGULAR), "spring-angular");
            assertEquals(3, result.artifacts().size());
            String ts = new String(result.artifacts().getFirst().bytes(), StandardCharsets.UTF_8);
            assertTrue(ts.contains("id: string"));
            assertTrue(ts.contains("dueDate: string"));
            assertTrue(new String(result.artifacts().get(1).bytes(), StandardCharsets.UTF_8).contains("type=\"date\""));
            stageAngularForCompilation(result);
        }
    }

    @Test void deleteBindingRequiresContractBodyMapping() throws Exception {
        ObjectNode snapshot = bindings("AssetTicket");
        ((ObjectNode) snapshot.withArray("apiBindings").get(4)).putObject("responseMapping");
        Map<UUID, String> selected = new LinkedHashMap<>();
        Map<String, Set<SupportedRole>> roles = new LinkedHashMap<>();
        String[] methods = {"listAssetTickets", "createAssetTicket", "getAssetTicketById", "updateAssetTicket", "deleteAssetTicket"};
        for (int i = 0; i < methods.length; i++) {
            selected.put(new UUID(0, i + 1), methods[i]);
            roles.put(methods[i], EnumSet.of(SupportedRole.ROLE_ADMIN));
        }
        var failure = assertThrows(GenerationValidationException.class,
                () -> builder.build(version("1.0.0", snapshot), spec("AssetTicket", "asset-ticket-api", selected, roles),
                        Set.of(Target.CONTROLLER), "spring-angular"));
        assertTrue(failure.diagnostics().stream().anyMatch(d -> d.contains("bindingOperations[4].responseMapping.item")));
    }

    @Test void angularUsesOnlySelectedBindingsAndTheirComponents() throws Exception {
        ObjectNode snapshot = bindings("AssetTicket");
        ObjectNode selectedComponent = snapshot.withArray("components").addObject();
        selectedComponent.put("id", new UUID(0, 10).toString());
        selectedComponent.put("type", "text-field"); selectedComponent.put("fieldKey", "title");
        selectedComponent.put("label", "Title {{ missing }} <draft>");
        selectedComponent.putObject("position").put("row", 0).put("col", 0);
        selectedComponent.putObject("size").put("width", 6).put("height", 1);
        selectedComponent.put("visibility", true); selectedComponent.put("disabled", false);
        ObjectNode otherComponent = snapshot.withArray("components").addObject();
        otherComponent.put("id", new UUID(0, 11).toString());
        otherComponent.put("type", "navigation"); otherComponent.put("label", "Other");
        otherComponent.putObject("position").put("row", 1).put("col", 0);
        otherComponent.putObject("size").put("width", 6).put("height", 1);
        otherComponent.put("visibility", true); otherComponent.put("disabled", false);
        ((ObjectNode) snapshot.withArray("apiBindings").get(0)).putArray("linkedComponentIds").add(new UUID(0, 10).toString());
        ((ObjectNode) snapshot.withArray("apiBindings").get(1)).putArray("linkedComponentIds").add(new UUID(0, 11).toString());
        Map<UUID, String> selected = Map.of(new UUID(0, 1), "listAssetTickets");
        var specification = spec("AssetTicket", "asset-ticket-api", selected,
                Map.of("listAssetTickets", EnumSet.of(SupportedRole.ROLE_USER)));
        var result = GenerationEngine.templateOnly(builder).generate(version("1.0.0", snapshot), specification,
                Set.of(Target.ANGULAR), "spring-angular");
        assertEquals(3, result.artifacts().size());
        String html = new String(result.artifacts().get(1).bytes(), StandardCharsets.UTF_8);
        assertTrue(html.contains("type=\"text\""));
        assertTrue(html.contains("<span ngNonBindable>Title {{ missing }} &lt;draft&gt;</span>"));
        assertFalse(html.contains("Other"));
        stageAngularForCompilation(result);
    }

    @Test void selectedFieldDiagnosticsIdentifyComponentAndSnapshotPath() throws Exception {
        ObjectNode snapshot = bindings("AssetTicket");
        UUID id = new UUID(0, 91);
        ObjectNode field = snapshot.withArray("components").addObject();
        field.put("id", id.toString()); field.put("type", "text-field");
        field.put("label", "Title"); field.put("fieldKey", "missing");
        field.putObject("position").put("row", 0).put("col", 0);
        field.putObject("size").put("width", 1).put("height", 1);
        field.put("visibility", true); field.put("disabled", false);
        ((ObjectNode) snapshot.withArray("apiBindings").get(0)).putArray("linkedComponentIds").add(id.toString());
        var specification = spec("AssetTicket", "asset-ticket-api", Map.of(new UUID(0, 1), "listAssetTickets"),
                Map.of("listAssetTickets", Set.of(SupportedRole.ROLE_USER)));
        for (String key : List.of("missing", "status")) {
            field.put("fieldKey", key);
            var failure = assertThrows(GenerationValidationException.class,
                    () -> builder.build(version("1.1.0", snapshot), specification, Set.of(Target.ANGULAR), "spring-angular"));
            assertTrue(failure.diagnostics().stream().anyMatch(d -> d.contains("snapshot.components[0].fieldKey")
                    && d.contains(id.toString())
                    && d.contains(key.equals("missing") ? "absent from entity and DTO" : "incompatible DTO type")),
                    failure.diagnostics().toString());
        }
        UUID absentId = new UUID(0, 92);
        ((ObjectNode) snapshot.withArray("apiBindings").get(0)).withArray("linkedComponentIds")
                .set(0, mapper.getNodeFactory().textNode(absentId.toString()));
        var absent = assertThrows(GenerationValidationException.class,
                () -> builder.build(version("1.1.0", snapshot), specification, Set.of(Target.ANGULAR), "spring-angular"));
        assertTrue(absent.diagnostics().stream().anyMatch(d -> d.contains("snapshot.apiBindings[0].linkedComponentIds[0]")
                && d.contains(absentId.toString()) && d.contains("absent from snapshot")), absent.diagnostics().toString());
    }

    @Test void angularTextSelectAndLegacyTableUseSelectedColumns() throws Exception {
        ObjectNode snapshot = bindings("AssetTicket");
        String[] types = {"text-field", "select", "table"};
        String[] keys = {"title", "status", ""};
        for (int i = 0; i < types.length; i++) {
            UUID id = new UUID(0, i + 10);
            ObjectNode component = snapshot.withArray("components").addObject();
            component.put("id", id.toString()); component.put("type", types[i]);
            component.put("label", types[i]);
            if (!keys[i].isEmpty()) component.put("fieldKey", keys[i]);
            component.putObject("position").put("row", i).put("col", 0);
            component.putObject("size").put("width", 6).put("height", 1);
            component.put("visibility", true); component.put("disabled", false);
            ((ObjectNode) snapshot.withArray("apiBindings").get(0)).withArray("linkedComponentIds").add(id.toString());
        }
        Map<UUID, String> selected = new LinkedHashMap<>();
        Map<String, Set<SupportedRole>> roles = new LinkedHashMap<>();
        String[] methods = {"listAssetTickets", "createAssetTicket", "getAssetTicketById", "updateAssetTicket", "deleteAssetTicket"};
        for (int i = 0; i < 5; i++) { selected.put(new UUID(0, i + 1), methods[i]); roles.put(methods[i], EnumSet.of(SupportedRole.ROLE_USER)); }
        var baseline = spec("AssetTicket", "asset-ticket-api", selected, roles);
        var spec = new GenerationSpecification("1.0.0", baseline.basePackage(), baseline.entity(), baseline.openApi(), baseline.operationRoles(),
                new AngularSpec("AssetTicket", Map.of(new UUID(0, 12), List.of("id", "title"))));
        var missingBindings = new GenerationSpecification(spec.specVersion(), spec.basePackage(), spec.entity(),
                new OpenApiSpec(spec.openApi().contractId(), spec.openApi().contractVersion(), null),
                spec.operationRoles(), spec.angular());
        var missingBindingsFailure = assertThrows(GenerationValidationException.class,
                () -> builder.build(version("1.0.0", snapshot), missingBindings, Set.of(Target.ANGULAR), "spring-angular"));
        assertTrue(missingBindingsFailure.diagnostics().stream().anyMatch(d -> d.contains("openApi.bindingOperations: required")),
                missingBindingsFailure.diagnostics().toString());
        assertTrue(missingBindingsFailure.diagnostics().stream().anyMatch(d -> d.contains("components[2]")
                && d.contains(new UUID(0, 12).toString()) && d.contains("selected list binding")),
                missingBindingsFailure.diagnostics().toString());
        var result = GenerationEngine.templateOnly(builder).generate(version("1.0.0", snapshot), spec, Set.of(Target.ANGULAR), "spring-angular");
        String html = new String(result.artifacts().get(1).bytes(), StandardCharsets.UTF_8);
        assertTrue(html.contains("type=\"text\"")); assertTrue(html.contains("<select"));
        assertTrue(html.contains("row.id")); assertFalse(html.contains("row.status"));
        String ts = new String(result.artifacts().getFirst().bytes(), StandardCharsets.UTF_8);
        assertTrue(ts.contains("status: ['OPEN', 'CLOSED']")); assertTrue(ts.contains("id: string"));
        stageAngularForCompilation(result);
        ObjectNode configured = (ObjectNode) snapshot.withArray("components").get(2);
        configured.putObject("tableConfig").putArray("columns").addObject().put("fieldKey", "title").put("label", "Title");
        configured.with("tableConfig").withArray("columns").addObject().put("fieldKey", "id").put("label", "Identifier");
        var configuredSpec = new GenerationSpecification(spec.specVersion(), spec.basePackage(), spec.entity(),
                spec.openApi(), spec.operationRoles(), new AngularSpec("AssetTicket", Map.of()));
        for (String schema : List.of("1.1.0", "1.2.0")) {
            var configuredResult = GenerationEngine.templateOnly(builder).generate(version(schema, snapshot), configuredSpec,
                    Set.of(Target.ANGULAR), "spring-angular");
            String configuredHtml = new String(configuredResult.artifacts().get(1).bytes(), StandardCharsets.UTF_8);
            assertTrue(configuredHtml.contains("<th ngNonBindable>Title</th>"), schema);
            assertTrue(configuredHtml.contains("row.title"), schema);
            assertTrue(configuredHtml.indexOf("row.title") < configuredHtml.indexOf("row.id"), schema);
            assertTrue(configuredHtml.contains("<th ngNonBindable>Identifier</th>"), schema);
            stageAngularForCompilation(configuredResult);
        }
        ((ObjectNode) configured.path("tableConfig").path("columns").get(0)).put("fieldKey", "missing");
        assertTrue(assertThrows(GenerationValidationException.class,
                () -> builder.build(version("1.1.0", snapshot), configuredSpec, Set.of(Target.ANGULAR), "spring-angular"))
                .diagnostics().stream().anyMatch(d -> d.contains("components[2].tableConfig.columns[0].fieldKey")
                        && d.contains(configured.path("id").asText())));
        configured.remove("tableConfig");
        ((ObjectNode) snapshot.withArray("components").get(0)).put("type", "navigation");
        assertTrue(assertThrows(GenerationValidationException.class,
                () -> builder.build(version("1.0.0", snapshot), spec, Set.of(Target.ANGULAR), "spring-angular"))
                .diagnostics().stream().anyMatch(d -> d.contains("snapshot.components[0]")
                        && d.contains(new UUID(0, 10).toString())
                        && d.contains(".type: unsupported Angular component for schema 1.0.0")));
    }

    @Test void extendedComponentsRenderApprovedStructureAndCompile() throws Exception {
        ObjectNode snapshot = bindings("AssetTicket");
        String[] types = {"form", "navigation", "modal", "pagination"};
        for (int i = 0; i < types.length; i++) {
            ObjectNode component = snapshot.withArray("components").addObject();
            UUID id = new UUID(0, i + 20);
            component.put("id", id.toString()); component.put("type", types[i]);
            component.put("label", "Title {{ value }} <draft>");
            component.putObject("position").put("row", i).put("col", 0);
            component.putObject("size").put("width", 6).put("height", 1);
            component.put("visibility", true); component.put("disabled", false);
            ((ObjectNode) snapshot.withArray("apiBindings").get(0)).withArray("linkedComponentIds").add(id.toString());
            switch (types[i]) {
                case "form" -> component.putObject("formConfig").put("submitLabel", "Save <now>");
                case "navigation" -> component.putObject("navigationConfig").putArray("items")
                        .addObject().put("label", "Tickets <all>").put("route", "/tickets?kind=a&view=b");
                case "pagination" -> component.putObject("paginationConfig").put("pageSize", 25);
                default -> { }
            }
        }
        var specification = spec("AssetTicket", "asset-ticket-api", Map.of(new UUID(0, 1), "listAssetTickets"),
                Map.of("listAssetTickets", EnumSet.of(SupportedRole.ROLE_USER)));
        for (String schema : List.of("1.1.0", "1.2.0")) {
            var result = GenerationEngine.templateOnly(builder).generate(version(schema, snapshot), specification,
                    Set.of(Target.ANGULAR), "spring-angular");
            String html = new String(result.artifacts().get(1).bytes(), StandardCharsets.UTF_8);
            assertTrue(html.contains("<form>"), schema);
            assertTrue(html.contains("Save &lt;now&gt;"), schema);
            assertTrue(html.contains("href=\"/tickets?kind=a&amp;view=b\""), schema);
            assertTrue(html.contains("Tickets &lt;all&gt;"), schema);
            assertTrue(html.contains("role=\"dialog\""), schema);
            assertTrue(html.contains("ngNonBindable>Title {{ value }} &lt;draft&gt;</h2>"), schema);
            assertTrue(html.contains("data-page-size=\"25\""), schema);
            assertTrue(html.contains("data-grid-row=\"0\""), schema);
            assertTrue(html.contains("aria-labelledby=\"generated-title-"), schema);
            assertFalse(html.contains("aria-modal=\"true\""), schema);
            stageAngularForCompilation(result);
        }
    }

    @Test void unboundPageComponentsAndStaticSettingsAreRendered() throws Exception {
        ObjectNode snapshot = bindings("AssetTicket");
        ObjectNode navigation = snapshot.withArray("components").addObject();
        navigation.put("id", new UUID(0, 30).toString()); navigation.put("type", "navigation");
        navigation.put("label", "Browse");
        navigation.putObject("position").put("row", 2).put("col", 3);
        navigation.putObject("size").put("width", 4).put("height", 1);
        navigation.put("visibility", true); navigation.put("disabled", true);
        navigation.putObject("navigationConfig").putArray("items").addObject()
                .put("label", "All <tickets>").put("route", "/tickets");
        ObjectNode modal = snapshot.withArray("components").addObject();
        modal.put("id", new UUID(0, 31).toString()); modal.put("type", "modal"); modal.put("label", "Hidden");
        modal.putObject("position").put("row", 3).put("col", 0);
        modal.putObject("size").put("width", 1).put("height", 1);
        modal.put("visibility", false); modal.put("disabled", false);
        var specification = spec("AssetTicket", "asset-ticket-api", Map.of(), Map.of());
        var result = GenerationEngine.templateOnly(builder).generate(version("1.1.0", snapshot), specification,
                Set.of(Target.ANGULAR), "spring-angular");
        String html = new String(result.artifacts().get(1).bytes(), StandardCharsets.UTF_8);
        assertTrue(html.contains("grid-row:3 / span 1;grid-column:4 / span 4"));
        assertTrue(html.contains("data-grid-row=\"2\" data-grid-col=\"3\" data-grid-width=\"4\""));
        assertTrue(html.contains("All &lt;tickets&gt;"));
        assertFalse(html.contains("href=\"/tickets\""));
        assertFalse(html.contains("Hidden"));
        stageAngularForCompilation(result);
        navigation.put("type", "future-widget");
        navigation.remove("navigationConfig");
        var unsupported = assertThrows(GenerationValidationException.class,
                () -> builder.build(version("1.1.0", snapshot), specification, Set.of(Target.ANGULAR), "spring-angular"));
        assertTrue(unsupported.diagnostics().stream().anyMatch(d -> d.contains("components[0]")
                && d.contains(new UUID(0, 30).toString()) && d.contains("unsupported Angular component")),
                unsupported.diagnostics().toString());
    }

    @Test void paginationOverflowAndColumnConflictHaveIndexedDiagnostics() throws Exception {
        ObjectNode snapshot = bindings("AssetTicket");
        ObjectNode pagination = snapshot.withArray("components").addObject();
        UUID id = new UUID(0, 40);
        pagination.put("id", id.toString()); pagination.put("type", "pagination"); pagination.put("label", "Pages");
        pagination.putObject("position").put("row", 0).put("col", 0);
        pagination.putObject("size").put("width", 1).put("height", 1);
        pagination.put("visibility", true); pagination.put("disabled", false);
        pagination.putObject("paginationConfig").put("pageSize", 2147483648L);
        var specification = spec("AssetTicket", "asset-ticket-api", Map.of(), Map.of());
        var failure = assertThrows(GenerationValidationException.class,
                () -> builder.build(version("1.1.0", snapshot), specification, Set.of(Target.ANGULAR), "spring-angular"));
        assertTrue(failure.diagnostics().stream().anyMatch(d -> d.contains("components[0]") && d.contains(id.toString())
                && d.contains("paginationConfig.pageSize")), failure.diagnostics().toString());
        pagination.with("paginationConfig").put("pageSize", 20);
        UUID tableId = new UUID(0, 41);
        ObjectNode table = snapshot.withArray("components").addObject();
        table.put("id", tableId.toString()); table.put("type", "table"); table.put("label", "Tickets");
        table.putObject("position").put("row", 1).put("col", 0);
        table.putObject("size").put("width", 4).put("height", 2);
        table.put("visibility", true); table.put("disabled", false);
        table.putObject("tableConfig").putArray("columns").addObject().put("fieldKey", "title").put("label", "Title");
        ((ObjectNode) snapshot.withArray("apiBindings").get(0)).putArray("linkedComponentIds").add(tableId.toString());
        var base = spec("AssetTicket", "asset-ticket-api", Map.of(new UUID(0, 1), "listAssetTickets"),
                Map.of("listAssetTickets", Set.of(SupportedRole.ROLE_USER)));
        var conflicting = new GenerationSpecification(base.specVersion(), base.basePackage(), base.entity(),
                base.openApi(), base.operationRoles(), new AngularSpec("AssetTicket", Map.of(tableId, List.of("id"))));
        var conflict = assertThrows(GenerationValidationException.class,
                () -> builder.build(version("1.1.0", snapshot), conflicting, Set.of(Target.ANGULAR), "spring-angular"));
        assertTrue(conflict.diagnostics().stream().anyMatch(d -> d.contains("components[1]")
                && d.contains(tableId.toString()) && d.contains("tableConfig.columns")), conflict.diagnostics().toString());
        table.remove("tableConfig");
        var invalidLegacy = new GenerationSpecification(base.specVersion(), base.basePackage(), base.entity(),
                base.openApi(), base.operationRoles(), new AngularSpec("AssetTicket", Map.of(tableId, List.of("missing"))));
        var legacyFailure = assertThrows(GenerationValidationException.class,
                () -> builder.build(version("1.1.0", snapshot), invalidLegacy, Set.of(Target.ANGULAR), "spring-angular"));
        assertTrue(legacyFailure.diagnostics().stream().anyMatch(d -> d.contains("snapshot.components[1]")
                && d.contains(tableId.toString())
                && d.contains("specification.angular.tableColumns." + tableId + "[0]")),
                legacyFailure.diagnostics().toString());
    }

    @Test void bindingVisibilityUsesDisplayInputAndRejectsAmbiguousRules() throws Exception {
        ObjectNode snapshot = bindings("AssetTicket");
        UUID id = new UUID(0, 50);
        ObjectNode field = snapshot.withArray("components").addObject();
        field.put("id", id.toString()); field.put("type", "text-field");
        field.put("label", "Title"); field.put("fieldKey", "title");
        field.putObject("position").put("row", 0).put("col", 0);
        field.putObject("size").put("width", 1).put("height", 1);
        field.put("visibility", true); field.put("disabled", false);
        ObjectNode first = (ObjectNode) snapshot.withArray("apiBindings").get(0);
        first.putArray("linkedComponentIds").add(id.toString());
        ObjectNode rule = first.putObject("visibilityRule");
        rule.put("op", "AND");
        rule.putArray("rules").addObject().put("op", "role").put("value", "EDITOR");
        rule.withArray("rules").addObject().put("op", "field").put("field", "status").put("equals", "OPEN");
        rule.withArray("rules").addObject().put("op", "field").put("field", "count").put("equals", 1);
        var specification = spec("AssetTicket", "asset-ticket-api", Map.of(new UUID(0, 1), "listAssetTickets"),
                Map.of("listAssetTickets", Set.of(SupportedRole.ROLE_USER)));
        var result = GenerationEngine.templateOnly(builder).generate(version("1.2.0", snapshot), specification,
                Set.of(Target.ANGULAR), "spring-angular");
        String ts = new String(result.artifacts().getFirst().bytes(), StandardCharsets.UTF_8);
        String html = new String(result.artifacts().get(1).bytes(), StandardCharsets.UTF_8);
        assertTrue(ts.contains("@Input() visibilityContext"));
        assertTrue(ts.contains("new VisibilityEvaluator(this.visibilityContext).evaluate(this.visibilityRules[id])"));
        assertTrue(result.artifacts().stream().anyMatch(a -> a.relativePath().equals("src/app/generated/angular-visibility.ts")));
        assertTrue(ts.contains("EDITOR"));
        assertTrue(ts.contains("$jsonNumber"));
        String evaluator = new String(result.artifacts().stream()
                .filter(a -> a.relativePath().equals("src/app/generated/angular-visibility.ts"))
                .findFirst().orElseThrow().bytes(), StandardCharsets.UTF_8);
        assertTrue(evaluator.contains("sameJsonNumber(number, actualNumber)"));
        assertTrue(html.contains("*ngIf=\"isVisible('" + id.toString().replace("-", "") + "')\""));
        stageAngularForCompilation(result);
        verifyGeneratedVisibilityRuntime();
        ObjectNode second = (ObjectNode) snapshot.withArray("apiBindings").get(1);
        second.putArray("linkedComponentIds").add(id.toString());
        second.putObject("visibilityRule").put("op", "role").put("value", "VIEWER");
        var both = spec("AssetTicket", "asset-ticket-api", Map.of(new UUID(0, 1), "listAssetTickets",
                new UUID(0, 2), "createAssetTicket"), Map.of("listAssetTickets", Set.of(SupportedRole.ROLE_USER),
                "createAssetTicket", Set.of(SupportedRole.ROLE_USER)));
        var failure = assertThrows(GenerationValidationException.class,
                () -> builder.build(version("1.2.0", snapshot), both, Set.of(Target.ANGULAR), "spring-angular"));
        assertTrue(failure.diagnostics().stream().anyMatch(d -> d.contains("apiBindings[1].visibilityRule")
                && d.contains(id.toString())), failure.diagnostics().toString());
    }

    @Test void angularTemplatesCompileMultipleDropdownsWithDistinctFieldNames() throws Exception {
        var templates = new Configuration(Configuration.VERSION_2_3_34);
        templates.setClassForTemplateLoading(TemplateOnlyProvider.class, "/");
        Map<String, Object> model = Map.of(
                "className", "DropdownFixture", "kebab", "dropdown-fixture",
                "properties", List.of(
                        Map.of("name", "status", "type", "string", "optional", ""),
                        Map.of("name", "resolutionCode", "type", "string", "optional", "")),
                "controls", List.of(
                        selectTemplate(Map.of("type", "select", "key", "status", "label", "Status", "id", "status", "visible", true,
                                "disabled", false, "row", "0", "col", "0", "width", "1", "height", "1")),
                        selectTemplate(Map.of("type", "select", "key", "resolutionCode", "label", "Resolution", "id", "resolutionCode", "visible", true,
                                "disabled", false, "row", "0", "col", "1", "width", "1", "height", "1"))),
                "options", Map.of("status", List.of("OPEN", "CLOSED"),
                        "resolutionCode", List.of("FIXED", "REJECTED")), "rules", Map.of());
        String typescript = renderAngularTemplate(templates, "angular-component.ts.ftl", model);
        String html = renderAngularTemplate(templates, "angular-component.html.ftl", model);
        assertTrue(html.contains("options['status']"));
        assertTrue(html.contains("options['resolutionCode']"));
        assertTrue(typescript.contains("resolutionCode: ['FIXED', 'REJECTED']"));
        stageAngularForCompilation(new GenerationResult(List.of(
                new GenerationResult.Artifact("src/app/generated/dropdown-fixture.component.ts",
                        typescript.getBytes(StandardCharsets.UTF_8), "", "1.0.0", "1.0.0"),
                new GenerationResult.Artifact("src/app/generated/dropdown-fixture.component.html",
                        html.getBytes(StandardCharsets.UTF_8), "", "1.0.0", "1.0.0"),
                new GenerationResult.Artifact("src/app/generated/angular-visibility.ts",
                        renderAngularTemplate(templates, "angular-visibility.ts.ftl", model).getBytes(StandardCharsets.UTF_8),
                        "", "1.0.0", "1.0.0")), List.of()));
    }

    private static String renderAngularTemplate(Configuration templates, String name, Map<String, Object> model)
            throws Exception {
        var output = new StringWriter();
        templates.getTemplate("templates/freemarker/" + name).process(model, output);
        return output.toString();
    }

    private static Map<String, Object> selectTemplate(Map<String, Object> control) {
        Map<String, Object> model = new LinkedHashMap<>(control);
        model.put("template", "/templates/freemarker/angular/select.ftl");
        return model;
    }

    @Test void cleanAssetTicketConsumerCompilesWithMethodSecurityEnabled() throws Exception {
        compileConsumer("AssetTicket", "asset-ticket-api", "openapi-fixture");
    }

    @Test void cleanServiceRequestConsumerCompilesWithDateDto() throws Exception {
        compileConsumer("ServiceRequest", "service-request-api", "openapi-second-fixture");
    }

    private void compileConsumer(String entity, String contract, String fixtureDirectory) throws Exception {
        ObjectNode snapshot = bindings(entity);
        Map<UUID, String> selected = new LinkedHashMap<>();
        Map<String, Set<SupportedRole>> roles = new LinkedHashMap<>();
        String[] methods = {"list" + entity + "s", "create" + entity, "get" + entity + "ById", "update" + entity, "delete" + entity};
        for (int i = 0; i < 5; i++) { selected.put(new UUID(0, i + 1), methods[i]); roles.put(methods[i], EnumSet.of(SupportedRole.ROLE_ADMIN, SupportedRole.ROLE_USER)); }
        var result = GenerationEngine.templateOnly(builder).generate(version("1.0.0", snapshot),
                spec(entity, contract, selected, roles), Set.of(Target.ENTITY, Target.CONTROLLER), "spring-angular");
        var build = result.consumerBuild();
        assertNotNull(build, "Generated Java must declare its consumer build contract");
        assertEquals("1.0.0", build.contractVersion());
        assertEquals(21, build.javaVersion());
        assertTrue(build.methodSecurityPrerequisite().contains("@EnableMethodSecurity"));
        var artifacts = result.artifacts();
        List<Path> sources = new ArrayList<>();
        for (var artifact : artifacts) {
            Path destination = temporaryDirectory.resolve(artifact.relativePath());
            Files.createDirectories(destination.getParent()); Files.write(destination, artifact.bytes()); sources.add(destination);
        }
        Path service = temporaryDirectory.resolve("src/main/java/com/example/fixture/service/" + entity + "Service.java");
        Files.createDirectories(service.getParent());
        Files.writeString(service, ("""
                package com.example.fixture.service;
                import java.util.UUID;
                import com.example.fixture.dto.*;
                public interface %sService {
                    %sDto create(Create%sRequest request);
                    %sDto getById(UUID id);
                    %sPage list(%sStatus status);
                    %sDto update(UUID id, Update%sRequest request);
                    void delete(UUID id);
                }
                """).formatted(entity, entity, entity, entity, entity, entity, entity, entity));
        sources.add(service);
        Path methodSecurity = temporaryDirectory.resolve("src/main/java/com/example/fixture/ConsumerMethodSecurity.java");
        Files.writeString(methodSecurity, """
                package com.example.fixture;
                import org.springframework.context.annotation.Configuration;
                import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
                @Configuration @EnableMethodSecurity
                public class ConsumerMethodSecurity { }
                """);
        sources.add(methodSecurity);
        Path generated = Path.of("target/generated-sources/" + fixtureDirectory + "/src/main/java");
        assertTrue(Files.isDirectory(generated), "Generate the consumer fixtures with -Popenapi-fixture,openapi-second-fixture");
        try (var files = Files.walk(generated)) {
            for (Path source : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                Path destination = temporaryDirectory.resolve("src/main/java").resolve(generated.relativize(source));
                Files.createDirectories(destination.getParent());
                Files.copy(source, destination);
            }
        }
        Files.writeString(temporaryDirectory.resolve("pom.xml"), build.mavenPom("com.example", entity.toLowerCase(Locale.ROOT) + "-consumer"));
        String configuredMaven = System.getenv("GSUIF_MAVEN_COMMAND");
        String executable = configuredMaven != null && !configuredMaven.isBlank() ? configuredMaven
                : Path.of(System.getProperty("user.dir"), System.getProperty("os.name").startsWith("Windows") ? "mvnw.cmd" : "mvnw").toString();
        List<String> command = new ArrayList<>(List.of(executable, "-B", "-q", "-f", temporaryDirectory.resolve("pom.xml").toString(), "compile"));
        String repository = System.getProperty("maven.repo.local");
        if (repository != null && !repository.isBlank()) {
            Path repositoryPath = Path.of(repository);
            if (!repositoryPath.isAbsolute()) repositoryPath = Path.of(System.getProperty("user.dir")).resolve(repositoryPath);
            command.add("-Dmaven.repo.local=" + repositoryPath.normalize().toAbsolutePath());
        }
        Process process = new ProcessBuilder(command).directory(temporaryDirectory.toFile()).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertEquals(0, process.waitFor(), "Standalone generated consumer failed to compile with only declared dependencies:\n" + output);
        Path classes = temporaryDirectory.resolve("target/classes");
        assertTrue(Files.exists(classes.resolve("com/example/fixture/controller/" + entity + "Controller.class")));
        try (URLClassLoader loader = new URLClassLoader(new java.net.URL[]{classes.toUri().toURL()}, getClass().getClassLoader())) {
            verifyConsumerMethodSecurity(entity, loader);
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private void verifyConsumerMethodSecurity(String entity, ClassLoader loader) throws Exception {
        Class<?> controllerType = loader.loadClass("com.example.fixture.controller." + entity + "Controller");
        Class<?> serviceType = loader.loadClass("com.example.fixture.service." + entity + "Service");
        Object service = Proxy.newProxyInstance(loader, new Class<?>[]{serviceType}, (proxy, method, args) -> null);
        Object bareController = controllerType.getConstructor(serviceType).newInstance(service);
        String[] operationNames = {"list" + entity + "s", "create" + entity, "get" + entity + "ById", "update" + entity, "delete" + entity};
        for (String name : operationNames) {
            var method = Arrays.stream(controllerType.getMethods()).filter(candidate -> candidate.getName().equals(name)).findFirst().orElseThrow();
            assertNotNull(method.getAnnotation(org.springframework.security.access.prepost.PreAuthorize.class), name);
        }
        var delete = controllerType.getMethod("delete" + entity, UUID.class);
        try {
            SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken("reader", "",
                    List.of(new SimpleGrantedAuthority("ROLE_OTHER"))));
            assertNotNull(delete.invoke(bareController, UUID.randomUUID()), "Without method security, an annotation alone does not enforce access");
        } finally { SecurityContextHolder.clearContext(); }
        Class<?> config = loader.loadClass("com.example.fixture.ConsumerMethodSecurity");
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.setClassLoader(loader);
            context.register((Class) config);
            context.registerBean("generatedController", (Class) controllerType, () -> bareController);
            context.refresh();
            Object secured = context.getBean("generatedController");
            for (String name : operationNames) {
                var method = Arrays.stream(controllerType.getMethods()).filter(candidate -> candidate.getName().equals(name)).findFirst().orElseThrow();
                var securedMethod = secured.getClass().getMethod(name, method.getParameterTypes());
                Object[] args = Arrays.stream(method.getParameterTypes())
                        .map(type -> type == UUID.class ? UUID.randomUUID() : null).toArray();
                for (String role : List.of("ROLE_ADMIN", "ROLE_USER")) {
                    SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken("reader", "",
                            List.of(new SimpleGrantedAuthority(role))));
                    assertNotNull(securedMethod.invoke(secured, args), name + " must allow " + role);
                }
                SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken("reader", "",
                        List.of(new SimpleGrantedAuthority("ROLE_OTHER"))));
                InvocationTargetException denied = assertThrows(InvocationTargetException.class,
                        () -> securedMethod.invoke(secured, args), name);
                assertInstanceOf(AccessDeniedException.class, denied.getCause());
            }
        } finally { SecurityContextHolder.clearContext(); }
    }

    @Test void documentedWomsFixtureGeneratesFullStack() throws Exception {
        var metadata = mapper.readTree(java.nio.file.Files.readString(Path.of("demo/woms-sample-metadata.json")));
        var request = mapper.readTree(java.nio.file.Files.readString(Path.of("demo/woms-generation-request.json")));
        var specification = mapper.treeToValue(request.path("specification"), GenerationSpecification.class);
        var result = GenerationEngine.templateOnly(builder).generate(
                version("1.2.0", (ObjectNode) metadata.path("snapshot")), specification,
                Set.of(Target.ENTITY, Target.CONTROLLER, Target.ANGULAR), "spring-angular");
        assertFalse(result.artifacts().isEmpty());
        assertTrue(result.artifacts().stream().anyMatch(a -> a.relativePath().endsWith(".html")));
    }

    @Test void packagedConsumerTestsFollowGeneratedTargetsForAllContracts() throws Exception {
        for (String entity : List.of("AssetTicket", "ServiceRequest", "CaseRecord")) {
            String contract = entity.equals("AssetTicket") ? "asset-ticket-api"
                    : entity.equals("ServiceRequest") ? "service-request-api" : "case-record-api";
            ObjectNode snapshot = bindings(entity);
            Map<UUID, String> selected = new LinkedHashMap<>();
            Map<String, Set<SupportedRole>> roles = new LinkedHashMap<>();
            for (int i = 0; i < 5; i++) {
                String operation = switch (i) {
                    case 0 -> "list" + entity + "s";
                    case 1 -> "create" + entity;
                    case 2 -> "get" + entity + "ById";
                    case 3 -> "update" + entity;
                    default -> "delete" + entity;
                };
                selected.put(new UUID(0, i + 1), operation);
                roles.put(operation, Set.of(SupportedRole.ROLE_USER));
            }
            var specification = spec(entity, contract, selected, roles);
            for (Set<Target> targets : List.of(Set.of(Target.ENTITY), Set.of(Target.CONTROLLER),
                    Set.of(Target.ENTITY, Target.CONTROLLER, Target.ANGULAR))) {
                var inputs = PackagedConsumerInputs.forBuild(specification, targets);
                assertEquals(targets.contains(Target.ENTITY), inputs.keySet().stream()
                        .anyMatch(path -> path.endsWith("GeneratedEntityBehaviorTest.java")), entity + targets);
                assertEquals(targets.contains(Target.CONTROLLER), inputs.keySet().stream()
                        .anyMatch(path -> path.endsWith("GeneratedControllerBehaviorTest.java")), entity + targets);
                var generated = GenerationEngine.templateOnly(builder).generate(version("1.0.0", snapshot),
                        specification, targets, "spring-angular");
                var build = new ConsumerBuildValidator(temporaryDirectory.resolve("consumer-builds"))
                        .validate(generated, inputs);
                assertTrue(build.passed(), entity + targets + ": " + build.compile().output()
                        + "\n" + (build.test() == null ? "" : build.test().output()));
            }
        }
    }

    private ObjectNode emptySnapshot() {
        ObjectNode snapshot = mapper.createObjectNode();
        snapshot.set("components", mapper.createArrayNode()); snapshot.set("apiBindings", mapper.createArrayNode());
        return snapshot;
    }

    private void stageAngularForCompilation(GenerationResult result) throws Exception {
        Path harness = Path.of("target/angular-harness");
        Files.createDirectories(harness);
        for (String file : List.of("package.json", "package-lock.json", "tsconfig.json"))
            Files.copy(Path.of("src/test/angular-harness", file), harness.resolve(file), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        for (var artifact : result.artifacts()) {
            Path destination = harness.resolve(artifact.relativePath());
            Files.createDirectories(destination.getParent()); Files.write(destination, artifact.bytes());
        }
        String npm = System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win") ? "npm.cmd" : "npm";
        if (!Files.isDirectory(harness.resolve("node_modules/@angular/compiler-cli"))) {
            Process install = new ProcessBuilder(npm, "ci", "--offline", "--ignore-scripts", "--no-audit", "--no-fund")
                    .directory(harness.toFile()).redirectErrorStream(true).start();
            String installOutput = new String(install.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            assertEquals(0, install.waitFor(), "Pinned Angular dependencies unavailable in the local npm cache: " + installOutput);
        }
        Process process = new ProcessBuilder(npm, "run", "check")
                .directory(harness.toFile()).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertEquals(0, process.waitFor(), output);
    }

    private void verifyGeneratedVisibilityRuntime() throws Exception {
        String[][] samples = {
                {"{\"op\":\"role\",\"value\":\"EDITOR\"}", "{\"roles\":[\"EDITOR\"],\"permissions\":[],\"fields\":{}}"},
                {"{\"op\":\"permission\",\"value\":\"READ\"}", "{\"roles\":[],\"permissions\":[\"READ\"],\"fields\":{}}"},
                {"{\"op\":\"NOT\",\"rule\":{\"op\":\"field\",\"field\":\"count\",\"equals\":1}}", "{\"roles\":[],\"permissions\":[],\"fields\":{\"count\":\"OPEN\"}}"},
                {"{\"op\":\"NOT\",\"rule\":{\"op\":\"field\",\"field\":\"status\",\"equals\":\"OPEN\"}}", "{\"roles\":[],\"permissions\":[],\"fields\":{\"status\":1}}"},
                {"{\"op\":\"field\",\"field\":\"count\",\"equals\":1}", "{\"roles\":[],\"permissions\":[],\"fields\":{\"count\":1}}"},
                {"{\"op\":\"field\",\"field\":\"count\",\"equals\":1.5}", "{\"roles\":[],\"permissions\":[],\"fields\":{\"count\":1.5}}"},
                {"{\"op\":\"field\",\"field\":\"count\",\"equals\":-0.0}", "{\"roles\":[],\"permissions\":[],\"fields\":{\"count\":0.0}}"},
                {"{\"op\":\"OR\",\"rules\":[{\"op\":\"role\",\"value\":\"EDITOR\"},{\"op\":\"permission\",\"value\":\"READ\"}]}", "{\"roles\":[],\"permissions\":[\"READ\"],\"fields\":{}}"},
                {"{\"op\":\"AND\",\"rules\":[{\"op\":\"role\",\"value\":\"EDITOR\"},{\"op\":\"permission\",\"value\":\"READ\"}]}", "{\"roles\":[\"EDITOR\"],\"permissions\":[],\"fields\":{}}"},
                {"{\"op\":\"NOT\",\"rule\":{\"op\":\"unknown\"}}", "{\"roles\":[],\"permissions\":[],\"fields\":{}}"},
                {"{\"op\":\"OR\",\"rules\":[{\"op\":\"role\",\"value\":\"EDITOR\"},{\"op\":\"unknown\"}]}", "{\"roles\":[\"EDITOR\"],\"permissions\":[],\"fields\":{}}"},
                {"{\"op\":\"role\",\"value\":\"EDITOR\"}", "null"},
                {"{\"op\":\"field\",\"field\":\"count\",\"equals\":1}", "{\"roles\":[],\"permissions\":[],\"fields\":[]}"}
        };
        var cases = mapper.createArrayNode();
        for (String[] sample : samples) {
            var rule = mapper.readTree(sample[0]);
            var context = mapper.readTree(sample[1]);
            Set<String> roles = context.isNull() ? null : mapper.convertValue(context.path("roles"), new com.fasterxml.jackson.core.type.TypeReference<Set<String>>() {});
            Set<String> permissions = context.isNull() ? null : mapper.convertValue(context.path("permissions"), new com.fasterxml.jackson.core.type.TypeReference<Set<String>>() {});
            boolean expected = VisibilityRuleEvaluator.evaluate(rule, context.isNull() ? null
                    : new VisibilityRuleEvaluator.Context(permissions, roles, context.path("fields")));
            var item = cases.addObject(); item.set("rule", runtimeRule(rule)); item.set("context", context); item.put("expected", expected);
        }
        Path harness = Path.of("target/angular-harness");
        Files.copy(Path.of("src/test/angular-harness/visibility-runtime.mjs"), harness.resolve("visibility-runtime.mjs"),
                java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        Path casesPath = harness.resolve("visibility-cases.json");
        Files.writeString(casesPath, mapper.writeValueAsString(cases));
        String node = System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win") ? "node.exe" : "node";
        Process process = new ProcessBuilder(node, "visibility-runtime.mjs", "src/app/generated/angular-visibility.ts",
                "visibility-cases.json").directory(harness.toFile()).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertEquals(0, process.waitFor(), output);
    }

    private com.fasterxml.jackson.databind.JsonNode runtimeRule(com.fasterxml.jackson.databind.JsonNode rule) {
        var copy = ((ObjectNode) rule).deepCopy();
        if ("field".equals(copy.path("op").asText()) && copy.path("equals").isNumber())
            copy.putObject("equals").put("$jsonNumber", rule.path("equals").toString());
        if (copy.path("rule").isObject()) copy.set("rule", runtimeRule(copy.path("rule")));
        if (copy.path("rules").isArray()) {
            var children = mapper.createArrayNode();
            for (var child : copy.path("rules")) children.add(runtimeRule(child));
            copy.set("rules", children);
        }
        return copy;
    }

    private ObjectNode bindings(String entity) {
        ObjectNode snapshot = emptySnapshot();
        String path = entity.equals("AssetTicket") ? "asset-tickets"
                : entity.equals("CaseRecord") ? "case-records" : "service-requests";
        String[] verbs = {"GET", "POST", "GET", "PUT", "DELETE"};
        for (int i = 0; i < 5; i++) {
            ObjectNode b = snapshot.withArray("apiBindings").addObject();
            b.put("id", new UUID(0, i + 1).toString()); b.put("httpMethod", verbs[i]);
            b.put("endpointUrl", "/api/v1/" + path + (i > 1 ? "/{id}" : "")); b.putObject("headers");
            ObjectNode request = b.putObject("requestMapping");
            if (i == 0) request.putObject("query").put("status", "status");
            if (i > 1) request.putObject("path").put("id", "id");
            if (i == 1 || i == 3) {
                ObjectNode body = request.putObject("body");
                body.put("title", "title").put("status", "status");
                if (entity.equals("ServiceRequest")) body.put("dueDate", "dueDate");
            }
            ObjectNode response = b.putObject("responseMapping");
            if (i == 0) response.put("list", "body.data"); else response.put("item", "body");
        }
        return snapshot;
    }

    private GenerationSpecification spec(String entity, String contract, Map<UUID, String> bindings,
                                         Map<String, Set<SupportedRole>> roles) {
        List<FieldSpec> fields = new ArrayList<>();
        fields.add(new FieldSpec("title", "title", "String", false, 100, null));
        fields.add(new FieldSpec("status", "status", entity + "Status", false, null, entity + "Status"));
        if (entity.equals("ServiceRequest")) fields.add(new FieldSpec("dueDate", "due_date", "LocalDate", false, null, null));
        return new GenerationSpecification("1.0.0", "com.example.fixture",
                new EntitySpec(entity, entity.equals("ServiceRequest") ? "service_request"
                        : entity.equals("CaseRecord") ? "case_record" : "asset_ticket", fields, false),
                new OpenApiSpec(contract, "1.0.0", bindings), roles, new AngularSpec(entity, Map.of()));
    }

    private MetadataVersion version(String schema, ObjectNode snapshot) throws Exception {
        GsuifProject project = new GsuifProject(); project.setName("Fixture");
        GsuifPage page = new GsuifPage(); page.setProject(project);
        MetadataVersion version = new MetadataVersion(); version.setPage(page);
        version.setSchemaVersion(schema); version.setSnapshot(mapper.writeValueAsString(snapshot));
        return version;
    }
}
