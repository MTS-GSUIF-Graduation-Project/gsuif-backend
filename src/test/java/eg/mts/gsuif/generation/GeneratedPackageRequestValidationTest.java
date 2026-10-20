package eg.mts.gsuif.generation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import eg.mts.gsuif.generation.GenerationCapabilityPreflight.OutputMode;
import eg.mts.gsuif.generation.GenerationSpecification.AngularSpec;
import eg.mts.gsuif.generation.GenerationSpecification.EntitySpec;
import eg.mts.gsuif.generation.GenerationSpecification.FieldSpec;
import eg.mts.gsuif.generation.GenerationSpecification.OpenApiSpec;
import eg.mts.gsuif.generation.GenerationSpecification.SupportedRole;
import eg.mts.gsuif.validator.MetadataSchemaValidator;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.io.TempDir;

import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * SCRUM-50 request validation of NEWLY generated packages, enforced (not pinned): every applicable
 * mutation family must be rejected with HTTP 400 keyed by the offending contract field. The
 * historical ZIP stays frozen as evidence and is scored separately.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class GeneratedPackageRequestValidationTest {
    private static final Set<String> STD06_LAYERS = Set.of("config", "controller", "dto", "entity", "repository",
            "service", "exception", "aspect", "security", "audit", "util");
    private final ObjectMapper json = new ObjectMapper();
    private final List<URLClassLoader> loaders = new ArrayList<>();

    @AfterAll
    void close() throws Exception {
        for (var loader : loaders) loader.close();
    }

    // ---- regression: the contract of the historical run, regenerated with today's generator ----

    @Test
    void regeneratedStockUnitPackageRejectsEveryApplicableMutationFamily(@TempDir Path scratch) throws Exception {
        var fixture = new CompleteOutputTestFixture();
        var historical = FinalGeneratedOutput.load();
        Map<String, byte[]> files = regenerateStockUnit(fixture, historical);
        Path root = extract(files, scratch.resolve("package"));
        var entityFields = fields(fixture.pagedSpecification().entity().fields());
        List<String> notes = new ArrayList<>();
        var cases = ContractDrivenRequestHarness.derive(files.get(FinalGeneratedOutput.CONTRACT_PATH), entityFields, notes);
        var mvc = ContractDrivenRequestHarness.compile(root.resolve(FinalGeneratedOutput.JAVA_ROOT),
                FinalGeneratedOutput.BASE_PACKAGE, FinalGeneratedOutput.ENTITY, scratch.resolve("classes"), loaders);
        var report = ContractDrivenRequestHarness.run("regenerated StockUnit package (stock-unit-api 3.0.0)",
                cases, notes, ContractDrivenRequestHarness.endpoint(mvc));
        ContractDrivenRequestHarness.write(report, "regenerated-stock-unit-validation-score.txt");
        assertEquals(List.of(), report.wronglyRejected(), "Valid contract bodies must be accepted");
        assertEquals(report.applicable(), report.detected(), "Every applicable mutation family must be detected");
        assertTrue(report.applicable().containsAll(List.of("REQUIRED_FIELD", "WRONG_TYPE", "INVALID_ENUM",
                "DUPLICATE_VALUE", "BOUNDARY_VIOLATION")), report.applicable().toString());

        String readme = new String(files.get("README.md"), StandardCharsets.UTF_8);
        assertTrue(readme.contains("CreateStockUnitRequest.label: contract declares no maxLength; enforcing the persistence column length 100"),
                "The contract gap must be reported explicitly");
        String create = text(files, "dto/CreateStockUnitRequest.java");
        assertTrue(create.contains("@jakarta.validation.constraints.Size(max = 100)"), create);
    }

    @Test
    void historicalZipScoredWithTheSameDerivedCorpusIsWeakerThanRegeneratedOutput(@TempDir Path scratch) throws Exception {
        var fixture = new CompleteOutputTestFixture();
        var historical = FinalGeneratedOutput.load();
        Path zipRoot = extract(historical.files(), scratch.resolve("historical"));
        var entityFields = fields(fixture.pagedSpecification().entity().fields());
        List<String> notes = new ArrayList<>();
        var cases = ContractDrivenRequestHarness.derive(historical.bytes(FinalGeneratedOutput.CONTRACT_PATH), entityFields, notes);
        var historicalReport = ContractDrivenRequestHarness.run("historical ZIP 6b1f398c (unchanged evidence)", cases, notes,
                ContractDrivenRequestHarness.endpoint(ContractDrivenRequestHarness.compile(
                        zipRoot.resolve(FinalGeneratedOutput.JAVA_ROOT), FinalGeneratedOutput.BASE_PACKAGE,
                        FinalGeneratedOutput.ENTITY, scratch.resolve("historical-classes"), loaders)));
        ContractDrivenRequestHarness.write(historicalReport, "historical-zip-derived-corpus-score.txt");
        assertEquals(FinalGeneratedOutput.ZIP_SHA256, historical.zipSha256(), "Historical evidence must stay unchanged");
        assertTrue(historicalReport.detected().size() < historicalReport.applicable().size(),
                "The historical output is known to miss families; it is evidence, not the target");
    }

    // ---- generality: a different contract with different and per-operation constraints ----

    @Test
    void equipmentLoanContractConstraintsAreGeneratedAndEnforced(@TempDir Path scratch) throws Exception {
        var loan = new EquipmentLoanFixture("/generation/equipment-loan-contracts.yaml");
        var descriptor = loan.describe();
        var plan = GenerationFilePlan.forDescriptor(descriptor);
        Map<String, byte[]> files = renderRequestLayer(descriptor, plan, loan.registry);
        String open = text(files, "dto/OpenEquipmentLoanRequest.java", "com/example/lending");
        String revise = text(files, "dto/ReviseEquipmentLoanRequest.java", "com/example/lending");
        assertTrue(open.contains("@jakarta.validation.constraints.Size(min = 4, max = 12)"), open);
        assertTrue(open.contains("@jakarta.validation.constraints.Pattern(regexp = \"(?s).*(?:^[A-Z]{2}-[0-9]+$).*\")"), open);
        assertTrue(open.contains("@jakarta.validation.constraints.Min(1L)") && open.contains("@jakarta.validation.constraints.Max(25L)"), open);
        assertTrue(open.contains("@jakarta.validation.constraints.Size(max = 500)"), "notes falls back to its column length");
        assertTrue(revise.contains("@jakarta.validation.constraints.Min(0L)") && revise.contains("@jakarta.validation.constraints.Max(40L)"), revise);
        assertTrue(revise.contains("@jakarta.validation.constraints.Size(max = 300)"), "update notes uses its contract maxLength");
        assertFalse(open.contains("StockUnit") || open.contains("label") || open.contains("condition"), "No example names leak");

        String readme = new String(files.get("README.md"), StandardCharsets.UTF_8);
        evidence("equipment-loan-generated-request-layer.txt", "# Generated OpenEquipmentLoanRequest.java\n" + open
                + "\n# Generated ReviseEquipmentLoanRequest.java\n" + revise + "\n# Generated README.md (request validation section)\n"
                + readme.substring(readme.indexOf("## Request validation"), readme.indexOf("## Dependency choices")));
        assertTrue(readme.contains("OpenEquipmentLoanRequest.notes: contract declares no maxLength; enforcing the persistence column length 500"), readme);
        assertFalse(readme.contains("ReviseEquipmentLoanRequest.notes: contract declares no maxLength"), readme);
        assertFalse(readme.contains("assetTag: contract declares no maxLength"), readme);

        Path root = extract(files, scratch.resolve("package"));
        List<String> notes = new ArrayList<>();
        var cases = ContractDrivenRequestHarness.derive(files.get("contracts/equipment-loan-api-1.0.0.yaml"),
                fields(loan.specification().entity().fields()), notes);
        var mvc = ContractDrivenRequestHarness.compile(root.resolve("backend/src/main/java/com/example/lending"),
                "com.example.lending", "EquipmentLoan", scratch.resolve("classes"), loaders);
        var report = ContractDrivenRequestHarness.run("generated EquipmentLoan package (equipment-loan-api 1.0.0)",
                cases, notes, ContractDrivenRequestHarness.endpoint(mvc));
        ContractDrivenRequestHarness.write(report, "generated-equipment-loan-validation-score.txt");
        assertEquals(List.of(), report.wronglyRejected(), "Valid contract bodies must be accepted");
        assertEquals(List.of(), report.notes(), "Every case must be derivable from the contract");
        assertEquals(Set.copyOf(ContractDrivenRequestHarness.FAMILIES), report.applicable());
        assertEquals(report.applicable(), report.detected(), "Every mutation family must be detected");
    }

    // ---- explicit reporting of conflicts and unsupported features ----

    @Test
    void unsupportedContractFeaturesStopGenerationWithNamedDiagnostics() throws Exception {
        var loan = variant("unsupported", contract -> {
            int create = contract.indexOf("OpenEquipmentLoanRequest:");
            String head = contract.substring(0, create), tail = contract.substring(create);
            tail = tail.replaceFirst("minimum: 1, maximum: 25 }", "minimum: 1, maximum: 25, multipleOf: 5 }")
                    .replaceFirst("notes: \\{ type: string, nullable: true }", "notes: { type: string, format: email, nullable: true }");
            return head + tail;
        });
        List<String> diagnostics = assertThrows(GenerationValidationException.class, loan::describe).diagnostics();
        evidence("contract-unsupported-diagnostics.txt", String.join("\n", diagnostics) + "\n");
        assertTrue(diagnostics.contains("contract.create.quantity: unsupported schema keywords [multipleOf]"), diagnostics.toString());
        assertTrue(diagnostics.contains("contract.create.notes: unsupported type/format 'string:email'"), diagnostics.toString());
    }

    @Test
    void contractPersistenceConflictsStopGenerationWithNamedDiagnostics() throws Exception {
        var loan = variant("conflict", contract -> contract
                .replaceFirst("minLength: 4, maxLength: 12", "minLength: 4, maxLength: 30")
                .replace("maxLength: 300, nullable: true", "maxLength: 900, nullable: true"));
        List<String> diagnostics = assertThrows(GenerationValidationException.class, loan::describe).diagnostics();
        evidence("contract-conflict-diagnostics.txt", String.join("\n", diagnostics) + "\n");
        assertTrue(diagnostics.contains("contract.create.assetTag: contract maxLength 30 exceeds the persistence column length 20"
                + " (accepted values could not be stored)"), diagnostics.toString());
        assertTrue(diagnostics.contains("contract.update.notes: contract maxLength 900 exceeds the persistence column length 500"
                + " (accepted values could not be stored)"), diagnostics.toString());
    }

    /** Registers an edited copy of the EquipmentLoan contract next to the original test resource. */
    private EquipmentLoanFixture variant(String name, java.util.function.UnaryOperator<String> edit) throws Exception {
        Path directory = Path.of(getClass().getResource("/openapi/equipment-loan-api.yaml").toURI()).getParent();
        String original = Files.readString(directory.resolve("equipment-loan-api.yaml"));
        String edited = edit.apply(original.replace("version: 1.0.0", "version: 9.0.0"));
        assertNotEquals(original.replace("version: 1.0.0", "version: 9.0.0"), edited, "Variant must change the contract");
        Files.writeString(directory.resolve("equipment-loan-api-" + name + ".yaml"), edited);
        Path registry = Path.of(getClass().getResource("/generation/equipment-loan-contracts.yaml").toURI())
                .resolveSibling("equipment-loan-" + name + "-contracts.yaml");
        Files.writeString(registry, Files.readString(registry.resolveSibling("equipment-loan-contracts.yaml"))
                .replace("1.0.0:", "9.0.0:").replace("equipment-loan-api.yaml", "equipment-loan-api-" + name + ".yaml"));
        return new EquipmentLoanFixture("/generation/equipment-loan-" + name + "-contracts.yaml", "9.0.0");
    }

    @Test
    void schemaProblemsAreNamedPerKeyword() {
        Map<String, Object> schemas = Map.of("Kind", Map.of("type", "string", "enum", List.of("A")));
        assertEquals(List.of("unsupported schema keywords [exclusiveMinimum, multipleOf]"),
                PackagedContractLoader.requestFieldSchemaProblems(Map.of("type", "integer", "format", "int32",
                        "multipleOf", 2, "exclusiveMinimum", 0), schemas));
        assertEquals(List.of("'maxLength' is only supported on plain string fields (not integer:int64)"),
                PackagedContractLoader.requestFieldSchemaProblems(Map.of("type", "integer", "format", "int64", "maxLength", 3), schemas));
        assertEquals(List.of("minLength 5 exceeds maxLength 2 (no value can satisfy the contract)"),
                PackagedContractLoader.requestFieldSchemaProblems(Map.of("type", "string", "minLength", 5, "maxLength", 2), schemas));
        assertTrue(PackagedContractLoader.requestFieldSchemaProblems(Map.of("type", "string", "pattern", "[unclosed"), schemas)
                .getFirst().startsWith("'pattern' is not a supported regular expression (Java syntax)"));
        assertEquals(List.of("unsupported keywords next to $ref [maxLength] (OpenAPI 3.0 ignores $ref siblings; declare them on the referenced schema)"),
                PackagedContractLoader.requestFieldSchemaProblems(Map.of("$ref", "#/components/schemas/Kind", "maxLength", 3), schemas));
        assertEquals(List.of("'minimum' is only supported on integer fields (decimal and number bounds are not)"),
                PackagedContractLoader.requestFieldSchemaProblems(Map.of("type", "number", "minimum", 1), schemas));
    }

    @Test
    void persistenceReconciliationReportsGapsAndConflicts() {
        var text = new FieldSpec("code", "code", "String", false, 40, null);
        var none = new PackagedContractLoader.FieldSchema("string", "", "", List.of(), false);
        var gap = RequestValidationRules.evaluate("contract.create.code", text, none);
        assertEquals(List.of("contract.create.code: contract declares no maxLength; enforcing the persistence column length 40"), gap.gaps());
        assertTrue(gap.annotations().contains("@jakarta.validation.constraints.Size(max = 40)"));
        var defaultColumn = RequestValidationRules.evaluate("contract.create.code",
                new FieldSpec("code", "code", "String", false, null, null), none);
        assertTrue(defaultColumn.annotations().contains("@jakarta.validation.constraints.Size(max = 255)"));
        var tooLong = new PackagedContractLoader.FieldSchema("string", "", "", List.of(), false,
                new PackagedContractLoader.Constraints(50, 60, null, null, null));
        assertEquals(List.of("contract.create.code: contract maxLength 60 exceeds the persistence column length 40 (accepted values could not be stored)",
                "contract.create.code: contract minLength 50 exceeds the enforceable maximum length 40 (no value can satisfy it)"),
                RequestValidationRules.evaluate("contract.create.code", text, tooLong).errors());
        var outOfRange = new PackagedContractLoader.FieldSchema("integer", "int32", "", List.of(), false,
                new PackagedContractLoader.Constraints(null, null, null, java.math.BigInteger.valueOf(-1),
                        java.math.BigInteger.valueOf(3_000_000_000L)));
        assertEquals(List.of("contract.create.count: contract maximum 3000000000 is outside the int32 range"),
                RequestValidationRules.evaluate("contract.create.count", new FieldSpec("count", "count", "Integer", false, null, null),
                        outOfRange).errors());
    }

    // ---- STD-06 against its wording: classes must live in the designated layers ----

    @Test
    void newlyGeneratedPackageLayersAgainstStd06() throws Exception {
        var fixture = new CompleteOutputTestFixture();
        Map<String, byte[]> files = regenerateStockUnit(fixture, FinalGeneratedOutput.load());
        Map<String, Set<String>> outside = new TreeMap<>();
        String prefix = FinalGeneratedOutput.JAVA_ROOT;
        for (String path : files.keySet()) {
            if (!path.startsWith(prefix) || !path.endsWith(".java")) continue;
            String relative = path.substring(prefix.length());
            String layer = relative.contains("/") ? relative.substring(0, relative.indexOf('/')) : "(base package)";
            if (!STD06_LAYERS.contains(layer)) outside.computeIfAbsent(layer, key -> new TreeSet<>()).add(relative);
        }
        // New SCRUM-50 code lives in designated layers (config/, exception/, dto/).
        assertTrue(files.containsKey(prefix + "config/RequestJsonStrictnessConfig.java"));
        // These pre-existing packages are outside STD-06's designated list. They are OPEN
        // non-conformances recorded in generated-output-conformance-evidence.md, not accepted exceptions;
        // this assertion only prevents the set from growing unnoticed.
        assertEquals(Set.of("(base package)", "api", "bootstrap", "filter", "logging"), outside.keySet(), outside.toString());
        StringBuilder listing = new StringBuilder("STD-06 designated layers: ").append(new TreeSet<>(STD06_LAYERS)).append('\n')
                .append("Generated classes outside a designated layer (open non-conformances, not exceptions):\n");
        outside.forEach((layer, classes) -> listing.append("  ").append(layer).append(": ").append(classes).append('\n'));
        listing.append("New SCRUM-50 file: config/RequestJsonStrictnessConfig.java (designated layer: config)\n");
        evidence("std06-generated-layers.txt", listing.toString());
        System.out.print(listing);
    }

    private static void evidence(String file, String content) throws Exception {
        Path directory = Files.createDirectories(Path.of("target", "generation-evidence"));
        Files.writeString(directory.resolve(file), content);
    }

    // ---- helpers ----

    private Map<String, byte[]> regenerateStockUnit(CompleteOutputTestFixture fixture, FinalGeneratedOutput historical) throws Exception {
        var descriptor = fixture.preflight().describe(fixture.version(fixture.pagedSnapshot()),
                fixture.pagedSpecification(), OutputMode.BACKEND);
        var registry = new PackagedContractRegistry("/generation/stock-unit-contracts.yaml");
        var plan = GenerationFilePlan.forDescriptor(descriptor);
        var runtime = new CompleteBackendRuntimeRenderer(registry);
        var input = BackendModelInput.from(descriptor, plan,
                new CompleteBackendDefinitionRenderer().render(descriptor, plan), runtime);
        ObjectNode response = json.createObjectNode();
        response.putArray("files").addObject().put("path", input.writablePath())
                .put("content", historical.text(FinalGeneratedOutput.SERVICE_IMPL_PATH));
        var accepted = new BackendModelOutputValidator((chosen, bytes) -> true).validate(input, json.writeValueAsBytes(response));
        return new CompleteBackendPackageAssembler(runtime).assemble(input, accepted,
                new CompleteBackendPackageAssembler.Provenance("mock-provider", "test-1", "mock-model", true)).files();
    }

    private static Map<String, byte[]> renderRequestLayer(CompleteOutputDescriptor descriptor, GenerationFilePlan plan,
                                                          PackagedContractRegistry registry) {
        Map<String, byte[]> files = new TreeMap<>(new CompleteBackendDefinitionRenderer().render(descriptor, plan).files());
        files.putAll(new CompleteBackendApiRenderer().render(descriptor, plan));
        files.putAll(new CompleteBackendErrorRenderer().render(descriptor, plan));
        files.putAll(new CompleteBackendOpenApiRenderer().render(descriptor, plan));
        files.putAll(new CompleteBackendObservabilityRenderer().render(descriptor, plan));
        files.putAll(new CompleteBackendRuntimeRenderer(registry).render(descriptor, plan));
        return files;
    }

    private static Path extract(Map<String, byte[]> files, Path directory) throws Exception {
        for (var file : files.entrySet()) {
            Path destination = directory.resolve(file.getKey()).normalize();
            assertTrue(destination.startsWith(directory));
            Files.createDirectories(destination.getParent());
            Files.write(destination, file.getValue());
        }
        return directory;
    }

    private static Map<String, FieldSpec> fields(List<FieldSpec> fields) {
        Map<String, FieldSpec> byName = new LinkedHashMap<>();
        fields.forEach(field -> byName.put(field.name(), field));
        return byName;
    }

    private static String text(Map<String, byte[]> files, String relative) {
        return text(files, relative, "com/example/inventory");
    }

    private static String text(Map<String, byte[]> files, String relative, String packagePath) {
        byte[] bytes = files.get("backend/src/main/java/" + packagePath + "/" + relative);
        assertNotNull(bytes, relative);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    /** A complete-output specification for the unrelated EquipmentLoan contract. */
    private static final class EquipmentLoanFixture {
        private static final List<String> OPERATIONS = List.of("listEquipmentLoans", "openEquipmentLoan",
                "getEquipmentLoan", "reviseEquipmentLoan", "closeEquipmentLoan");
        private static final List<String> BODY = List.of("assetTag", "quantity", "status", "dueOn", "notes", "urgent");
        private final CompleteOutputTestFixture base = new CompleteOutputTestFixture();
        private final PackagedContractRegistry registry;
        private final String version;

        EquipmentLoanFixture(String registryResource) { this(registryResource, "1.0.0"); }

        EquipmentLoanFixture(String registryResource, String version) {
            this.registry = new PackagedContractRegistry(registryResource);
            this.version = version;
        }

        CompleteOutputDescriptor describe() throws Exception {
            var preflight = new GenerationCapabilityPreflight(
                    new GenerationContextBuilder(base.mapper, new MetadataSchemaValidator(), registry), registry);
            return preflight.describe(base.version(snapshot()), specification(), OutputMode.BACKEND);
        }

        GenerationSpecification specification() {
            Map<UUID, String> bindings = new LinkedHashMap<>();
            Map<String, Set<SupportedRole>> roles = new LinkedHashMap<>();
            for (int i = 0; i < OPERATIONS.size(); i++) {
                bindings.put(new UUID(0, i + 1), OPERATIONS.get(i));
                roles.put(OPERATIONS.get(i), Set.of(SupportedRole.ROLE_ADMIN));
            }
            return new GenerationSpecification("1.0.0", "com.example.lending",
                    new EntitySpec("EquipmentLoan", "equipment_loan", List.of(
                            new FieldSpec("assetTag", "asset_tag", "String", false, 20, null),
                            new FieldSpec("quantity", "quantity", "Integer", false, null, null),
                            new FieldSpec("status", "status", "LoanStatus", false, null, "LoanStatus"),
                            new FieldSpec("dueOn", "due_on", "LocalDate", true, null, null),
                            new FieldSpec("notes", "notes", "String", true, 500, null),
                            new FieldSpec("urgent", "urgent", "Boolean", false, null, null)), false),
                    new OpenApiSpec("equipment-loan-api", version, bindings), roles,
                    new AngularSpec("EquipmentLoan", Map.of()));
        }

        ObjectNode snapshot() {
            var snapshot = base.mapper.createObjectNode();
            snapshot.putArray("components");
            var bindings = snapshot.putArray("apiBindings");
            String[] verbs = {"GET", "POST", "GET", "PUT", "DELETE"};
            for (int i = 0; i < verbs.length; i++) {
                ObjectNode binding = bindings.addObject();
                binding.put("id", new UUID(0, i + 1).toString());
                binding.put("httpMethod", verbs[i]);
                binding.put("endpointUrl", "/api/v1/equipment-loans" + (i > 1 ? "/{id}" : ""));
                binding.putObject("headers");
                ObjectNode request = binding.putObject("requestMapping");
                if (i == 0) request.putObject("query").put("status", "status");
                if (i > 1) request.putObject("path").put("id", "id");
                if (i == 1 || i == 3) {
                    ObjectNode body = request.putObject("body");
                    BODY.forEach(field -> body.put(field, field));
                }
                ObjectNode response = binding.putObject("responseMapping");
                if (i == 0) response.put("list", "body.data"); else response.put("item", "body");
            }
            return snapshot;
        }
    }
}
