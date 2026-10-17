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
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;

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
        Path evidence = preserveAcceptedSource(scratch.resolve("accepted-evidence"), accepted);
        Path saved = evidence.resolve(accepted.path());
        assertArrayEquals(accepted.source(), Files.readAllBytes(saved));
        assertEquals(sha256(accepted.source()), Files.readString(evidence.resolve("accepted-source.sha256")).trim());
        writeFailureAtAttempt(evidence, "ASSEMBLY", new IllegalStateException("disposable-detail-must-not-appear"));
        assertArrayEquals(accepted.source(), Files.readAllBytes(saved));
        assertFalse(Files.readString(evidence.resolve("result.txt")).contains("disposable-detail-must-not-appear"));
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
                assembled, extracted, dependencies, MOCK, null);
    }

    @Test void liveProviderContainerAcceptanceIsExplicitOptIn() throws Exception {
        Assumptions.assumeTrue(Boolean.getBoolean("gsuif.test.ai-live-stockunit"),
                "Live transport requires a separately approved, explicit opt-in");
        // This branch never reads StockUnitServiceImpl.java.txt or constructs a synthetic provider response.
        Map<String, String> environment = System.getenv();
        requireLiveConfiguration(environment);
        String image = requiredImage("gsuif.test.ai-jdk-image");
        String postgres = requiredImage("gsuif.test.ai-postgres-image");
        Path maven = Path.of(requiredProperty("gsuif.test.maven-command"));
        Path repository = Path.of(requiredProperty("maven.repo.local"));
        Path evidenceRoot = Path.of(requiredProperty("gsuif.test.ai-evidence-dir")).toAbsolutePath().normalize();
        if (evidenceRoot.startsWith(scratch.toAbsolutePath().normalize()))
            throw new IllegalStateException("LIVE_EVIDENCE_LOCATION_INVALID");
        String stage = "PREPARE";
        Path attempt = null;
        try {
            Selected selected = selected();
            var provenance = new CompleteBackendPackageAssembler.Provenance(
                    OpenRouterBackendModelAdapter.PROVIDER_ID,
                    OpenRouterBackendModelAdapter.PROVIDER_API_VERSION,
                    OpenRouterBackendModelAdapter.MODEL, false);

            stage = "SANDBOX_PREFLIGHT";
            try (var docker = IsolatedBackendDockerSupport.local()) {
                for (String pinned : List.of(image, postgres)) {
                    var inspected = docker.run(List.of("image", "inspect", "--format", "{{.Id}}", pinned),
                            Duration.ofSeconds(10));
                    if (inspected.exit() != 0 || !pinned.equals(inspected.output().trim()))
                        throw new IllegalStateException("LIVE_IMAGE_UNAVAILABLE");
                }
            }

            stage = "DEPENDENCIES";
            var dependencies = new BackendMavenDependencyProvisioner(maven, repository)
                    .provision(selected.input());
            assertEquals(sha256(selected.input().pom()), dependencies.pomSha256());
            var compiler = new DockerBackendJavaCompiler(image);
            var adapter = new OpenRouterBackendModelAdapter(
                    OpenRouterBackendModelAdapter.httpsTransport(),
                    input -> (chosen, source, remaining) -> compiler.compile(chosen, source, dependencies, remaining),
                    System::nanoTime, duration -> Thread.sleep(duration.toMillis()));

            stage = "PROVIDER_VALIDATE_COMPILE";
            var accepted = adapter.generate(selected.input(), environment.get("OPENROUTER_API_KEY"),
                    environment.get("OPENROUTER_MODEL"));
            stage = "ACCEPTED_SOURCE_EVIDENCE";
            attempt = preserveAcceptedSource(evidenceRoot, accepted);
            stage = "ASSEMBLY";
            var assembled = new CompleteBackendPackageAssembler(selected.runtime())
                    .assemble(selected.input(), accepted, provenance);
            Path extracted = extract(assembled, selected.input(), accepted);
            assertArrayEquals(accepted.source(), assembled.file(accepted.path()));
            assertArrayEquals(accepted.source(), Files.readAllBytes(extracted.resolve(accepted.path())));
            stage = "ISOLATED_BUILD_RUNTIME";
            new IsolatedStockUnitContainerAcceptance(scratch).verify(selected.input(), accepted,
                    assembled, extracted, dependencies, provenance, attempt);
        } catch (Exception | AssertionError failure) {
            if (attempt == null) writeFailure(evidenceRoot, stage, failure);
            else writeFailureAtAttempt(attempt, stage, failure);
            throw failure;
        }
    }

    @Test void liveConfigurationAndFailureEvidenceUseOnlyDisposableValues() throws Exception {
        assertEquals("LIVE_CONFIGURATION_REQUIRED", assertThrows(IllegalStateException.class,
                () -> requireLiveConfiguration(Map.of("OPENROUTER_MODEL", OpenRouterBackendModelAdapter.MODEL)))
                .getMessage());
        assertEquals("LIVE_CONFIGURATION_REQUIRED", assertThrows(IllegalStateException.class,
                () -> requireLiveConfiguration(Map.of("OPENROUTER_API_KEY", "disposable-fake-key",
                        "OPENROUTER_MODEL", "other/model"))).getMessage());
        requireLiveConfiguration(Map.of("OPENROUTER_API_KEY", "disposable-fake-key",
                "OPENROUTER_MODEL", OpenRouterBackendModelAdapter.MODEL));
        Path attempt = writeFailure(scratch.resolve("review"), "PROVIDER_VALIDATE_COMPILE",
                new OpenRouterBackendModelAdapter.Failed(OpenRouterBackendModelAdapter.Failure.HTTP_REJECTED));
        String report = Files.readString(attempt.resolve("result.txt"));
        assertTrue(report.contains("failureCode=HTTP_REJECTED\n"));
        assertTrue(report.contains("mocked=false\n"));
        assertFalse(report.contains("disposable-fake-key"));
        assertFalse(report.contains("providerResponse"));
    }

    @Test void mockedValidatorRejectionRecordsOnlyItsBoundedReason() throws Exception {
        Selected selected = selected();
        var response = (ObjectNode) JSON.readTree(providerResponse(selected.input(), mockedSource()));
        ((ObjectNode) response.path("choices").path(0).path("message")).put("content", "not-json");
        var adapter = new OpenRouterBackendModelAdapter((payload, key, timeout) ->
                new OpenRouterBackendModelAdapter.Reply(200, JSON.writeValueAsBytes(response)),
                (input, source, limit) -> fail("invalid output must not compile"),
                System::nanoTime, ignored -> fail("invalid output must not retry"));
        var failed = assertThrows(OpenRouterBackendModelAdapter.Failed.class,
                () -> adapter.generate(selected.input(), "disposable-fake-key", OpenRouterBackendModelAdapter.MODEL));
        assertEquals(OpenRouterBackendModelAdapter.Failure.INVALID_MODEL_OUTPUT, failed.reason());
        assertEquals(BackendModelOutputValidator.Reason.INVALID_RESPONSE, failed.validatorReason());

        Path attempt = writeFailure(scratch.resolve("validator-evidence"), "PROVIDER_VALIDATE_COMPILE", failed);
        String report = Files.readString(attempt.resolve("result.txt"));
        assertTrue(report.contains("failureCode=INVALID_MODEL_OUTPUT\n"));
        assertTrue(report.contains("validatorReason=INVALID_RESPONSE\n"));
        assertFalse(report.contains("not-json"));
        assertFalse(report.contains("disposable-fake-key"));
        assertFalse(report.contains("providerResponse"));
        Path other = writeFailure(scratch.resolve("http-evidence"), "PROVIDER_VALIDATE_COMPILE",
                new OpenRouterBackendModelAdapter.Failed(OpenRouterBackendModelAdapter.Failure.HTTP_REJECTED));
        assertFalse(Files.readString(other.resolve("result.txt")).contains("validatorReason="));
    }

    private static void requireLiveConfiguration(Map<String, String> environment) {
        if (environment == null || !OpenRouterBackendModelAdapter.MODEL.equals(environment.get("OPENROUTER_MODEL"))
                || environment.get("OPENROUTER_API_KEY") == null
                || environment.get("OPENROUTER_API_KEY").isBlank())
            throw new IllegalStateException("LIVE_CONFIGURATION_REQUIRED");
    }

    private static String requiredProperty(String name) {
        String value = System.getProperty(name);
        if (value == null || value.isBlank()) throw new IllegalStateException("LIVE_CONFIGURATION_REQUIRED");
        return value;
    }

    private static String requiredImage(String name) {
        String image = requiredProperty(name);
        if (!image.matches("sha256:[0-9a-f]{64}")) throw new IllegalStateException("LIVE_IMAGE_REQUIRED");
        return image;
    }

    private static Path writeFailure(Path root, String stage, Throwable failure) throws IOException {
        Path attempt = Files.createDirectories(root).resolve("live-attempt-" + UUID.randomUUID());
        Files.createDirectory(attempt);
        writeFailureAtAttempt(attempt, stage, failure);
        return attempt;
    }

    private static void writeFailureAtAttempt(Path attempt, String stage, Throwable failure) throws IOException {
        String code = failure instanceof OpenRouterBackendModelAdapter.Failed model ? model.reason().name()
                : failure instanceof BackendMavenDependencyProvisioner.Failed dependency ? dependency.reason().name()
                : "ACCEPTANCE_FAILED";
        String validatorReason = failure instanceof OpenRouterBackendModelAdapter.Failed model
                && model.validatorReason() != null ? "validatorReason=" + model.validatorReason().name() + "\n" : "";
        Files.writeString(attempt.resolve("result.txt"), "status=failed\nproviderId="
                + OpenRouterBackendModelAdapter.PROVIDER_ID + "\nproviderVersion="
                + OpenRouterBackendModelAdapter.PROVIDER_API_VERSION + "\nmodelId="
                + OpenRouterBackendModelAdapter.MODEL + "\nmocked=false\nfailureStage="
                + stage + "\nfailureCode=" + code + "\n" + validatorReason, StandardCharsets.UTF_8);
    }

    private static Path preserveAcceptedSource(Path root, BackendModelOutputValidator.Accepted accepted)
            throws Exception {
        Path attempt = Files.createDirectories(root).resolve("live-attempt-" + UUID.randomUUID());
        Files.createDirectory(attempt);
        Path source = attempt.resolve(GenerationArtifactStore.safeRelative(accepted.path())).normalize();
        if (!source.startsWith(attempt)) throw new IllegalStateException("ACCEPTED_SOURCE_PATH_INVALID");
        Files.createDirectories(source.getParent());
        byte[] bytes = accepted.source();
        Files.write(source, bytes, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        String hash = sha256(bytes);
        if (!java.util.Arrays.equals(bytes, Files.readAllBytes(source))
                || !hash.equals(sha256(Files.readAllBytes(source))))
            throw new IllegalStateException("ACCEPTED_SOURCE_EVIDENCE_MISMATCH");
        Files.writeString(attempt.resolve("accepted-source.sha256"), hash + "\n", StandardCharsets.UTF_8,
                StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        return attempt;
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
