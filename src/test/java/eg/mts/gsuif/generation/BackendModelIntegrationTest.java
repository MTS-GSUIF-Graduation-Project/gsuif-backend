package eg.mts.gsuif.generation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import eg.mts.gsuif.generation.GenerationCapabilityPreflight.OutputMode;
import eg.mts.gsuif.generation.GenerationSpecification.*;
import eg.mts.gsuif.validator.MetadataSchemaValidator;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.Flow;

import static org.junit.jupiter.api.Assertions.*;

class BackendModelIntegrationTest {
    private final CompleteOutputTestFixture fixture = new CompleteOutputTestFixture();
    private final ObjectMapper json = new ObjectMapper();

    private BackendModelInput input() throws Exception {
        var descriptor = fixture.preflight().describe(fixture.version(fixture.snapshot("condition")),
                fixture.specification(), OutputMode.BACKEND);
        var plan = GenerationFilePlan.forDescriptor(descriptor);
        return BackendModelInput.from(descriptor, plan,
                new CompleteBackendDefinitionRenderer().render(descriptor, plan),
                new CompleteBackendRuntimeRenderer(new PackagedContractRegistry("/generation/stock-unit-contracts.yaml")));
    }

    private BackendModelInput gadgetInput() throws Exception {
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
        var descriptor = preflight.describe(fixture.version(snapshot), specification, OutputMode.BACKEND);
        var plan = GenerationFilePlan.forDescriptor(descriptor);
        return BackendModelInput.from(descriptor, plan,
                new CompleteBackendDefinitionRenderer().render(descriptor, plan),
                new CompleteBackendRuntimeRenderer(registry));
    }

    private byte[] files(String... paths) throws java.io.IOException {
        var root = json.createObjectNode();
        ArrayNode array = root.putArray("files");
        for (String path : paths) array.addObject().put("path", path)
                .put("content", "package com.example.inventory.service; public class StockUnitServiceImpl implements StockUnitService {}");
        return json.writeValueAsBytes(root);
    }

    private byte[] provider(String content) throws java.io.IOException {
        var root = json.createObjectNode();
        root.put("model", OpenRouterBackendModelAdapter.MODEL);
        root.putArray("choices").addObject().put("finish_reason", "stop")
                .putObject("message").put("content", content);
        root.putObject("usage").put("completion_tokens", 100);
        return json.writeValueAsBytes(root);
    }

    @Test void exactContractAndHandoffBytesDriveProjectSpecificPrompt() throws Exception {
        var input = input();
        var adapter = new OpenRouterBackendModelAdapter((body, key, timeout) -> { throw new AssertionError(); },
                (selected, source, time) -> true, System::nanoTime, ignored -> { });
        var payload = json.readTree(adapter.request(input));
        var content = json.readTree(payload.path("messages").path(0).path("content").asText());
        assertEquals(input.descriptor().contractResourceSha256(), content.path("contractSha256").asText());
        assertArrayEquals(input.contract(), content.path("contract").asText().getBytes(StandardCharsets.UTF_8));
        assertEquals(input.writablePath(), content.path("outputPath").asText());
        assertEquals(input.handoff().definitions().size(), content.path("definitions").size());
        for (int i = 0; i < content.path("definitions").size(); i++) {
            var definition = input.handoff().definitions().get(i);
            var item = content.path("definitions").path(i);
            assertArrayEquals(definition.bytes(), item.path("content").asText().getBytes(StandardCharsets.UTF_8));
            assertEquals(definition.sha256(), item.path("sha256").asText());
        }
        assertEquals(4096, payload.path("max_tokens").asInt());
        assertFalse(payload.path("provider").path("allow_fallbacks").asBoolean(true));
        assertEquals(5, content.path("operations").size());
        assertEquals("scanStockUnits", content.path("operations").path(0).path("operationId").asText());
        byte[] mutable = input.contract(); mutable[0] ^= 1;
        assertNotEquals(mutable[0], input.contract()[0]);
    }

    @Test void renamedDomainUsesItsOwnDefinitionsContractAndOperationIds() throws Exception {
        var input = gadgetInput();
        var adapter = new OpenRouterBackendModelAdapter((body, key, timeout) -> { throw new AssertionError(); },
                (selected, source, time) -> true, System::nanoTime, ignored -> { });
        var prompt = json.readTree(json.readTree(adapter.request(input)).path("messages").path(0).path("content").asText());
        assertTrue(input.writablePath().endsWith("/GadgetServiceImpl.java"));
        assertEquals("fetchGadgets", prompt.path("operations").path(0).path("operationId").asText());
        assertEquals("gadget-api", prompt.path("contractId").asText());
        assertTrue(prompt.path("contract").asText().contains("Gadget"));
        assertFalse(prompt.path("contract").asText().contains("StockUnit"));
        assertTrue(prompt.path("entityFields").toString().contains("title"));
        assertTrue(prompt.path("fullUpdateRules").path("note").path("nullable").asBoolean());
        assertEquals(input.handoff().definitions().size(), prompt.path("definitions").size());
    }

    @Test void outputMapRejectsMissingExtraDuplicateUnsafeAndWrongOwnerPaths() throws Exception {
        var input = input();
        var validator = new BackendModelOutputValidator((selected, source) -> true);
        assertEquals(BackendModelOutputValidator.Reason.MISSING_PATH,
                assertThrows(BackendModelOutputValidator.Rejected.class,
                        () -> validator.validate(input, files())).reason());
        for (var caseData : List.of(
                new Object[]{"unplanned.java", BackendModelOutputValidator.Reason.EXTRA_PATH},
                new Object[]{"../unsafe.java", BackendModelOutputValidator.Reason.UNSAFE_PATH},
                new Object[]{input.handoff().definitions().getFirst().path(), BackendModelOutputValidator.Reason.WRONG_OWNER})) {
            assertEquals(caseData[1], assertThrows(BackendModelOutputValidator.Rejected.class,
                    () -> validator.validate(input, files((String) caseData[0]))).reason());
        }
        assertEquals(BackendModelOutputValidator.Reason.DUPLICATE_PATH,
                assertThrows(BackendModelOutputValidator.Rejected.class,
                        () -> validator.validate(input, files(input.writablePath(), input.writablePath().toUpperCase()))).reason());
        assertEquals(BackendModelOutputValidator.Reason.EXTRA_PATH,
                assertThrows(BackendModelOutputValidator.Rejected.class,
                        () -> validator.validate(input, files(input.writablePath(), "other.java"))).reason());
        String duplicateJsonKey = "{\"files\":[{\"path\":\"" + input.writablePath()
                + "\",\"path\":\"" + input.writablePath() + "\",\"content\":\"x\"}]}";
        assertEquals(BackendModelOutputValidator.Reason.INVALID_RESPONSE,
                assertThrows(BackendModelOutputValidator.Rejected.class,
                        () -> validator.validate(input, duplicateJsonKey.getBytes(StandardCharsets.UTF_8))).reason());
        assertEquals(BackendModelOutputValidator.Reason.INVALID_RESPONSE,
                assertThrows(BackendModelOutputValidator.Rejected.class,
                        () -> validator.validate(input, (new String(files(input.writablePath()), StandardCharsets.UTF_8)
                                + " {} ").getBytes(StandardCharsets.UTF_8))).reason());
    }

    @Test void sourceStructureAndCompileOutcomeAreSeparate() throws Exception {
        var input = input();
        var invalid = files(input.writablePath());
        var root = (com.fasterxml.jackson.databind.node.ObjectNode) json.readTree(invalid);
        ((com.fasterxml.jackson.databind.node.ObjectNode) root.path("files").path(0))
                .put("content", "package wrong; public class StockUnitServiceImpl implements StockUnitService {}");
        assertEquals(BackendModelOutputValidator.Reason.INVALID_JAVA_STRUCTURE,
                assertThrows(BackendModelOutputValidator.Rejected.class,
                        () -> new BackendModelOutputValidator((selected, source) -> true)
                                .validate(input, json.writeValueAsBytes(root))).reason());
        assertEquals(BackendModelOutputValidator.Reason.COMPILE_FAILED,
                assertThrows(BackendModelOutputValidator.Rejected.class,
                        () -> new BackendModelOutputValidator((selected, source) -> false)
                                .validate(input, invalid)).reason());
        assertEquals(BackendModelOutputValidator.Reason.COMPILE_FAILED,
                assertThrows(BackendModelOutputValidator.Rejected.class,
                        () -> new BackendModelOutputValidator((selected, source) -> {
                            throw new IllegalStateException("disposable compiler detail");
                        }).validate(input, invalid)).reason());
        var accepted = new BackendModelOutputValidator((selected, source) -> true).validate(input, invalid);
        assertEquals(input.writablePath(), accepted.path());
        byte first = accepted.source()[0]; accepted.source()[0] ^= 1;
        assertEquals(first, accepted.source()[0]);
    }

    @Test void transientResponsesRetryOnceButInvalidOutputDoesNot() throws Exception {
        var input = input();
        for (int transientStatus : List.of(429, 500, 503)) {
            var calls = new AtomicInteger();
            var delays = new ArrayList<Duration>();
            var adapter = new OpenRouterBackendModelAdapter((payload, key, timeout) -> {
                assertTrue(timeout.compareTo(Duration.ofSeconds(120)) <= 0);
                return new OpenRouterBackendModelAdapter.Reply(calls.incrementAndGet() == 1 ? transientStatus : 200,
                        provider(new String(files(input.writablePath()), StandardCharsets.UTF_8)));
            }, (selected, source, time) -> true, System::nanoTime, delays::add);
            assertEquals(input.writablePath(), adapter.generate(input, "fake-key", OpenRouterBackendModelAdapter.MODEL).path());
            assertEquals(2, calls.get());
            assertEquals(List.of(Duration.ofSeconds(2)), delays);
        }
        var calls = new AtomicInteger();
        var invalid = new OpenRouterBackendModelAdapter((payload, key, timeout) -> {
            calls.incrementAndGet(); return new OpenRouterBackendModelAdapter.Reply(200, provider("not-json"));
        }, (selected, source, time) -> true, System::nanoTime, ignored -> fail("no repair"));
        var rejection = assertThrows(OpenRouterBackendModelAdapter.Failed.class,
                () -> invalid.generate(input, "fake-key", OpenRouterBackendModelAdapter.MODEL));
        assertEquals(OpenRouterBackendModelAdapter.Failure.INVALID_MODEL_OUTPUT, rejection.reason());
        assertEquals(BackendModelOutputValidator.Reason.INVALID_RESPONSE, rejection.validatorReason());
        assertEquals(1, calls.get());
    }

    @Test void transportRetryExhaustionHttpRejectionAndDeadlineAreBounded() throws Exception {
        var input = input();
        var calls = new AtomicInteger();
        var retry = new OpenRouterBackendModelAdapter((payload, key, timeout) -> {
            calls.incrementAndGet(); throw new java.io.IOException("disposable secret text");
        }, (selected, source, time) -> true, System::nanoTime, ignored -> { });
        assertEquals(OpenRouterBackendModelAdapter.Failure.RETRY_EXHAUSTED,
                assertThrows(OpenRouterBackendModelAdapter.Failed.class,
                        () -> retry.generate(input, "fake-key", OpenRouterBackendModelAdapter.MODEL)).reason());
        assertEquals(2, calls.get());
        var noRetry = new OpenRouterBackendModelAdapter((payload, key, timeout) -> {
            calls.incrementAndGet(); return new OpenRouterBackendModelAdapter.Reply(400, new byte[0]);
        }, (selected, source, time) -> true, System::nanoTime, ignored -> fail("no retry"));
        assertEquals(OpenRouterBackendModelAdapter.Failure.HTTP_REJECTED,
                assertThrows(OpenRouterBackendModelAdapter.Failed.class,
                        () -> noRetry.generate(input, "fake-key", OpenRouterBackendModelAdapter.MODEL)).reason());
        assertEquals(3, calls.get());
        var clock = new AtomicLong();
        var deadline = new OpenRouterBackendModelAdapter((payload, key, timeout) -> {
            clock.addAndGet(Duration.ofSeconds(251).toNanos());
            return new OpenRouterBackendModelAdapter.Reply(200, provider(new String(files(input.writablePath()), StandardCharsets.UTF_8)));
        }, (selected, source, time) -> true, clock::get, ignored -> { });
        assertEquals(OpenRouterBackendModelAdapter.Failure.DEADLINE,
                assertThrows(OpenRouterBackendModelAdapter.Failed.class,
                        () -> deadline.generate(input, "fake-key", OpenRouterBackendModelAdapter.MODEL)).reason());
    }

    @Test void providerJsonRejectsDuplicateKeysAndTrailingContentWithoutRetry() throws Exception {
        var input = input();
        String valid = new String(provider(new String(files(input.writablePath()), StandardCharsets.UTF_8)),
                StandardCharsets.UTF_8);
        for (String response : List.of(valid + " {}", valid.replaceFirst("\\{", "{\"model\":\"qwen/qwen3-coder\","))) {
            var calls = new AtomicInteger();
            var adapter = new OpenRouterBackendModelAdapter((payload, key, timeout) -> {
                calls.incrementAndGet();
                return new OpenRouterBackendModelAdapter.Reply(200, response.getBytes(StandardCharsets.UTF_8));
            }, (selected, source, time) -> true, System::nanoTime, ignored -> fail("no retry"));
            assertEquals(OpenRouterBackendModelAdapter.Failure.INVALID_PROVIDER_RESPONSE,
                    assertThrows(OpenRouterBackendModelAdapter.Failed.class,
                            () -> adapter.generate(input, "fake-key", OpenRouterBackendModelAdapter.MODEL)).reason());
            assertEquals(1, calls.get());
        }
    }

    @Test void receivingSubscriberCancelsBeforeBufferingOversizedProviderBody() throws Exception {
        var subscriber = new OpenRouterBackendModelAdapter.BoundedBodySubscriber();
        var cancelled = new AtomicBoolean();
        subscriber.onSubscribe(new Flow.Subscription() {
            @Override public void request(long count) { }
            @Override public void cancel() { cancelled.set(true); }
        });
        subscriber.onNext(List.of(ByteBuffer.wrap(new byte[65536])));
        subscriber.onNext(List.of(ByteBuffer.wrap(new byte[1])));
        assertTrue(cancelled.get());
        assertInstanceOf(OpenRouterBackendModelAdapter.ResponseTooLarge.class,
                assertThrows(java.util.concurrent.ExecutionException.class,
                        () -> subscriber.getBody().toCompletableFuture().get()).getCause());
        var input = input();
        var calls = new AtomicInteger();
        var adapter = new OpenRouterBackendModelAdapter((payload, key, timeout) -> {
            calls.incrementAndGet(); throw new OpenRouterBackendModelAdapter.ResponseTooLarge();
        }, (selected, source, time) -> true, System::nanoTime, ignored -> fail("no retry"));
        assertEquals(OpenRouterBackendModelAdapter.Failure.RESPONSE_TOO_LARGE,
                assertThrows(OpenRouterBackendModelAdapter.Failed.class,
                        () -> adapter.generate(input, "fake-key", OpenRouterBackendModelAdapter.MODEL)).reason());
        assertEquals(1, calls.get());
    }

    @Test void localHttpStreamFailsAtReceiveBoundary() throws Exception {
        var server = com.sun.net.httpserver.HttpServer.create(
                new java.net.InetSocketAddress(java.net.InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/oversized", exchange -> {
            try (exchange) {
                exchange.sendResponseHeaders(200, 0);
                try { exchange.getResponseBody().write(new byte[65537]); }
                catch (java.io.IOException ignored) { /* Client cancelled on the byte limit. */ }
            }
        });
        server.start();
        try {
            var request = java.net.http.HttpRequest.newBuilder(java.net.URI.create(
                    "http://127.0.0.1:" + server.getAddress().getPort() + "/oversized")).GET().build();
            var response = java.net.http.HttpClient.newHttpClient().sendAsync(request,
                    info -> new OpenRouterBackendModelAdapter.BoundedBodySubscriber());
            Throwable cause = assertThrows(java.util.concurrent.ExecutionException.class,
                    () -> response.get(10, java.util.concurrent.TimeUnit.SECONDS)).getCause();
            while (cause instanceof java.util.concurrent.CompletionException && cause.getCause() != null)
                cause = cause.getCause();
            assertInstanceOf(OpenRouterBackendModelAdapter.ResponseTooLarge.class, cause);
        } finally { server.stop(0); }
    }

    @Test
    @org.junit.jupiter.api.condition.EnabledOnOs(org.junit.jupiter.api.condition.OS.LINUX)
    void posixStagingGrantsContainerReadOnlyDisposableInputsAndKeepsDockerConfigPrivate(
            @org.junit.jupiter.api.io.TempDir Path scratch) throws Exception {
        Path stage = Files.createDirectory(scratch.resolve("stage"));
        Path source = Files.createDirectories(stage.resolve("sources/Source0"));
        Path file = Files.writeString(source.resolve("Example.java"), "class Example {}");
        Path config = Files.createDirectories(stage.resolve("docker-config"));
        PosixCompileStaging.makeContainerReadable(stage);
        assertEquals(PosixFilePermissions.fromString("rwx--x--x"), Files.getPosixFilePermissions(stage));
        assertEquals(PosixFilePermissions.fromString("rwxr-xr-x"), Files.getPosixFilePermissions(source));
        assertEquals(PosixFilePermissions.fromString("rw-r--r--"), Files.getPosixFilePermissions(file));
        assertFalse(Files.getPosixFilePermissions(config).contains(java.nio.file.attribute.PosixFilePermission.OTHERS_READ));
        assertFalse(Files.getPosixFilePermissions(config).contains(java.nio.file.attribute.PosixFilePermission.OTHERS_EXECUTE));
    }

    @Test void dockerCompilationCommandIsOfflineReadOnlyAndCredentialFree() {
        var command = DockerBackendJavaCompiler.command("sha256:" + "a".repeat(64),
                "disposable-container", Path.of("C:/disposable"), "javac -proc:none");
        assertTrue(command.contains("--network=none"));
        assertTrue(command.contains("--read-only"));
        assertTrue(command.contains("--user=65532:65532"));
        assertTrue(command.stream().anyMatch(arg -> arg.endsWith(",dst=/input,readonly")));
        assertTrue(command.contains("javac -proc:none"));
        assertFalse(String.join(" ", command).contains("OPENROUTER_API_KEY"));
    }

    @Test void trustedPomSelectsConditionalEnversAndLoadsWithoutCheckout(@org.junit.jupiter.api.io.TempDir Path scratch)
            throws Exception {
        BackendModelInput plain = input();
        assertFalse(new String(plain.pom(), StandardCharsets.UTF_8).contains("hibernate-envers"));
        var auditedDescriptor = fixture.auditedSchemaDescriptor(plain.descriptor(), "stock_unit");
        var auditedPlan = GenerationFilePlan.forDescriptor(auditedDescriptor);
        var runtime = new CompleteBackendRuntimeRenderer(
                new PackagedContractRegistry("/generation/stock-unit-contracts.yaml"));
        BackendModelInput audited = BackendModelInput.from(auditedDescriptor, auditedPlan,
                new CompleteBackendDefinitionRenderer().render(auditedDescriptor, auditedPlan), runtime);
        assertTrue(new String(audited.pom(), StandardCharsets.UTF_8).contains("hibernate-envers"));
        String original = System.getProperty("user.dir");
        try {
            System.setProperty("user.dir", scratch.toString());
            assertArrayEquals(plain.pom(), input().pom());
        } finally { System.setProperty("user.dir", original); }
    }

    @Test void provisionerBindsExactPomCoordinatesAndCopiedJarHashes(@org.junit.jupiter.api.io.TempDir Path scratch)
            throws Exception {
        BackendModelInput input = input();
        Path repository = Files.createDirectory(scratch.resolve("repository"));
        Path jar = repository.resolve("org/example/compile-api/1.2.3/compile-api-1.2.3.jar");
        Files.createDirectories(jar.getParent());
        Files.writeString(jar, "disposable jar bytes");
        Path executable = Files.writeString(scratch.resolve("mvn.cmd"), "disposable");
        var provisioner = new BackendMavenDependencyProvisioner(executable, repository, (command, directory, timeout) -> {
            assertTrue(command.contains(BackendMavenDependencyProvisioner.DEPENDENCY_PLUGIN));
            assertTrue(command.contains("-DincludeScope=compile"));
            assertTrue(!timeout.isNegative() && !timeout.isZero()
                    && timeout.compareTo(BackendMavenDependencyProvisioner.TIMEOUT) <= 0);
            assertArrayEquals(input.pom(), Files.readAllBytes(directory.resolve("pom.xml")));
            Files.writeString(directory.resolve("classpath.txt"), jar.toString());
            return BackendMavenDependencyProvisioner.Exit.SUCCESS;
        });
        var manifest = provisioner.provision(input);
        assertEquals(BackendCompileClasspath.hash(input.pom()), manifest.pomSha256());
        assertEquals("org.example:compile-api:1.2.3", manifest.artifacts().getFirst().coordinates());
        assertEquals(BackendCompileClasspath.hash(jar), manifest.artifacts().getFirst().sha256());
        Path staged = Files.createDirectory(scratch.resolve("staged"));
        assertEquals(List.of("/input/lib/lib0.jar"), manifest.stage(input.pom(), staged));
        assertArrayEquals(Files.readAllBytes(jar), Files.readAllBytes(staged.resolve("lib0.jar")));
        byte[] changedPom = input.pom(); changedPom[0] ^= 1;
        assertEquals(BackendCompileClasspath.Reason.INVALID_MANIFEST,
                assertThrows(BackendCompileClasspath.Failed.class,
                        () -> manifest.stage(changedPom, scratch.resolve("wrong-pom"))).reason());
        Files.writeString(jar, "tampered jar bytes");
        assertEquals(BackendCompileClasspath.Reason.CHANGED_ARTIFACT,
                assertThrows(BackendCompileClasspath.Failed.class,
                        () -> manifest.stage(input.pom(), scratch.resolve("tampered"))).reason());
        Files.delete(jar);
        assertEquals(BackendCompileClasspath.Reason.MISSING_ARTIFACT,
                assertThrows(BackendCompileClasspath.Failed.class,
                        () -> manifest.stage(input.pom(), scratch.resolve("missing"))).reason());
    }

    @Test void missingDependencyAndTimeoutFailBeforeAnyProviderRequest(@org.junit.jupiter.api.io.TempDir Path scratch)
            throws Exception {
        BackendModelInput input = input();
        Path repository = Files.createDirectory(scratch.resolve("repository"));
        Path executable = Files.writeString(scratch.resolve("mvn.cmd"), "disposable");
        var absent = new BackendMavenDependencyProvisioner(executable, repository, (command, directory, timeout) -> {
            Files.writeString(directory.resolve("classpath.txt"),
                    repository.resolve("org/example/missing/1.0/missing-1.0.jar").toString());
            return BackendMavenDependencyProvisioner.Exit.SUCCESS;
        });
        assertEquals(BackendMavenDependencyProvisioner.Reason.MISSING_ARTIFACT,
                assertThrows(BackendMavenDependencyProvisioner.Failed.class,
                        () -> absent.provision(input)).reason());
        var timedOut = new BackendMavenDependencyProvisioner(executable, repository,
                (command, directory, timeout) -> BackendMavenDependencyProvisioner.Exit.TIMEOUT);
        assertEquals(BackendMavenDependencyProvisioner.Reason.TIMEOUT,
                assertThrows(BackendMavenDependencyProvisioner.Failed.class,
                        () -> timedOut.provision(input)).reason());
        AtomicInteger requests = new AtomicInteger();
        var adapter = new OpenRouterBackendModelAdapter((body, key, timeout) -> {
            requests.incrementAndGet(); throw new AssertionError("Provider must not be called");
        }, (OpenRouterBackendModelAdapter.CompilerFactory) selected -> {
            absent.provision(selected);
            throw new AssertionError("Missing dependency must fail during preparation");
        }, System::nanoTime, ignored -> { });
        assertEquals(OpenRouterBackendModelAdapter.Failure.DEPENDENCY_UNAVAILABLE,
                assertThrows(OpenRouterBackendModelAdapter.Failed.class,
                        () -> adapter.generate(input, "disposable-key", OpenRouterBackendModelAdapter.MODEL)).reason());
        assertEquals(0, requests.get());
    }

    @Test void trustedMavenChildDropsDisposableProviderCredential() {
        Map<String, String> environment = new java.util.HashMap<>();
        environment.put("OpenRouter_Api_Key", "disposable-fake-key");
        environment.put("MAVEN_REPOSITORY_TOKEN", "disposable-repository-value");
        BackendMavenDependencyProvisioner.removeProviderCredential(environment);
        assertFalse(environment.keySet().stream().anyMatch(key -> key.equalsIgnoreCase("OPENROUTER_API_KEY")));
        assertEquals("disposable-repository-value", environment.get("MAVEN_REPOSITORY_TOKEN"));
    }

    @Test void pinnedMavenSelectsAuditedAndUnauditedCompileDependencies() throws Exception {
        String maven = System.getProperty("gsuif.test.maven-command");
        String repository = System.getProperty("maven.repo.local");
        org.junit.jupiter.api.Assumptions.assumeTrue(maven != null && repository != null,
                "Generated-POM Maven provisioning is an explicit opt-in");
        var input = input();
        var provisioner = new BackendMavenDependencyProvisioner(Path.of(maven), Path.of(repository));
        var plain = provisioner.provision(input);
        assertTrue(plain.artifacts().stream().noneMatch(artifact ->
                artifact.coordinates().startsWith("org.postgresql:postgresql:")));
        assertTrue(plain.artifacts().stream().noneMatch(artifact ->
                artifact.coordinates().contains("hibernate-envers")));
        for (String required : List.of("spring-boot-starter-webmvc", "spring-boot-starter-data-jpa",
                "spring-security-oauth2-jose", "springdoc-openapi-starter-webmvc-ui"))
            assertTrue(plain.artifacts().stream().anyMatch(artifact ->
                    artifact.artifactId().equals(required)), "Missing compile dependency: " + required);
        var auditedDescriptor = fixture.auditedSchemaDescriptor(input.descriptor(), "stock_unit");
        var auditedPlan = GenerationFilePlan.forDescriptor(auditedDescriptor);
        var auditedInput = BackendModelInput.from(auditedDescriptor, auditedPlan,
                new CompleteBackendDefinitionRenderer().render(auditedDescriptor, auditedPlan),
                new CompleteBackendRuntimeRenderer(new PackagedContractRegistry("/generation/stock-unit-contracts.yaml")));
        var audited = provisioner.provision(auditedInput);
        assertTrue(audited.artifacts().stream().noneMatch(artifact ->
                artifact.coordinates().startsWith("org.postgresql:postgresql:")));
        assertTrue(audited.artifacts().stream().anyMatch(artifact ->
                artifact.coordinates().equals("org.hibernate.orm:hibernate-envers:7.2.24.Final")));
        for (String required : List.of("spring-boot-starter-webmvc", "spring-boot-starter-data-jpa",
                "spring-security-oauth2-jose", "springdoc-openapi-starter-webmvc-ui"))
            assertTrue(audited.artifacts().stream().anyMatch(artifact ->
                    artifact.artifactId().equals(required)), "Missing compile dependency: " + required);
        assertNotEquals(plain.pomSha256(), audited.pomSha256());
    }

    @Test void pinnedSandboxCompilesDefinitionsWithoutExecutingModelCode() throws Exception {
        String image = System.getProperty("gsuif.test.jdk-image");
        org.junit.jupiter.api.Assumptions.assumeTrue(image != null && !image.isBlank(),
                "Pinned Docker image is an explicit opt-in");
        String maven = System.getProperty("gsuif.test.maven-command");
        String repository = System.getProperty("maven.repo.local");
        org.junit.jupiter.api.Assumptions.assumeTrue(maven != null && repository != null,
                "Generated-POM Maven provisioning is an explicit opt-in");
        var input = input();
        String source = """
                package com.example.inventory.service;
                public class StockUnitServiceImpl implements StockUnitService {
                  public com.example.inventory.dto.StockUnitDto create(com.example.inventory.dto.CreateStockUnitRequest request) { return null; }
                  public com.example.inventory.dto.StockUnitDto getById(java.util.UUID id) { return null; }
                  public com.example.inventory.dto.StockUnitPage list(com.example.inventory.dto.StockUnitCondition condition, String label) { return null; }
                  public com.example.inventory.dto.StockUnitDto update(java.util.UUID id, com.example.inventory.dto.UpdateStockUnitRequest request) { return null; }
                  public void delete(java.util.UUID id) { }
                }
                """;
        var dependencies = new BackendMavenDependencyProvisioner(Path.of(maven), Path.of(repository)).provision(input);
        assertTrue(new DockerBackendJavaCompiler(image).compile(input,
                source.getBytes(StandardCharsets.UTF_8), dependencies, Duration.ofSeconds(90)),
                "Emitted definitions and model-owned service must compile in the isolated JDK");
        String hostOnly = source.replace("public class StockUnitServiceImpl implements StockUnitService {",
                "public class StockUnitServiceImpl implements StockUnitService {\n"
                        + "  private eg.mts.gsuif.generation.GenerationResult hostOnly;\n");
        String testOnly = source.replace("public class StockUnitServiceImpl implements StockUnitService {",
                "public class StockUnitServiceImpl implements StockUnitService {\n"
                        + "  private org.junit.jupiter.api.Assertions testOnly;\n");
        assertFalse(new DockerBackendJavaCompiler(image).compile(input,
                hostOnly.getBytes(StandardCharsets.UTF_8), dependencies, Duration.ofSeconds(90)));
        assertFalse(new DockerBackendJavaCompiler(image).compile(input,
                testOnly.getBytes(StandardCharsets.UTF_8), dependencies, Duration.ofSeconds(90)));
    }
}
