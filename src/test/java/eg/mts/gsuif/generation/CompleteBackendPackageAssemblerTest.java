package eg.mts.gsuif.generation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import eg.mts.gsuif.generation.GenerationCapabilityPreflight.OutputMode;
import eg.mts.gsuif.generation.GenerationSpecification.*;
import eg.mts.gsuif.validator.MetadataSchemaValidator;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class CompleteBackendPackageAssemblerTest {
    private final CompleteOutputTestFixture fixture = new CompleteOutputTestFixture();
    private final ObjectMapper json = new ObjectMapper();
    private static final CompleteBackendPackageAssembler.Provenance MOCK =
            new CompleteBackendPackageAssembler.Provenance("mock-provider", "test-1", "mock-model", true);

    private record Selected(BackendModelInput input, CompleteBackendPackageAssembler assembler) { }

    private Selected stock(boolean audited) throws Exception {
        var descriptor = fixture.preflight().describe(fixture.version(fixture.snapshot("condition")),
                fixture.specification(), OutputMode.BACKEND);
        if (audited) descriptor = fixture.auditedSchemaDescriptor(descriptor, descriptor.tableName());
        return selected(descriptor, new PackagedContractRegistry("/generation/stock-unit-contracts.yaml"));
    }

    private Selected gadget() throws Exception {
        var registry = new PackagedContractRegistry("/generation/gadget-contracts.yaml");
        var preflight = new GenerationCapabilityPreflight(
                new GenerationContextBuilder(fixture.mapper, new MetadataSchemaValidator(), registry), registry);
        List<String> operations = List.of("fetchGadgets", "submitGadget", "findGadget", "replaceGadget", "removeGadget");
        Map<UUID, String> bindings = new java.util.LinkedHashMap<>();
        Map<String, Set<SupportedRole>> roles = new java.util.LinkedHashMap<>();
        for (int i = 0; i < operations.size(); i++) {
            bindings.put(new UUID(0, i + 1), operations.get(i));
            roles.put(operations.get(i), Set.of(SupportedRole.ROLE_ADMIN));
        }
        var specification = new GenerationSpecification("1.0.0", "com.example.fixture",
                new EntitySpec("Gadget", "gadget", List.of(
                        new FieldSpec("title", "title", "String", false, 100, null),
                        new FieldSpec("status", "status", "GadgetStatus", false, null, "GadgetStatus"),
                        new FieldSpec("note", "note", "String", true, 100, null)), false),
                new OpenApiSpec("gadget-api", "2.0.0", bindings), roles, new AngularSpec("Gadget", Map.of()));
        ObjectNode snapshot = fixture.snapshot("condition");
        for (int i = 0; i < 5; i++) {
            ObjectNode binding = (ObjectNode) snapshot.withArray("apiBindings").get(i);
            binding.put("endpointUrl", binding.path("endpointUrl").asText().replace("stock-units", "gadgets"));
            if (i == 0) {
                ObjectNode query = (ObjectNode) binding.path("requestMapping").path("query");
                query.removeAll(); query.put("status", "status");
            }
            if (i == 1 || i == 3) {
                ObjectNode body = (ObjectNode) binding.path("requestMapping").path("body");
                body.removeAll(); body.put("title", "title").put("status", "status").put("note", "note");
            }
        }
        return selected(preflight.describe(fixture.version(snapshot), specification, OutputMode.BACKEND), registry);
    }

    private Selected selected(CompleteOutputDescriptor descriptor, PackagedContractRegistry registry) {
        var plan = GenerationFilePlan.forDescriptor(descriptor);
        var runtime = new CompleteBackendRuntimeRenderer(registry);
        return new Selected(BackendModelInput.from(descriptor, plan,
                new CompleteBackendDefinitionRenderer().render(descriptor, plan), runtime),
                new CompleteBackendPackageAssembler(runtime));
    }

    private BackendModelOutputValidator.Accepted accepted(BackendModelInput input) throws Exception {
        String source = "package " + input.descriptor().basePackage() + ".service; public class "
                + input.descriptor().entityName() + "ServiceImpl implements "
                + input.descriptor().contractServiceName() + " {}";
        ObjectNode response = json.createObjectNode();
        response.putArray("files").addObject().put("path", input.writablePath()).put("content", source);
        return new BackendModelOutputValidator((selected, bytes) -> true)
                .validate(input, json.writeValueAsBytes(response));
    }

    private static String sha(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    private Map<String, byte[]> deterministic(CompleteBackendPackageAssembler.Assembled assembled,
            BackendModelInput input) {
        Map<String, byte[]> files = new TreeMap<>(assembled.files());
        files.remove("generation-manifest.json");
        files.remove(input.writablePath());
        return files;
    }

    @Test void combinesExactPlanAndHashesForRenamedAuditedAndUnauditedDomains() throws Exception {
        for (Selected selected : List.of(stock(false), stock(true), gadget())) {
            var input = selected.input();
            var output = selected.assembler().assemble(input, accepted(input), MOCK);
            assertEquals(input.plan().entries().stream().map(GenerationFilePlan.Entry::path).toList(),
                    output.files().keySet().stream().toList());
            var manifest = json.readTree(output.file("generation-manifest.json"));
            assertTrue(manifest.path("provider").path("mocked").asBoolean());
            assertEquals("mock-provider", manifest.path("provider").path("id").asText());
            assertEquals(input.descriptor().contractId(), manifest.path("contract").path("id").asText());
            assertEquals(input.descriptor().contractVersion(), manifest.path("contract").path("version").asText());
            assertEquals(input.descriptor().contractResourceSha256(), manifest.path("contract").path("sha256").asText());
            assertEquals(output.files().size() - 1, manifest.path("files").size());
            for (var item : manifest.path("files")) {
                String path = item.path("path").asText();
                assertNotEquals("generation-manifest.json", path);
                assertEquals(sha(output.file(path)), item.path("sha256").asText());
                assertEquals(output.file(path).length, item.path("bytes").asInt());
                assertEquals(input.plan().entries().stream().filter(entry -> entry.path().equals(path))
                        .findFirst().orElseThrow().owner().name(), item.path("owner").asText());
            }
            assertArrayEquals(input.contract(), output.file("contracts/" + input.descriptor().contractId()
                    + "-" + input.descriptor().contractVersion() + ".yaml"));
            assertEquals(input.descriptor().enversAudited(), output.files().keySet().stream()
                    .anyMatch(path -> path.endsWith("/audit/AppRevision.java")));
            String other = input.descriptor().entityName().equals("Gadget") ? "StockUnit" : "Gadget";
            assertTrue(output.files().keySet().stream().noneMatch(path -> path.contains(other)));
            assertFalse(new String(output.file("generation-manifest.json"), StandardCharsets.UTF_8)
                    .contains("OPENROUTER_API_KEY"));
            assertArrayEquals(output.file("generation-manifest.json"),
                    selected.assembler().assemble(input, accepted(input), MOCK).file("generation-manifest.json"));
        }
    }

    @Test void returnedBytesAndMapsAreDefensiveCopies() throws Exception {
        var selected = stock(false);
        var output = selected.assembler().assemble(selected.input(), accepted(selected.input()), MOCK);
        String path = selected.input().writablePath();
        byte original = output.file(path)[0];
        output.file(path)[0] ^= 1;
        output.files().get(path)[0] ^= 1;
        assertEquals(original, output.file(path)[0]);
        assertThrows(UnsupportedOperationException.class, () -> output.files().clear());
        Map<String, byte[]> supplied = deterministic(output, selected.input());
        var combined = CompleteBackendPackageAssembler.combine(selected.input(), accepted(selected.input()),
                MOCK, List.of(supplied));
        String suppliedPath = supplied.keySet().iterator().next();
        byte initial = combined.file(suppliedPath)[0];
        supplied.get(suppliedPath)[0] ^= 1;
        assertEquals(initial, combined.file(suppliedPath)[0]);
    }

    @Test void rejectsDifferentInvocationAndNonBackendModes() throws Exception {
        var first = stock(false);
        var second = stock(false);
        assertThrows(IllegalArgumentException.class, () -> first.assembler()
                .assemble(first.input(), accepted(second.input()), MOCK));
        for (OutputMode mode : List.of(OutputMode.FRONTEND, OutputMode.FULL_STACK)) {
            var original = first.input().descriptor();
            var changed = new CompleteOutputDescriptor(original.metadataVersionId(), original.metadataVersionNumber(),
                    original.metadataSchemaVersion(), original.specificationVersion(), original.validatedSnapshotJson(),
                    original.pageId(), original.pageName(), original.pageRoute(), original.projectId(), original.projectName(),
                    original.framework(), original.catalogVersion(), original.catalogAssets(), original.catalogProviderVersion(),
                    original.standardRefs(), mode, original.basePackage(), original.entityName(), original.tableName(),
                    original.enversAudited(), original.fields(), original.angularClassName(), original.angularTableColumns(),
                    original.contractId(), original.contractVersion(), original.contractResourceSha256(),
                    original.contractDtoPackage(), original.contractServicePackage(), original.contractInterfacePackage(),
                    original.contractServiceName(), original.contractInterfaceName(), original.dtoName(), original.pageDtoName(),
                    original.createRequestName(), original.updateRequestName(), original.createRules(), original.createFields(),
                    original.updateRules(), original.editableFields(), original.responseFields(), original.operations());
            if (mode == OutputMode.FRONTEND) {
                assertThrows(IllegalArgumentException.class,
                        () -> selected(changed, new PackagedContractRegistry("/generation/stock-unit-contracts.yaml")));
            } else {
                var changedInput = selected(changed, new PackagedContractRegistry("/generation/stock-unit-contracts.yaml"));
                assertThrows(IllegalArgumentException.class, () -> first.assembler()
                        .assemble(changedInput.input(), accepted(changedInput.input()), MOCK));
            }
        }
    }

    @Test void rejectsMissingExtraDuplicateUnsafeWrongOwnerAndChangedBytes() throws Exception {
        var selected = stock(false);
        var input = selected.input();
        var model = accepted(input);
        var output = selected.assembler().assemble(input, model, MOCK);
        Map<String, byte[]> base = deterministic(output, input);
        String path = base.keySet().iterator().next();
        Map<String, byte[]> missing = new TreeMap<>(base); missing.remove(path);
        assertThrows(IllegalArgumentException.class, () -> CompleteBackendPackageAssembler.combine(
                input, model, MOCK, List.of(missing)));
        for (String bad : List.of("other.java", "../escape.java", input.writablePath(), "generation-manifest.json",
                path.toUpperCase())) {
            Map<String, byte[]> changed = new TreeMap<>(base); changed.put(bad, new byte[]{1});
            assertThrows(IllegalArgumentException.class, () -> CompleteBackendPackageAssembler.combine(
                    input, model, MOCK, List.of(changed)));
        }
        assertThrows(IllegalArgumentException.class, () -> CompleteBackendPackageAssembler.combine(
                input, model, MOCK, List.of(base, Map.of(path, base.get(path)))));
        Map<String, byte[]> tampered = new TreeMap<>(base);
        byte[] contract = tampered.get("contracts/stock-unit-api-2.0.0.yaml").clone(); contract[0] ^= 1;
        tampered.put("contracts/stock-unit-api-2.0.0.yaml", contract);
        assertThrows(IllegalArgumentException.class, () -> CompleteBackendPackageAssembler.combine(
                input, model, MOCK, List.of(tampered)));
        Map<String, byte[]> definitionChanged = new TreeMap<>(base);
        String definitionPath = input.handoff().definitions().getFirst().path();
        byte[] definition = definitionChanged.get(definitionPath).clone(); definition[0] ^= 1;
        definitionChanged.put(definitionPath, definition);
        assertThrows(IllegalArgumentException.class, () -> CompleteBackendPackageAssembler.combine(
                input, model, MOCK, List.of(definitionChanged)));
    }
}
