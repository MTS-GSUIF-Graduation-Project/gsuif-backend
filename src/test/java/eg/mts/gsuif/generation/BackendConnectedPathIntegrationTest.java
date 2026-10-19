package eg.mts.gsuif.generation;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import eg.mts.gsuif.dto.GenerationApiDtos;
import eg.mts.gsuif.entity.*;
import eg.mts.gsuif.generation.GenerationSpecification.*;
import eg.mts.gsuif.repository.*;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.SecureRandom;
import java.time.Duration;
import java.sql.DriverManager;
import java.util.Base64;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipFile;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** H2-connected public path with a deliberately mocked model/compiler and final build. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class BackendConnectedPathIntegrationTest {
    private static final Path STORE = Path.of("target", "backend-connected-" + UUID.randomUUID());
    private static final Path EXPORTS = Path.of("target", "backend-connected-exports-" + UUID.randomUUID());
    private static final CompleteBackendPackageAssembler.Provenance MOCK =
            new CompleteBackendPackageAssembler.Provenance("openrouter", "api-v1", "qwen/qwen3-coder", true);
    private final ObjectMapper json = new ObjectMapper();
    @Autowired MockMvc mvc;
    @Autowired EntityManager entities;
    @Autowired PlatformTransactionManager transactions;
    @Autowired GsuifPermissionRepository permissions;
    @Autowired GenerationRunRepository runRepository;
    @Autowired GeneratedArtifactRepository artifactRepository;
    @Autowired GenerationArtifactStore store;
    @Autowired BackendGenerationService backend;
    @MockitoSpyBean GenerationRunService runs;
    @MockitoBean BackendModelGateway model;
    @MockitoBean BackendFinalBuild finalBuild;
    @TempDir Path scratch;
    private Fixture fixture;

    @DynamicPropertySource static void paths(DynamicPropertyRegistry registry) {
        registry.add("gsuif.generation.artifact-root", () -> STORE.toString());
        registry.add("gsuif.generation.export-root", () -> EXPORTS.toString());
    }

    @AfterEach void cleanup() {
        SecurityContextHolder.clearContext();
        if (fixture == null) return;
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            entities.createQuery("delete from GeneratedArtifact a where a.generationRun.metadataVersion.id = :version")
                    .setParameter("version", fixture.versionId()).executeUpdate();
            entities.createQuery("delete from GenerationRun r where r.metadataVersion.id = :version")
                    .setParameter("version", fixture.versionId()).executeUpdate();
            entities.createQuery("delete from GsuifRolePermission p where p.role.id = :role")
                    .setParameter("role", fixture.roleId()).executeUpdate();
            entities.createQuery("delete from GsuifUserRole u where u.role.id = :role")
                    .setParameter("role", fixture.roleId()).executeUpdate();
            var page = entities.find(GsuifPage.class, fixture.pageId());
            page.setCurrentMetadataVersionId(null);
            entities.flush();
            entities.remove(entities.getReference(MetadataVersion.class, fixture.versionId()));
            entities.remove(page);
            entities.remove(entities.getReference(GsuifProject.class, fixture.projectId()));
            entities.remove(entities.getReference(GsuifUser.class, fixture.userId()));
            entities.remove(entities.getReference(GsuifRole.class, fixture.roleId()));
        });
    }

    @Test void publicBackendRunReplaysAndExportsCommittedBytes() throws Exception {
        prepare();
        UUID attempt = UUID.randomUUID();
        byte[] request = request(attempt);
        String body = mvc.perform(post("/api/v1/generation/backend").with(authentication(fixture.auth()))
                        .contentType("application/json").content(request))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.body.status").value("SUCCESS"))
                .andReturn().getResponse().getContentAsString();
        UUID runId = UUID.fromString(json.readTree(body).path("body").path("runId").asText());
        assertEquals("asset-ticket-api", json.readTree(body).path("body").path("backendPackage")
                .path("contractId").asText());
        assertEquals("2.0.0", json.readTree(body).path("body").path("backendPackage")
                .path("contractVersion").asText());
        assertEquals("MODEL_OUTPUT_STRUCTURE", json.readTree(body).path("body")
                .path("backendPackage").path("validationScope").path("passedTargets").get(0).asText());
        assertEquals("REAL_PROVIDER_RESPONSE", json.readTree(body).path("body")
                .path("backendPackage").path("validationScope").path("notValidatedTargets").get(0).asText());
        var run = runRepository.findById(runId).orElseThrow();
        assertEquals("openrouter", run.getToolName());
        assertEquals("api-v1|qwen/qwen3-coder", run.getToolVersion());
        assertNotNull(run.getPackageManifestSha256());
        assertEquals(run.getPackageManifestSha256(), json.readTree(body).path("body")
                .path("backendPackage").path("manifestSha256").asText());
        String details = mvc.perform(get("/api/v1/generation/runs/{id}", runId)
                        .with(authentication(fixture.auth())))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertEquals("asset-ticket-api", json.readTree(details).path("body")
                .path("backendPackage").path("contractId").asText());
        assertEquals(run.getPackageManifestSha256(), json.readTree(details).path("body")
                .path("backendPackage").path("manifestSha256").asText());
        assertFalse(artifactRepository.findAllByGenerationRunIdOrderByRelativePathAsc(runId).isEmpty());
        byte[] manifest = mvc.perform(get("/api/v1/generation/runs/{id}/artifacts/download", runId)
                        .param("path", "generation-manifest.json").with(authentication(fixture.auth())))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        assertEquals(run.getPackageManifestSha256(), BackendPackageIntegrity.hash(manifest));
        assertTrue(json.readTree(manifest).path("provider").path("mocked").asBoolean());
        byte[] selectedContract = mvc.perform(get("/api/v1/generation/runs/{id}/artifacts/download", runId)
                        .param("path", "contracts/asset-ticket-api-2.0.0.yaml")
                        .with(authentication(fixture.auth())))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        try (var resource = getClass().getResourceAsStream("/openapi/asset-ticket-api-2.yaml")) {
            assertArrayEquals(resource.readAllBytes(), selectedContract);
        }
        // No destination has been configured: the new path reads the committed package directly.
        var pendingZip = mvc.perform(get("/api/v1/generation/runs/{id}/package.zip", runId)
                        .with(authentication(fixture.auth())))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.request().asyncStarted())
                .andReturn();
        byte[] directZip = mvc.perform(asyncDispatch(pendingZip))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "application/zip"))
                .andExpect(header().string("Content-Disposition",
                        "attachment; filename=\"" + runId + ".zip\""))
                .andReturn().getResponse().getContentAsByteArray();
        Map<String, byte[]> extracted = new LinkedHashMap<>();
        try (var zip = new java.util.zip.ZipInputStream(new java.io.ByteArrayInputStream(directZip))) {
            java.util.zip.ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                assertNull(extracted.putIfAbsent(entry.getName(), zip.readAllBytes()));
            }
        }
        assertArrayEquals(manifest, extracted.get("generation-manifest.json"));
        assertArrayEquals(selectedContract, extracted.get("contracts/asset-ticket-api-2.0.0.yaml"));
        assertEquals(artifactRepository.findAllByGenerationRunIdOrderByRelativePathAsc(runId).size(),
                extracted.size());
        mvc.perform(post("/api/v1/generation/backend").with(authentication(fixture.auth()))
                        .contentType("application/json").content(request))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.body.runId").value(runId.toString()));
        verify(model, times(1)).generate(any());

        mvc.perform(put("/api/v1/generation/projects/{id}/destinations/connected", fixture.projectId())
                        .with(authentication(fixture.auth())).contentType("application/json")
                        .content("{\"type\":\"ZIP\",\"path\":\"review\"}"))
                .andExpect(status().isOk());
        String exported = mvc.perform(post("/api/v1/generation/runs/{id}/exports/connected", runId)
                        .with(authentication(fixture.auth())))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        Path zip = Path.of(java.net.URI.create(json.readTree(exported).path("body").path("location").asText()));
        try (ZipFile archive = new ZipFile(zip.toFile())) {
            assertArrayEquals(manifest, archive.getInputStream(archive.getEntry("generation-manifest.json")).readAllBytes());
            assertArrayEquals(selectedContract,
                    archive.getInputStream(archive.getEntry("contracts/asset-ticket-api-2.0.0.yaml")).readAllBytes());
        }
    }

    @Test void changedInputAndTamperedArtifactCannotReplayOrDownload() throws Exception {
        prepare();
        UUID attempt = UUID.randomUUID();
        UUID runId = create(attempt);
        var changed = specification();
        changed = new GenerationSpecification(changed.specVersion(), changed.basePackage(), changed.entity(),
                changed.openApi(), Map.of("listAssetTickets", Set.of(SupportedRole.ROLE_USER)), changed.angular());
        mvc.perform(post("/api/v1/generation/backend").with(authentication(fixture.auth()))
                        .contentType("application/json")
                        .content(json.writeValueAsBytes(new GenerationApiDtos.BackendRequest(fixture.pageId(), changed, attempt))))
                .andExpect(status().isBadRequest());
        verify(model, times(1)).generate(any());
        Path stored = STORE.resolve(runId.toString()).resolve("generation-manifest.json");
        byte[] original = Files.readAllBytes(stored);
        try {
            Files.writeString(stored, "{}", StandardCharsets.UTF_8);
            mvc.perform(get("/api/v1/generation/runs/{id}/package.zip", runId)
                            .with(authentication(fixture.auth())))
                    .andExpect(status().is5xxServerError())
                    .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("PK"))));
            mvc.perform(get("/api/v1/generation/runs/{id}/artifacts/download", runId)
                            .param("path", "generation-manifest.json").with(authentication(fixture.auth())))
                    .andExpect(status().is5xxServerError());
            assertThrows(RuntimeException.class, () -> new GenerationRunService(runRepository,
                    artifactRepository, new GenerationArtifactStore(STORE)).readResult(runRepository.findById(runId).orElseThrow()));
        } finally { Files.write(stored, original); }
    }

    @Test void missingArtifactBlocksDownloadAndExportBeforeWritingDestination() throws Exception {
        prepare();
        UUID runId = create(UUID.randomUUID());
        Path stored = STORE.resolve(runId.toString()).resolve("generation-manifest.json");
        byte[] original = Files.readAllBytes(stored);
        try {
            Files.delete(stored);
            mvc.perform(get("/api/v1/generation/runs/{id}/package.zip", runId)
                            .with(authentication(fixture.auth())))
                    .andExpect(status().is5xxServerError());
            mvc.perform(get("/api/v1/generation/runs/{id}/artifacts/download", runId)
                            .param("path", "README.md").with(authentication(fixture.auth())))
                    .andExpect(status().is5xxServerError());
            mvc.perform(put("/api/v1/generation/projects/{id}/destinations/missing", fixture.projectId())
                            .with(authentication(fixture.auth())).contentType("application/json")
                            .content("{\"type\":\"ZIP\",\"path\":\"review\"}"))
                    .andExpect(status().isOk());
            mvc.perform(post("/api/v1/generation/runs/{id}/exports/missing", runId)
                            .with(authentication(fixture.auth())))
                    .andExpect(status().is5xxServerError());
            assertFalse(Files.exists(EXPORTS.resolve(fixture.projectId().toString()).resolve("review")
                    .resolve(runId + ".zip")));
        } finally { Files.write(stored, original); }
    }

    @Test void packageBuildFailureIsLoggedWithItsReasonAndRunIdOnly() throws Exception {
        prepare();
        UUID attempt = UUID.randomUUID();
        doThrow(new BackendFinalPackageValidator.Failed(BackendFinalPackageValidator.Failure.TIMEOUT))
                .when(finalBuild).validate(any());
        var events = captureDiagnostics();
        try {
            mvc.perform(post("/api/v1/generation/backend").with(authentication(fixture.auth()))
                            .contentType("application/json").content(request(attempt)))
                    .andExpect(status().is5xxServerError())
                    .andExpect(header().string("X-Generation-Failure-Stage", "FINAL_VALIDATION"))
                    .andExpect(header().string("X-Generation-Failure-Code", "RESULT_VALIDATION_FAILED"));
            var run = runRepository.findByAttemptId(attempt).orElseThrow();
            assertEquals("RESULT_VALIDATION_FAILED", run.getFailureCode());
            assertEquals(1, events.list.size());
            String logged = events.list.get(0).getFormattedMessage();
            assertTrue(logged.contains("runId=" + run.getId()), logged);
            assertTrue(logged.contains("attemptId=" + attempt), logged);
            assertTrue(logged.contains("reason=TIMEOUT"), logged);
        } finally { detachDiagnostics(events); }
    }

    @Test void publicationCollisionRecordsFailureWithoutRegistryOrSuccess() throws Exception {
        prepare();
        UUID attempt = UUID.randomUUID();
        var events = captureDiagnostics();
        doAnswer(invocation -> {
            UUID runId = runRepository.findByAttemptId(attempt).orElseThrow().getId();
            Files.createDirectories(STORE.resolve(runId.toString()));
            return accepted(invocation.getArgument(0));
        }).when(model).generate(any());
        try {
            mvc.perform(post("/api/v1/generation/backend").with(authentication(fixture.auth()))
                            .contentType("application/json").content(request(attempt)))
                    .andExpect(status().is5xxServerError());
            var run = runRepository.findByAttemptId(attempt).orElseThrow();
            assertEquals(GenerationRunStatus.FAILED, run.getStatus());
            assertEquals("FINAL_VALIDATION", run.getFailureStage());
            assertEquals(0, artifactRepository.findAllByGenerationRunIdOrderByRelativePathAsc(run.getId()).size());
            assertEquals(1, events.list.size());
            String logged = events.list.get(0).getFormattedMessage();
            assertTrue(logged.contains("runId=" + run.getId()) && logged.contains("reason=PERSISTENCE_COMPLETION"), logged);
        } finally {
            detachDiagnostics(events);
            Path collision = runRepository.findByAttemptId(attempt)
                    .map(run -> STORE.resolve(run.getId().toString())).orElse(null);
            if (collision != null) Files.deleteIfExists(collision);
        }
    }

    @Test void commitFailureRemovesPublishedFiles() {
        UUID runId = UUID.randomUUID();
        byte[] bytes = "test-only".getBytes(StandardCharsets.UTF_8);
        var result = new GenerationResult(List.of(new GenerationResult.Artifact("README.md", bytes,
                BackendPackageIntegrity.hash(bytes), "test", "test")), List.of());
        var staged = store.stage(runId, "test-fingerprint", result);
        assertThrows(RuntimeException.class, () -> new TransactionTemplate(transactions).executeWithoutResult(status -> {
            store.publish(staged);
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void beforeCommit(boolean readOnly) { throw new IllegalStateException("test commit failure"); }
            });
        }));
        assertFalse(Files.exists(STORE.resolve(runId.toString())));
        assertFalse(Files.exists(staged.directory()));
    }

    @Test void concurrentSameAttemptRunsProviderOnceAndRequiresFreshIdForPendingAttempt() throws Exception {
        prepare();
        UUID attempt = UUID.randomUUID();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        doAnswer(invocation -> {
            entered.countDown();
            if (!release.await(20, TimeUnit.SECONDS)) throw new IllegalStateException("test timeout");
            return accepted(invocation.getArgument(0));
        }).when(model).generate(any());
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var first = executor.submit(() -> {
                SecurityContextHolder.getContext().setAuthentication(fixture.auth());
                try { return backend.generate(new GenerationApiDtos.BackendRequest(
                        fixture.pageId(), specification(), attempt), fixture.auth().getName()); }
                finally { SecurityContextHolder.clearContext(); }
            });
            assertTrue(entered.await(20, TimeUnit.SECONDS));
            SecurityContextHolder.getContext().setAuthentication(fixture.auth());
            assertThrows(GenerationValidationException.class, () -> backend.generate(
                    new GenerationApiDtos.BackendRequest(fixture.pageId(), specification(), attempt),
                    fixture.auth().getName()));
            release.countDown();
            assertEquals("SUCCESS", first.get(20, TimeUnit.SECONDS).status());
        } finally { release.countDown(); }
        verify(model, times(1)).generate(any());
    }

    @Test void missingPersistedPageGrantRejectsBeforeReservation() throws Exception {
        prepare();
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            var permission = permissions.findByCode("ENTITY_ACCESS:GsuifPage").orElseThrow();
            entities.createQuery("delete from GsuifRolePermission p where p.role.id = :role and p.permission.id = :permission")
                    .setParameter("role", fixture.roleId()).setParameter("permission", permission.getId())
                    .executeUpdate();
        });
        UUID attempt = UUID.randomUUID();
        mvc.perform(post("/api/v1/generation/backend").with(authentication(fixture.auth()))
                        .contentType("application/json").content(request(attempt)))
                .andExpect(status().isForbidden());
        assertTrue(runRepository.findByAttemptId(attempt).isEmpty());
        verify(model, never()).generate(any());
    }

    @Test void adapterRejectionsRetainDistinctBoundedFailureCodes() throws Exception {
        prepare();
        for (var example : List.of(
                Map.entry(OpenRouterBackendModelAdapter.Failure.TRANSPORT, "MODEL_UNAVAILABLE"),
                Map.entry(OpenRouterBackendModelAdapter.Failure.INVALID_MODEL_OUTPUT, "MODEL_OUTPUT_INVALID"),
                Map.entry(OpenRouterBackendModelAdapter.Failure.COMPILATION, "MODEL_COMPILATION_FAILED"))) {
            UUID attempt = UUID.randomUUID();
            doThrow(new OpenRouterBackendModelAdapter.Failed(example.getKey())).when(model).generate(any());
            mvc.perform(post("/api/v1/generation/backend").with(authentication(fixture.auth()))
                            .contentType("application/json").content(request(attempt)))
                    .andExpect(status().isInternalServerError())
                    .andExpect(header().string("X-Generation-Failure-Stage", "AI"))
                    .andExpect(header().string("X-Generation-Failure-Code", example.getValue()))
                    .andExpect(jsonPath("$.errors._global").value("Generation attempt failed"));
            var run = runRepository.findByAttemptId(attempt).orElseThrow();
            assertEquals(GenerationRunStatus.FAILED, run.getStatus());
            assertEquals("AI", run.getFailureStage());
            assertEquals(example.getValue(), run.getFailureCode());
            assertTrue(artifactRepository.findAllByGenerationRunIdOrderByRelativePathAsc(run.getId()).isEmpty());
        }
        verify(finalBuild, never()).validate(any());
    }

    @Test void postCommitIntegrityReadFailureDoesNotRecordGenerationFailure() throws Exception {
        prepare();
        UUID attempt = UUID.randomUUID();
        doThrow(new IllegalStateException("test-only integrity read failure"))
                .when(runs).readResult(any(GenerationRun.class));
        mvc.perform(post("/api/v1/generation/backend").with(authentication(fixture.auth()))
                        .contentType("application/json").content(request(attempt)))
                .andExpect(status().isInternalServerError())
                .andExpect(header().doesNotExist("X-Generation-Run-Id"))
                .andExpect(header().doesNotExist("X-Generation-Failure-Stage"))
                .andExpect(header().doesNotExist("X-Generation-Failure-Code"))
                .andExpect(jsonPath("$.clientMessage").value("An unexpected error occurred"));
        var run = runRepository.findByAttemptId(attempt).orElseThrow();
        assertEquals(GenerationRunStatus.SUCCESS, run.getStatus());
        assertNull(run.getFailureStage());
        assertNull(run.getFailureCode());
        assertFalse(artifactRepository.findAllByGenerationRunIdOrderByRelativePathAsc(run.getId()).isEmpty());
        verify(runs, never()).recordFailure(any(), anyString(), any(), any());
    }

    @Test void publicPagedBackendZipBuildsAndRunsWithoutSupplementalSources() throws Exception {
        Assumptions.assumeTrue(Boolean.getBoolean("gsuif.test.connected-export"),
                "Public exported-JAR acceptance requires explicit Docker/PostgreSQL opt-in");
        for (String key : List.of("gsuif.test.maven-command", "maven.repo.local",
                "gsuif.test.backend-build-image-id", "gsuif.test.postgres-image",
                "gsuif.test.startup-evidence-dir")) assertNotNull(System.getProperty(key), key);
        CompleteOutputTestFixture selected = new CompleteOutputTestFixture();
        fixture = new TransactionTemplate(transactions).execute(status -> persist(selected.pagedSnapshot()));
        byte[] source;
        try (var resource = getClass().getResourceAsStream(
                "/generation/fixtures/StockUnitPagedServiceImpl.java.txt")) {
            assertNotNull(resource);
            source = resource.readAllBytes();
        }
        when(model.provenance()).thenReturn(MOCK);
        Path executable = Path.of(System.getProperty("gsuif.test.maven-command"));
        Path repository = Path.of(System.getProperty("maven.repo.local"));
        String image = System.getProperty("gsuif.test.backend-build-image-id");
        doAnswer(invocation -> {
            var input = invocation.getArgument(0, BackendModelInput.class);
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
            var files = json.createObjectNode();
            files.putArray("files").addObject().put("path", input.writablePath())
                    .put("content", new String(source, StandardCharsets.UTF_8));
            var envelope = json.createObjectNode();
            envelope.put("model", OpenRouterBackendModelAdapter.MODEL);
            envelope.putArray("choices").addObject().put("finish_reason", "stop")
                    .putObject("message").put("content", files.toString());
            envelope.putObject("usage").put("completion_tokens", 100);
            var dependencies = new BackendMavenDependencyProvisioner(executable, repository);
            var compiler = new DockerBackendJavaCompiler(image);
            var adapter = new OpenRouterBackendModelAdapter(
                    (payload, disposableKey, timeout) -> new OpenRouterBackendModelAdapter.Reply(200,
                            json.writeValueAsBytes(envelope)),
                    chosen -> {
                        var manifest = dependencies.provision(chosen);
                        return new OpenRouterBackendModelAdapter.IsolatedCompiler() {
                            @Override public boolean compile(BackendModelInput candidate, byte[] candidateSource,
                                    Duration remaining) {
                                return compiler.compile(candidate, candidateSource, manifest, remaining);
                            }
                            @Override public BackendCompileClasspath manifest() { return manifest; }
                        };
                    }, System::nanoTime, ignored -> fail("Mock provider must not retry"));
            return adapter.generate(input, "disposable-test-only", OpenRouterBackendModelAdapter.MODEL);
        }).when(model).generate(any());
        when(finalBuild.imageIdentity()).thenReturn(image);
        doAnswer(invocation -> {
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
            new BackendFinalPackageValidator(image, repository, executable.getParent().getParent())
                    .validate(invocation.getArgument(0));
            return null;
        }).when(finalBuild).validate(any());

        UUID attempt = UUID.randomUUID();
        var request = new GenerationApiDtos.BackendRequest(fixture.pageId(), selected.pagedSpecification(), attempt);
        String created = mvc.perform(post("/api/v1/generation/backend")
                        .with(authentication(fixture.auth())).contentType("application/json")
                        .content(json.writeValueAsBytes(request)))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        UUID runId = UUID.fromString(json.readTree(created).path("body").path("runId").asText());
        var run = runRepository.findById(runId).orElseThrow();
        assertEquals("stock-unit-api", json.readTree(created).path("body").path("backendPackage")
                .path("contractId").asText());
        assertEquals("3.0.0", json.readTree(created).path("body").path("backendPackage")
                .path("contractVersion").asText());
        assertEquals(run.getPackageManifestSha256(), json.readTree(created).path("body")
                .path("backendPackage").path("manifestSha256").asText());
        String details = mvc.perform(get("/api/v1/generation/runs/{id}", runId)
                        .with(authentication(fixture.auth())))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertEquals("stock-unit-api", json.readTree(details).path("body").path("backendPackage")
                .path("contractId").asText());
        assertEquals("3.0.0", json.readTree(details).path("body").path("backendPackage")
                .path("contractVersion").asText());
        assertEquals(run.getPackageManifestSha256(), json.readTree(details).path("body")
                .path("backendPackage").path("manifestSha256").asText());
        assertEquals("REAL_PROVIDER_RESPONSE", json.readTree(created).path("body")
                .path("backendPackage").path("validationScope").path("notValidatedTargets").get(0).asText());
        mvc.perform(put("/api/v1/generation/projects/{id}/destinations/connected-zip", fixture.projectId())
                        .with(authentication(fixture.auth())).contentType("application/json")
                        .content("{\"type\":\"ZIP\",\"path\":\"connected\"}"))
                .andExpect(status().isOk());
        String exported = mvc.perform(post("/api/v1/generation/runs/{id}/exports/connected-zip", runId)
                        .with(authentication(fixture.auth())))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        Path zip = Path.of(URI.create(json.readTree(exported).path("body").path("location").asText()));
        Path extracted = Files.createDirectory(scratch.resolve("exact-export"));
        var paths = new java.util.HashSet<String>();
        try (ZipFile archive = new ZipFile(zip.toFile())) {
            var entries = archive.entries();
            while (entries.hasMoreElements()) {
                var entry = entries.nextElement();
                if (entry.isDirectory()) continue;
                Path relative = GenerationArtifactStore.safeRelative(entry.getName());
                assertTrue(paths.add(entry.getName()), "Duplicate ZIP path");
                Path file = extracted.resolve(relative).normalize();
                assertTrue(file.startsWith(extracted));
                Files.createDirectories(file.getParent());
                Files.write(file, archive.getInputStream(entry).readAllBytes());
            }
        }
        assertArrayEquals(source, Files.readAllBytes(extracted.resolve(
                "backend/src/main/java/com/example/inventory/service/StockUnitServiceImpl.java")));
        byte[] contract = Files.readAllBytes(extracted.resolve("contracts/stock-unit-api-3.0.0.yaml"));
        try (var registered = getClass().getResourceAsStream("/openapi/stock-unit-api-paged.yaml")) {
            assertArrayEquals(registered.readAllBytes(), contract);
        }
        byte[] manifestBytes = Files.readAllBytes(extracted.resolve("generation-manifest.json"));
        assertEquals(run.getPackageManifestSha256(), BackendPackageIntegrity.hash(manifestBytes));
        var manifest = json.readTree(manifestBytes);
        assertTrue(manifest.path("provider").path("mocked").asBoolean());
        assertEquals(paths.size() - 1, manifest.path("files").size());
        for (var entry : manifest.path("files")) {
            Path file = extracted.resolve(GenerationArtifactStore.safeRelative(entry.path("path").asText()));
            assertEquals(entry.path("sha256").asText(), BackendPackageIntegrity.hash(Files.readAllBytes(file)));
        }
        var processes = new StandaloneBackendTestSupport(scratch);
        Path jar = processes.buildJar("connected-export", extracted);
        acceptExportedPagedRuntime(processes, jar);
    }

    private static ListAppender<ILoggingEvent> captureDiagnostics() {
        ListAppender<ILoggingEvent> events = new ListAppender<>();
        events.start();
        ((Logger) org.slf4j.LoggerFactory.getLogger(FinalValidationDiagnostics.class)).addAppender(events);
        return events;
    }

    private static void detachDiagnostics(ListAppender<ILoggingEvent> events) {
        ((Logger) org.slf4j.LoggerFactory.getLogger(FinalValidationDiagnostics.class)).detachAppender(events);
    }

    private void prepare() {
        fixture = new TransactionTemplate(transactions).execute(status -> persist());
        when(model.provenance()).thenReturn(MOCK);
        when(finalBuild.imageIdentity()).thenReturn("mock-build-image");
        doAnswer(invocation -> {
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive(),
                    "Final build must not hold a database transaction");
            return null;
        }).when(finalBuild).validate(any());
        when(model.generate(any())).thenAnswer(invocation -> {
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive(),
                    "Provider work must not hold a database transaction");
            return accepted(invocation.getArgument(0));
        });
    }

    private void acceptExportedPagedRuntime(StandaloneBackendTestSupport processes, Path jar) throws Exception {
        String dbPassword = "disposable-" + UUID.randomUUID();
        String adminPassword = "admin-" + UUID.randomUUID();
        byte[] secret = new byte[32]; new SecureRandom().nextBytes(secret);
        String jwt = Base64.getEncoder().encodeToString(secret);
        String label = "gsuif.scrum65.connected=" + UUID.randomUUID();
        Path envFile = scratch.resolve("connected-postgres.env");
        Files.writeString(envFile, "POSTGRES_PASSWORD=" + dbPassword + "\n");
        try {
            var created = processes.docker(List.of("run", "-d", "--name", "gsuif-scrum65-connected-" + UUID.randomUUID(),
                    "--label", label, "--env-file", envFile.toString(),
                    "-e", "POSTGRES_USER=generated_test", "-e", "POSTGRES_DB=generated_app",
                    "-p", "127.0.0.1::5432", System.getProperty("gsuif.test.postgres-image")));
            assertEquals(0, created.exit());
            assertTrue(created.output().matches("[a-f0-9]{64}"));
            String container = created.output();
            String port = processes.docker(List.of("port", container, "5432/tcp")).output().trim()
                    .replaceFirst("^127\\.0\\.0\\.1:", "");
            assertTrue(port.matches("[0-9]{2,5}"));
            String url = "jdbc:postgresql://127.0.0.1:" + port
                    + "/generated_app?connectTimeout=5&socketTimeout=5";
            long dbDeadline = System.nanoTime() + Duration.ofSeconds(60).toNanos();
            while (System.nanoTime() < dbDeadline && processes.docker(List.of("exec", container,
                    "pg_isready", "-U", "generated_test", "-d", "generated_app")).exit() != 0)
                Thread.sleep(500);
            assertTrue(System.nanoTime() < dbDeadline, "Disposable PostgreSQL did not become ready");
            Path bootstrapLog = scratch.resolve("connected-bootstrap.log");
            Process bootstrap = processes.bootstrapJar(jar, url, "generated_test", dbPassword,
                    "FirstAdmin", adminPassword, bootstrapLog);
            try {
                assertTrue(bootstrap.waitFor(100, TimeUnit.SECONDS));
                assertEquals(0, bootstrap.exitValue(), StandaloneBackendTestSupport.safeLines(bootstrapLog));
            } finally {
                StandaloneBackendTestSupport.stop(bootstrap);
                processes.preserveEvidence(bootstrapLog, dbPassword, adminPassword);
            }
            int webPort = StandaloneBackendTestSupport.freePort();
            Path appLog = scratch.resolve("connected-startup.log");
            Process app = processes.startJar(jar, url, "generated_test", dbPassword, jwt, webPort, appLog);
            try {
                long deadline = System.nanoTime() + Duration.ofSeconds(100).toNanos();
                boolean ready = false;
                while (System.nanoTime() < deadline && app.isAlive()) {
                    try {
                        if (send(webPort, "GET", "/v3/api-docs", null, null).statusCode() == 200) {
                            ready = true; break;
                        }
                    } catch (java.io.IOException ignored) { }
                    Thread.sleep(500);
                }
                assertTrue(ready, StandaloneBackendTestSupport.safeLines(appLog));
                assertError(send(webPort, "GET", "/api/v1/stock-units?condition=OPEN", null, null), 401);
                String loginJson = json.writeValueAsString(Map.of("username", "FirstAdmin", "password", adminPassword));
                var login = send(webPort, "POST", "/api/auth/login", null, loginJson);
                assertEquals(200, login.statusCode());
                String token = envelope(login).path("body").path("accessToken").asText();
                assertFalse(token.isBlank());
                List<String> ids = new ArrayList<>();
                for (int i = 0; i < 23; i++) {
                    var item = assertSuccess(send(webPort, "POST", "/api/v1/stock-units", token,
                            "{\"label\":\"group\",\"condition\":\"OPEN\",\"retiredOn\":null}"), 201);
                    ids.add(item.path("id").asText());
                }
                assertSuccess(send(webPort, "POST", "/api/v1/stock-units", token,
                        "{\"label\":\"other\",\"condition\":\"CLOSED\",\"retiredOn\":null}"), 201);
                ids.sort(String::compareTo);
                var first = assertSuccess(send(webPort, "GET", "/api/v1/stock-units?condition=OPEN", token, null), 200);
                var second = assertSuccess(send(webPort, "GET", "/api/v1/stock-units?condition=OPEN&page=1", token, null), 200);
                assertPage(first, 0, 20, 23, ids.subList(0, 20));
                assertPage(second, 1, 20, 23, ids.subList(20, 23));
                assertPage(assertSuccess(send(webPort, "GET", "/api/v1/stock-units?condition=OPEN&page=2",
                        token, null), 200), 2, 20, 23, List.of());
                List<String> collected = new ArrayList<>();
                for (int page = 0; page < 5; page++) {
                    var data = assertSuccess(send(webPort, "GET", "/api/v1/stock-units?condition=OPEN&label=group&page="
                            + page + "&size=5", token, null), 200);
                    assertEquals(23, data.path("totalElements").asInt());
                    data.path("data").forEach(item -> collected.add(item.path("id").asText()));
                }
                assertEquals(ids, collected);
                assertPage(assertSuccess(send(webPort, "GET", "/api/v1/stock-units?condition=CLOSED",
                        token, null), 200), 0, 20, 1, null);
                for (String invalid : List.of("page=-1", "size=0", "size=101", "page=bad", "size=bad"))
                    assertError(send(webPort, "GET", "/api/v1/stock-units?condition=OPEN&" + invalid,
                            token, null), 400);
                String id = ids.getFirst();
                assertEquals("group", assertSuccess(send(webPort, "GET", "/api/v1/stock-units/" + id,
                        token, null), 200).path("label").asText());
                assertEquals("changed", assertSuccess(send(webPort, "PUT", "/api/v1/stock-units/" + id,
                        token, "{\"label\":\"changed\",\"condition\":\"OPEN\",\"retiredOn\":null}"),
                        200).path("label").asText());
                assertTrue(assertSuccess(send(webPort, "DELETE", "/api/v1/stock-units/" + id,
                        token, null), 200).isNull());
                assertError(send(webPort, "GET", "/api/v1/stock-units/" + id, token, null), 404);
                try (var connection = DriverManager.getConnection(url, "generated_test", dbPassword);
                     var statement = connection.createStatement();
                     var rows = statement.executeQuery("SELECT count(*) FROM stock_unit")) {
                    assertTrue(rows.next()); assertEquals(23, rows.getInt(1));
                }
            } finally {
                StandaloneBackendTestSupport.stop(app);
                processes.preserveEvidence(appLog, dbPassword, jwt);
            }
        } finally {
            processes.cleanupOwned("connected-export", label, envFile, dbPassword);
        }
    }

    private HttpResponse<String> send(int port, String method, String path, String bearer, String body) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .timeout(Duration.ofSeconds(5));
        if (bearer != null) request.header("Authorization", "Bearer " + bearer);
        if (body != null) request.header("Content-Type", "application/json");
        request.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(body));
        return HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build()
                .send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private com.fasterxml.jackson.databind.JsonNode envelope(HttpResponse<String> response) throws Exception {
        assertEquals(Set.of("status", "clientMessage", "statusCode", "body", "errors"),
                json.readValue(response.body(), Map.class).keySet());
        return json.readTree(response.body());
    }

    private com.fasterxml.jackson.databind.JsonNode assertSuccess(HttpResponse<String> response, int status) throws Exception {
        assertEquals(status, response.statusCode());
        var value = envelope(response);
        assertEquals("OK", value.path("status").asText());
        assertEquals(status, value.path("statusCode").asInt());
        assertTrue(value.path("errors").isNull());
        return value.path("body");
    }

    private void assertError(HttpResponse<String> response, int status) throws Exception {
        assertEquals(status, response.statusCode());
        var value = envelope(response);
        assertEquals(status, value.path("statusCode").asInt());
        assertTrue(value.path("body").isNull());
        assertTrue(value.path("errors").isObject());
    }

    private void assertPage(com.fasterxml.jackson.databind.JsonNode page, int number, int size,
            int total, List<String> expectedIds) {
        assertEquals(number, page.path("number").asInt());
        assertEquals(size, page.path("size").asInt());
        assertEquals(total, page.path("totalElements").asInt());
        if (expectedIds != null) {
            assertEquals(expectedIds.size(), page.path("data").size());
            for (int i = 0; i < expectedIds.size(); i++)
                assertEquals(expectedIds.get(i), page.path("data").get(i).path("id").asText());
        }
    }

    private BackendModelOutputValidator.Accepted accepted(BackendModelInput input) throws Exception {
        ObjectNode response = json.createObjectNode();
        response.putArray("files").addObject().put("path", input.writablePath()).put("content",
                "package com.example.fixture.service; public class AssetTicketServiceImpl implements AssetTicketService {}");
        return new BackendModelOutputValidator((chosen, source) -> true)
                .validate(input, json.writeValueAsBytes(response));
    }

    private UUID create(UUID attempt) throws Exception {
        String body = mvc.perform(post("/api/v1/generation/backend").with(authentication(fixture.auth()))
                        .contentType("application/json").content(request(attempt)))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return UUID.fromString(json.readTree(body).path("body").path("runId").asText());
    }

    private byte[] request(UUID attempt) throws Exception {
        return json.writeValueAsBytes(new GenerationApiDtos.BackendRequest(fixture.pageId(), specification(), attempt));
    }

    private Fixture persist() { return persist(snapshot()); }

    private Fixture persist(ObjectNode selectedSnapshot) {
        String unique = UUID.randomUUID().toString().substring(0, 12);
        GsuifProject project = new GsuifProject(); project.setName("Connected backend " + unique); entities.persist(project);
        GsuifPage page = new GsuifPage(); page.setProject(project); page.setName("Asset tickets"); entities.persist(page);
        MetadataVersion version = new MetadataVersion(); version.setPage(page); version.setVersion(1);
        version.setSchemaVersion("1.0.0"); version.setSnapshot(selectedSnapshot.toString()); entities.persist(version);
        entities.flush(); page.setCurrentMetadataVersionId(version.getId());
        GsuifUser user = new GsuifUser(); user.setUsername("backend-" + unique);
        user.setPasswordHash("test-only"); entities.persist(user);
        GsuifRole role = new GsuifRole(); role.setName("ROLE_BACKEND_" + unique); entities.persist(role);
        entities.flush();
        GsuifUserRole membership = new GsuifUserRole();
        membership.setId(new GsuifUserRoleId(user.getId(), role.getId()));
        membership.setUser(user); membership.setRole(role); entities.persist(membership);
        for (String code : List.of("ENTITY_ACCESS:GsuifPage", "ENTITY_ACCESS:MetadataVersion",
                "ENTITY_ACCESS:GsuifProject")) {
            var permission = permissions.findByCode(code).orElseThrow();
            GsuifRolePermission grant = new GsuifRolePermission();
            grant.setId(new GsuifRolePermissionId(role.getId(), permission.getId()));
            grant.setRole(role); grant.setPermission(permission); entities.persist(grant);
        }
        Authentication auth = new UsernamePasswordAuthenticationToken(user.getUsername(), null,
                List.of(new SimpleGrantedAuthority(role.getName())));
        return new Fixture(project.getId(), page.getId(), version.getId(), user.getId(), role.getId(), auth);
    }

    private ObjectNode snapshot() {
        ObjectNode result = new ObjectMapper().createObjectNode();
        result.putArray("components");
        var bindings = result.putArray("apiBindings");
        String[] verbs = {"GET", "POST", "GET", "PUT", "DELETE"};
        for (int i = 0; i < 5; i++) {
            ObjectNode binding = bindings.addObject();
            binding.put("id", new UUID(0, i + 1).toString());
            binding.put("httpMethod", verbs[i]);
            binding.put("endpointUrl", "/api/v1/asset-tickets" + (i > 1 ? "/{id}" : ""));
            binding.putObject("headers");
            ObjectNode request = binding.putObject("requestMapping");
            if (i == 0) request.putObject("query").put("status", "status");
            if (i > 1) request.putObject("path").put("id", "id");
            if (i == 1 || i == 3) request.putObject("body").put("title", "title").put("status", "status");
            ObjectNode response = binding.putObject("responseMapping");
            if (i == 0) response.put("list", "body.data"); else response.put("item", "body");
        }
        return result;
    }

    private GenerationSpecification specification() {
        List<String> operations = List.of("listAssetTickets", "createAssetTicket", "getAssetTicketById",
                "updateAssetTicket", "deleteAssetTicket");
        Map<UUID, String> bindings = new LinkedHashMap<>();
        Map<String, Set<SupportedRole>> roles = new LinkedHashMap<>();
        for (int i = 0; i < operations.size(); i++) {
            bindings.put(new UUID(0, i + 1), operations.get(i));
            roles.put(operations.get(i), Set.of(SupportedRole.ROLE_ADMIN));
        }
        return new GenerationSpecification("1.0.0", "com.example.fixture",
                new EntitySpec("AssetTicket", "asset_ticket", List.of(
                        new FieldSpec("title", "title", "String", false, 100, null),
                        new FieldSpec("status", "status", "AssetTicketStatus", false, null, "AssetTicketStatus")), false),
                new OpenApiSpec("asset-ticket-api", "2.0.0", bindings), roles,
                new AngularSpec("AssetTicket", Map.of()));
    }

    private record Fixture(UUID projectId, UUID pageId, UUID versionId, UUID userId, UUID roleId,
                           Authentication auth) { }
}
