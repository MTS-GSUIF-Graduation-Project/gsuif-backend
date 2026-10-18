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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

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
                input -> compilerWithManifest(compiler, dependencies),
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

            stage = "RESPONSE_CAPTURE_SETUP";
            OpenRouterBackendModelAdapter.ModelContentObserver observer = ignored -> { };
            if (Boolean.getBoolean("gsuif.test.ai-capture-response")) {
                Path diagnosticDirectory = Path.of(requiredProperty("gsuif.test.ai-response-dir"));
                var diagnostic = BackendModelResponseDiagnostic.openLive(diagnosticDirectory);
                observer = new OpenRouterBackendModelAdapter.ModelContentObserver() {
                    @Override public void received(byte[] content) { diagnostic.capture(content); }
                    @Override public void normalized(OpenRouterBackendModelAdapter.Normalization code) {
                        diagnostic.recordNormalization(code);
                    }
                };
            }
            var adapter = new OpenRouterBackendModelAdapter(
                    OpenRouterBackendModelAdapter.httpsTransport(),
                    input -> compilerWithManifest(compiler, dependencies),
                    System::nanoTime, duration -> Thread.sleep(duration.toMillis()), observer);

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

    @Test void capturedResponseReplaysThroughAdapterAndUnchangedValidatorWithoutProviderTransport() throws Exception {
        Assumptions.assumeTrue(Boolean.getBoolean("gsuif.test.ai-replay-response"),
                "Offline replay requires an explicitly selected private capture directory");
        byte[] response = BackendModelResponseDiagnostic.readPrivate(
                Path.of(requiredProperty("gsuif.test.ai-response-dir")));
        String expected = requiredProperty("gsuif.test.ai-replay-expected");
        Selected selected = selected();
        Path maven = Path.of(requiredProperty("gsuif.test.maven-command"));
        Path repository = Path.of(requiredProperty("maven.repo.local"));
        String image = requiredImage("gsuif.test.ai-jdk-image");
        var dependencies = new BackendMavenDependencyProvisioner(maven, repository,
                CompleteBackendAiStockUnitAcceptanceTest::runOfflineMaven).provision(selected.input());
        var compiler = new DockerBackendJavaCompiler(image);
        var normalization = new java.util.concurrent.atomic.AtomicReference<OpenRouterBackendModelAdapter.Normalization>();
        var observer = new OpenRouterBackendModelAdapter.ModelContentObserver() {
            @Override public void received(byte[] content) { assertArrayEquals(response, content); }
            @Override public void normalized(OpenRouterBackendModelAdapter.Normalization code) {
                normalization.set(code);
            }
        };
        var adapter = new OpenRouterBackendModelAdapter((payload, key, timeout) ->
                new OpenRouterBackendModelAdapter.Reply(200, providerContent(response)),
                input -> compilerWithManifest(compiler, dependencies),
                System::nanoTime, ignored -> fail("offline replay must not retry"), observer);
        try {
            var accepted = adapter.generate(selected.input(), "disposable-replay-key",
                    OpenRouterBackendModelAdapter.MODEL);
            assertEquals("ACCEPTED", expected);
            assertEquals(selected.input().writablePath(), accepted.path());
        } catch (OpenRouterBackendModelAdapter.Failed rejection) {
            assertEquals(expected, rejection.validatorReason() == null
                    ? rejection.reason().name() : rejection.validatorReason().name());
        }
        assertEquals(OpenRouterBackendModelAdapter.Normalization.TOP_LEVEL_ARRAY_WRAPPED,
                normalization.get());
    }

    @Test void mockedResponseCaptureAndReplayKeepExactBytesAndRejection() throws Exception {
        Selected selected = selected();
        var provider = (ObjectNode) JSON.readTree(providerResponse(selected.input(), mockedSource()));
        ((ObjectNode) provider.path("choices").path(0).path("message")).put("content", "not-json");
        byte[] response = JSON.writeValueAsBytes(provider);
        byte[] content = "not-json".getBytes(StandardCharsets.UTF_8);
        Path directory = scratch.resolve("response-capture");
        var capture = BackendModelResponseDiagnostic.openMock(directory);
        var calls = new AtomicInteger();
        var adapter = new OpenRouterBackendModelAdapter((payload, key, timeout) -> {
            calls.incrementAndGet();
            return new OpenRouterBackendModelAdapter.Reply(200, response);
        }, input -> (selectedInput, source, limit) -> fail("invalid response must not compile"),
                System::nanoTime, ignored -> fail("invalid response must not retry"), capture::capture);
        var initial = assertThrows(OpenRouterBackendModelAdapter.Failed.class,
                () -> adapter.generate(selected.input(), "disposable-fake-key", OpenRouterBackendModelAdapter.MODEL));
        assertEquals(BackendModelOutputValidator.Reason.INVALID_RESPONSE, initial.validatorReason());
        assertEquals(1, calls.get());
        assertArrayEquals(content, BackendModelResponseDiagnostic.readVerified(directory));
        assertEquals(sha256(content), Files.readString(directory.resolve(
                BackendModelResponseDiagnostic.HASH_FILE)).trim());
        try (var saved = Files.list(directory)) { assertEquals(2, saved.count()); }
        var replay = new BackendModelOutputValidator((input, source) ->
                fail("invalid response must not compile"));
        var repeated = assertThrows(BackendModelOutputValidator.Rejected.class,
                () -> replay.validate(selected.input(), BackendModelResponseDiagnostic.readVerified(directory)));
        assertEquals(initial.validatorReason(), repeated.reason());
        Files.writeString(directory.resolve(BackendModelResponseDiagnostic.HASH_FILE), "0".repeat(64));
        assertThrows(BackendModelResponseDiagnostic.Failed.class,
                () -> BackendModelResponseDiagnostic.readVerified(directory));
        assertThrows(BackendModelResponseDiagnostic.Failed.class,
                () -> BackendModelResponseDiagnostic.openLive(Path.of("target", "response-capture")));
    }

    @Test void recordedNormalizationDoesNotChangeCapturedContentOrHash() throws Exception {
        byte[] content = "[{\"path\":\"disposable.java\",\"content\":\"package demo;\"}]"
                .getBytes(StandardCharsets.UTF_8);
        Path directory = scratch.resolve("normalization-capture");
        var diagnostic = BackendModelResponseDiagnostic.openMock(directory);
        diagnostic.capture(content);
        diagnostic.recordNormalization(OpenRouterBackendModelAdapter.Normalization.TOP_LEVEL_ARRAY_WRAPPED);
        assertArrayEquals(content, BackendModelResponseDiagnostic.readVerified(directory));
        assertEquals(sha256(content), Files.readString(directory.resolve(
                BackendModelResponseDiagnostic.HASH_FILE)).trim());
        assertEquals("TOP_LEVEL_ARRAY_WRAPPED", Files.readString(directory.resolve(
                BackendModelResponseDiagnostic.NORMALIZATION_FILE)).trim());
        assertThrows(BackendModelResponseDiagnostic.Failed.class,
                () -> diagnostic.recordNormalization(OpenRouterBackendModelAdapter.Normalization.CANONICAL_FILES_OBJECT));
    }

    private static BackendMavenDependencyProvisioner.Exit runOfflineMaven(
            List<String> command, Path directory, Duration timeout) throws IOException, InterruptedException {
        var offline = new ArrayList<>(command);
        offline.add(1, "-o");
        var builder = new ProcessBuilder(offline).directory(directory.toFile())
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD);
        BackendMavenDependencyProvisioner.removeProviderCredential(builder.environment());
        builder.environment().remove("OPENROUTER_MODEL");
        Process process = builder.start();
        if (!process.waitFor(timeout.toNanos(), TimeUnit.NANOSECONDS)) {
            process.descendants().forEach(child -> child.destroyForcibly());
            process.destroyForcibly();
            process.waitFor();
            return BackendMavenDependencyProvisioner.Exit.TIMEOUT;
        }
        return process.exitValue() == 0 ? BackendMavenDependencyProvisioner.Exit.SUCCESS
                : BackendMavenDependencyProvisioner.Exit.FAILURE;
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
                : failure instanceof BackendModelResponseDiagnostic.Failed ? "RESPONSE_DIAGNOSTIC_FAILED"
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
        return providerContent(JSON.writeValueAsBytes(files));
    }

    private static OpenRouterBackendModelAdapter.IsolatedCompiler compilerWithManifest(
            DockerBackendJavaCompiler compiler, BackendCompileClasspath dependencies) {
        return new OpenRouterBackendModelAdapter.IsolatedCompiler() {
            @Override public boolean compile(BackendModelInput input, byte[] source, Duration remaining) {
                return compiler.compile(input, source, dependencies, remaining);
            }
            @Override public BackendCompileClasspath manifest() { return dependencies; }
        };
    }

    private static byte[] providerContent(byte[] content) throws IOException {
        ObjectNode response = JSON.createObjectNode();
        response.put("model", OpenRouterBackendModelAdapter.MODEL);
        response.putArray("choices").addObject().put("finish_reason", "stop")
                .putObject("message").put("content", new String(content, StandardCharsets.UTF_8));
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
