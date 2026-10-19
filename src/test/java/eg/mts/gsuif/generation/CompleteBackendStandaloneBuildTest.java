package eg.mts.gsuif.generation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import eg.mts.gsuif.generation.GenerationCapabilityPreflight.OutputMode;
import eg.mts.gsuif.generation.GenerationSpecification.*;
import eg.mts.gsuif.validator.MetadataSchemaValidator;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.jar.JarFile;

import static org.junit.jupiter.api.Assertions.*;

/** Explicit opt-in: build the extracted emitted backend without GSUIF sources or fixtures. */
class CompleteBackendStandaloneBuildTest {
    private static final Duration BUILD_LIMIT = Duration.ofMinutes(4);
    private static final CompleteBackendPackageAssembler.Provenance MOCK =
            new CompleteBackendPackageAssembler.Provenance("mock-provider", "test-1", "mock-model", true);
    private final CompleteOutputTestFixture fixture = new CompleteOutputTestFixture();
    private final ObjectMapper json = new ObjectMapper();
    @TempDir Path scratch;

    private record Selected(BackendModelInput input, CompleteBackendPackageAssembler assembler, String source) { }

    @Test void unauditedStockUnitBuildsAsStandaloneExecutableJar() throws Exception {
        requireOptIn();
        var descriptor = fixture.preflight().describe(fixture.version(fixture.snapshot("condition")),
                fixture.specification(), OutputMode.BACKEND);
        Selected selected = selected(descriptor, new PackagedContractRegistry("/generation/stock-unit-contracts.yaml"),
                """
                package com.example.inventory.service;
                @org.springframework.stereotype.Service
                public class StockUnitServiceImpl implements StockUnitService {
                  public com.example.inventory.dto.StockUnitDto create(com.example.inventory.dto.CreateStockUnitRequest request) { return null; }
                  public com.example.inventory.dto.StockUnitDto getById(java.util.UUID id) { return null; }
                  public com.example.inventory.dto.StockUnitPage list(com.example.inventory.dto.StockUnitCondition condition, String label) { return null; }
                  public com.example.inventory.dto.StockUnitDto update(java.util.UUID id, com.example.inventory.dto.UpdateStockUnitRequest request) { return null; }
                  public void delete(java.util.UUID id) { }
                }
                """);
        buildAndInspect("stock-unit-unaudited", selected, false);
    }

    @Test void auditedRenamedGadgetBuildsAsStandaloneExecutableJar() throws Exception {
        requireOptIn();
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
                        new FieldSpec("note", "note", "String", true, 100, null)), true),
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
        var descriptor = preflight.describe(fixture.version(snapshot), specification, OutputMode.BACKEND);
        Selected selected = selected(descriptor, registry, """
                package com.example.fixture.service;
                @org.springframework.stereotype.Service
                public class GadgetServiceImpl implements GadgetService {
                  public com.example.fixture.dto.GadgetDto create(com.example.fixture.dto.CreateGadgetRequest request) { return null; }
                  public com.example.fixture.dto.GadgetDto getById(java.util.UUID id) { return null; }
                  public com.example.fixture.dto.GadgetPage list(com.example.fixture.dto.GadgetStatus status) { return null; }
                  public com.example.fixture.dto.GadgetDto update(java.util.UUID id, com.example.fixture.dto.UpdateGadgetRequest request) { return null; }
                  public void delete(java.util.UUID id) { }
                }
                """);
        buildAndInspect("gadget-audited", selected, true);
    }

    private Selected selected(CompleteOutputDescriptor descriptor, PackagedContractRegistry registry, String source) {
        var plan = GenerationFilePlan.forDescriptor(descriptor);
        var runtime = new CompleteBackendRuntimeRenderer(registry);
        var input = BackendModelInput.from(descriptor, plan,
                new CompleteBackendDefinitionRenderer().render(descriptor, plan), runtime);
        return new Selected(input, new CompleteBackendPackageAssembler(runtime), source);
    }

    private void buildAndInspect(String label, Selected selected, boolean audited) throws Exception {
        var input = selected.input();
        ObjectNode response = json.createObjectNode();
        response.putArray("files").addObject().put("path", input.writablePath())
                .put("content", selected.source());
        // The model transport and compiler are mocked here; the extracted Maven build is real.
        var accepted = new BackendModelOutputValidator((chosen, bytes) -> true)
                .validate(input, json.writeValueAsBytes(response));
        var packageFiles = selected.assembler().assemble(input, accepted, MOCK).files();
        Path extracted = Files.createDirectory(scratch.resolve(label));
        for (var entry : packageFiles.entrySet()) {
            Path destination = extracted.resolve(entry.getKey()).normalize();
            assertTrue(destination.startsWith(extracted));
            Files.createDirectories(destination.getParent());
            Files.write(destination, entry.getValue());
        }
        verifyExtracted(input, extracted, packageFiles.keySet());
        Path backend = extracted.resolve("backend");
        Path log = scratch.resolve(label + "-maven-build.log");
        var command = List.of(Path.of(System.getProperty("gsuif.test.maven-command")).toAbsolutePath().toString(),
                "-B", "-ntp", "-o", "-q", "-f", backend.resolve("pom.xml").toString(),
                "-Dmaven.repo.local=" + Path.of(System.getProperty("maven.repo.local")).toAbsolutePath(),
                "-DskipTests", "package");
        ProcessBuilder builder = new ProcessBuilder(command).directory(backend.toFile())
                .redirectErrorStream(true).redirectOutput(log.toFile());
        builder.environment().keySet().removeIf(key -> key.equalsIgnoreCase("OPENROUTER_API_KEY"));
        assertTrue(builder.environment().keySet().stream().noneMatch(key -> key.equalsIgnoreCase("OPENROUTER_API_KEY")));
        Process process = builder.start();
        boolean completed;
        try {
            completed = process.waitFor(BUILD_LIMIT.toSeconds(), TimeUnit.SECONDS);
        } finally {
            if (process.isAlive()) {
                process.descendants().forEach(ProcessHandle::destroyForcibly);
                process.destroyForcibly();
                process.waitFor(5, TimeUnit.SECONDS);
            }
        }
        assertTrue(completed, label + " Maven package timed out after " + BUILD_LIMIT);
        assertEquals(0, process.exitValue(), label + " Maven package failed: " + safeLog(log));
        Path target = backend.resolve("target");
        List<Path> jars;
        try (var paths = Files.list(target)) {
            jars = paths.filter(path -> path.getFileName().toString().endsWith(".jar")
                    && !path.getFileName().toString().endsWith(".jar.original")).toList();
        }
        assertEquals(1, jars.size(), label + " must produce one executable JAR");
        try (JarFile jar = new JarFile(jars.getFirst().toFile())) {
            String classes = "BOOT-INF/classes/" + input.descriptor().basePackage().replace('.', '/') + "/";
            assertNotNull(jar.getEntry(classes + "Application.class"));
            assertNotNull(jar.getEntry(classes + "service/" + input.descriptor().entityName() + "ServiceImpl.class"));
            assertNotNull(jar.getEntry("BOOT-INF/classes/db/migration/V1__initial_schema.sql"));
            assertNotNull(jar.getEntry("org/springframework/boot/loader/launch/JarLauncher.class"));
            assertTrue(jar.stream().anyMatch(entry -> entry.getName().startsWith("BOOT-INF/lib/spring-boot-")));
            assertTrue(jar.stream().anyMatch(entry -> entry.getName().startsWith("BOOT-INF/lib/postgresql-")));
            assertEquals(audited, jar.stream().anyMatch(entry ->
                    entry.getName().startsWith("BOOT-INF/lib/hibernate-envers-")));
            assertEquals(audited, jar.getEntry(classes + "audit/AppRevision.class") != null);
            assertNull(jar.getEntry("BOOT-INF/classes/eg/mts/gsuif/generation/GenerationResult.class"));
            assertEquals("org.springframework.boot.loader.launch.JarLauncher",
                    jar.getManifest().getMainAttributes().getValue("Main-Class"));
            assertEquals(input.descriptor().basePackage() + ".Application",
                    jar.getManifest().getMainAttributes().getValue("Start-Class"));
        }
    }

    private void verifyExtracted(BackendModelInput input, Path extracted, Set<String> paths) throws Exception {
        var manifest = json.readTree(Files.readAllBytes(extracted.resolve("generation-manifest.json")));
        assertTrue(manifest.path("provider").path("mocked").asBoolean());
        assertEquals("mock-provider", manifest.path("provider").path("id").asText());
        assertEquals("mock-model", manifest.path("provider").path("model").asText());
        assertEquals(input.descriptor().contractId(), manifest.path("contract").path("id").asText());
        assertEquals(input.descriptor().contractVersion(), manifest.path("contract").path("version").asText());
        assertEquals(input.descriptor().contractResourceSha256(),
                manifest.path("contract").path("sha256").asText());
        assertEquals(paths.size() - 1, manifest.path("files").size());
        Set<String> listed = new TreeSet<>();
        for (var item : manifest.path("files")) {
            String path = item.path("path").asText();
            assertTrue(listed.add(path));
            byte[] bytes = Files.readAllBytes(extracted.resolve(path));
            assertEquals(bytes.length, item.path("bytes").asInt());
            assertEquals(sha(bytes), item.path("sha256").asText());
            assertEquals(input.plan().entries().stream().filter(entry -> entry.path().equals(path))
                    .findFirst().orElseThrow().owner().name(), item.path("owner").asText());
        }
        assertFalse(listed.contains("generation-manifest.json"));
        Set<String> expected = new TreeSet<>(paths); expected.remove("generation-manifest.json");
        assertEquals(expected, listed);
        String contractPath = "contracts/" + input.descriptor().contractId() + "-"
                + input.descriptor().contractVersion() + ".yaml";
        assertArrayEquals(input.contract(), Files.readAllBytes(extracted.resolve(contractPath)));
        assertEquals(input.descriptor().contractResourceSha256(), sha(Files.readAllBytes(extracted.resolve(contractPath))));
    }

    private static String sha(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    private static void requireOptIn() {
        String maven = System.getProperty("gsuif.test.maven-command");
        String repository = System.getProperty("maven.repo.local");
        Assumptions.assumeTrue(maven != null && repository != null,
                "Standalone generated build requires explicit Maven command and repository");
        Assumptions.assumeTrue(Runtime.version().feature() == 21, "Standalone generated build requires Java 21");
        assertTrue(Files.isRegularFile(Path.of(maven)));
        assertTrue(Files.isDirectory(Path.of(repository)));
    }

    private static String safeLog(Path log) throws IOException {
        List<String> lines = Files.readAllLines(log, StandardCharsets.UTF_8);
        return lines.stream().filter(line -> line.contains("[ERROR]")).limit(12)
                .map(line -> line.replaceAll("(?i)(password|token|authorization|openrouter_api_key)[=: ]+\\S+", "$1=[redacted]"))
                .map(line -> line.length() > 240 ? line.substring(0, 240) : line)
                .reduce("", (a, b) -> a + "\n" + b);
    }
}
