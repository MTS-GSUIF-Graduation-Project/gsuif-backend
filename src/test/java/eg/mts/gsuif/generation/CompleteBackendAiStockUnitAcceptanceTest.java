package eg.mts.gsuif.generation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import eg.mts.gsuif.generation.GenerationCapabilityPreflight.OutputMode;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;

import static org.junit.jupiter.api.Assertions.*;

/** Opt-in AI acceptance path. The default test uses a labelled mocked provider and never reads a key. */
class CompleteBackendAiStockUnitAcceptanceTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final CompleteBackendPackageAssembler.Provenance MOCK =
            new CompleteBackendPackageAssembler.Provenance("mock-openrouter-transport", "test-1",
                    OpenRouterBackendModelAdapter.MODEL, true);
    @TempDir Path scratch;

    @Test void mockedProviderSourceIsByteBoundThroughAssemblyAndExtraction() throws Exception {
        Selected selected = selected();
        byte[] fixture = mockedSource();
        var adapter = new OpenRouterBackendModelAdapter((payload, key, timeout) -> {
            assertEquals(4096, JSON.readTree(payload).path("max_tokens").asInt());
            assertFalse(JSON.readTree(payload).path("provider").path("allow_fallbacks").asBoolean(true));
            return new OpenRouterBackendModelAdapter.Reply(200, providerResponse(selected.input(), fixture));
        }, (input, source, limit) -> true, System::nanoTime, ignored -> { });
        var accepted = adapter.generate(selected.input(), "disposable-fake-key", OpenRouterBackendModelAdapter.MODEL);
        assertArrayEquals(fixture, accepted.source());
        var assembled = new CompleteBackendPackageAssembler(selected.runtime())
                .assemble(selected.input(), accepted, MOCK);
        Path extracted = extract(assembled, selected.input(), accepted);
        assertArrayEquals(fixture, Files.readAllBytes(extracted.resolve(selected.input().writablePath())));
        var manifest = JSON.readTree(assembled.file("generation-manifest.json"));
        assertTrue(manifest.path("provider").path("mocked").asBoolean());
        assertEquals(OpenRouterBackendModelAdapter.MODEL, manifest.path("provider").path("model").asText());
        assertEquals(selected.input().descriptor().contractResourceSha256(),
                manifest.path("contract").path("sha256").asText());
    }

    @Test void mockedProviderContainerAcceptanceIsExplicitOptIn() throws Exception {
        Assumptions.assumeTrue(Boolean.getBoolean("gsuif.test.ai-mock-containers"),
                "Requires pinned Docker images and isolated offline Maven settings");
        // The container orchestration is intentionally a separate opt-in test; no live OpenRouter transport exists here.
        var selected = selected();
        String image = System.getProperty("gsuif.test.ai-jdk-image");
        String maven = System.getProperty("gsuif.test.maven-command");
        String repository = System.getProperty("maven.repo.local");
        assertNotNull(image); assertNotNull(maven); assertNotNull(repository);
        byte[] fixture = mockedSource();
        var provisioner = new BackendMavenDependencyProvisioner(Path.of(maven), Path.of(repository));
        var dependencies = provisioner.provision(selected.input());
        assertEquals(sha256(selected.input().pom()), dependencies.pomSha256());
        var compiler = new DockerBackendJavaCompiler(image);
        var adapter = new OpenRouterBackendModelAdapter(
                (payload, key, timeout) -> new OpenRouterBackendModelAdapter.Reply(200,
                        providerResponse(selected.input(), fixture)),
                input -> (chosen, source, remaining) -> compiler.compile(chosen, source, dependencies, remaining),
                System::nanoTime, duration -> Thread.sleep(duration.toMillis()));
        var accepted = adapter.generate(selected.input(), "disposable-fake-key", OpenRouterBackendModelAdapter.MODEL);
        var assembled = new CompleteBackendPackageAssembler(selected.runtime())
                .assemble(selected.input(), accepted, MOCK);
        Path extracted = extract(assembled, selected.input(), accepted);
        assertArrayEquals(accepted.source(), Files.readAllBytes(extracted.resolve(selected.input().writablePath())));
        // The next boundary is the disposable offline build/runtime orchestrator; this test must not use a host JVM.
        new IsolatedStockUnitContainerAcceptance(scratch).verify(selected.input(), accepted,
                assembled, extracted, dependencies);
    }

    private record Selected(BackendModelInput input, CompleteBackendRuntimeRenderer runtime) { }

    private static Selected selected() throws Exception {
        var fixture = new CompleteOutputTestFixture();
        var descriptor = fixture.preflight().describe(fixture.version(fixture.snapshot("condition")),
                fixture.specification(), OutputMode.BACKEND);
        var plan = GenerationFilePlan.forDescriptor(descriptor);
        var runtime = new CompleteBackendRuntimeRenderer(new PackagedContractRegistry("/generation/stock-unit-contracts.yaml"));
        return new Selected(BackendModelInput.from(descriptor, plan,
                new CompleteBackendDefinitionRenderer().render(descriptor, plan), runtime), runtime);
    }

    private static byte[] mockedSource() throws Exception {
        try (var resource = CompleteBackendAiStockUnitAcceptanceTest.class
                .getResourceAsStream("/generation/fixtures/StockUnitServiceImpl.java.txt")) {
            assertNotNull(resource);
            return resource.readAllBytes();
        }
    }

    private static byte[] providerResponse(BackendModelInput input, byte[] source) throws IOException {
        ObjectNode files = JSON.createObjectNode();
        files.putArray("files").addObject().put("path", input.writablePath())
                .put("content", new String(source, StandardCharsets.UTF_8));
        ObjectNode response = JSON.createObjectNode();
        response.put("model", OpenRouterBackendModelAdapter.MODEL);
        response.putArray("choices").addObject().put("finish_reason", "stop")
                .putObject("message").put("content", JSON.writeValueAsString(files));
        response.putObject("usage").put("completion_tokens", 100);
        return JSON.writeValueAsBytes(response);
    }

    private Path extract(CompleteBackendPackageAssembler.Assembled assembled, BackendModelInput input,
            BackendModelOutputValidator.Accepted accepted) throws Exception {
        Path root = Files.createDirectory(scratch.resolve("package-" + java.util.UUID.randomUUID()));
        for (var entry : assembled.files().entrySet()) {
            Path destination = root.resolve(entry.getKey()).normalize();
            assertTrue(destination.startsWith(root));
            Files.createDirectories(destination.getParent());
            Files.write(destination, entry.getValue());
        }
        byte[] emitted = Files.readAllBytes(root.resolve(accepted.path()));
        assertArrayEquals(accepted.source(), emitted);
        assertEquals(sha256(accepted.source()), sha256(emitted));
        assertArrayEquals(input.contract(), Files.readAllBytes(root.resolve("contracts/"
                + input.descriptor().contractId() + "-" + input.descriptor().contractVersion() + ".yaml")));
        var manifest = JSON.readTree(assembled.file("generation-manifest.json"));
        boolean found = false;
        for (var file : manifest.path("files")) {
            byte[] bytes = Files.readAllBytes(root.resolve(file.path("path").asText()));
            assertEquals(file.path("sha256").asText(), sha256(bytes));
            if (file.path("path").asText().equals(accepted.path())) {
                assertEquals("MODEL", file.path("owner").asText());
                found = true;
            }
        }
        assertTrue(found);
        return root;
    }

    private static String sha256(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }
}
