package eg.mts.gsuif.generation;

import eg.mts.gsuif.entity.*;
import eg.mts.gsuif.repository.GeneratedArtifactRepository;
import eg.mts.gsuif.repository.GenerationRunRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@SpringBootTest
@ActiveProfiles("test")
class GenerationWorkflowTransactionIntegrationTest {
    @Autowired EntityManager entityManager;
    @Autowired TransactionTemplate transactions;
    @Autowired GenerationRunService runs;
    @Autowired GenerationRunRepository runRepository;
    @Autowired GeneratedArtifactRepository artifactRepository;
    @Autowired GenerationArtifactStore artifactStore;
    private Fixture persistedFixture;
    private UUID recordedRunId;

    @AfterEach void removeCommittedFixture() {
        if (persistedFixture == null) return;
        transactions.executeWithoutResult(status -> {
            if (recordedRunId != null) {
                entityManager.createQuery("delete from GeneratedArtifact a where a.generationRun.id = :runId")
                        .setParameter("runId", recordedRunId).executeUpdate();
                entityManager.createQuery("delete from GenerationRun r where r.id = :runId")
                        .setParameter("runId", recordedRunId).executeUpdate();
            }
            entityManager.remove(entityManager.getReference(MetadataVersion.class, persistedFixture.version().getId()));
            entityManager.remove(entityManager.getReference(GsuifPage.class, persistedFixture.page().getId()));
            entityManager.remove(entityManager.getReference(GsuifProject.class, persistedFixture.project().getId()));
            entityManager.remove(entityManager.getReference(GsuifUser.class, persistedFixture.user().getId()));
        });
    }

    @Test void artifactConstraintFailureRollsBackTheRun() {
        Fixture fixture = fixture();
        long initialRuns = runRepository.count();
        long initialFiles = artifactRepository.count();
        var artifact = new GenerationResult.Artifact("src/main/java/example/Duplicate.java",
                "class Duplicate {}".getBytes(StandardCharsets.UTF_8), "hash", "1.0", "1.0");
        var result = new GenerationResult(List.of(artifact, artifact), List.of(), ConsumerBuildContract.phaseOne());

        assertThrows(RuntimeException.class, () -> runs.validateAndRecord(UUID.randomUUID(),
                fixture.version(), fixture.user(), result, Map.of()));
        assertEquals(initialRuns, runRepository.count());
        assertEquals(initialFiles, artifactRepository.count());
    }

    @Test void storageFailureDoesNotCommitRegistryRows() throws Exception {
        Fixture fixture = fixture();
        Path blockedRoot = Path.of("target", "blocked-artifact-root-" + UUID.randomUUID());
        Files.createDirectories(blockedRoot.getParent());
        Files.writeString(blockedRoot, "not a directory");
        long beforeRuns = runRepository.count();
        long beforeFiles = artifactRepository.count();
        var service = service(new GenerationArtifactStore(blockedRoot));
        try {
            assertThrows(IllegalStateException.class, () -> transactions.execute(status ->
                    service.validateAndRecord(UUID.randomUUID(), fixture.version(), fixture.user(), sample(), Map.of())));
            assertEquals(beforeRuns, runRepository.count());
            assertEquals(beforeFiles, artifactRepository.count());
        } finally { Files.deleteIfExists(blockedRoot); }
    }

    @Test void databaseRollbackRemovesAlreadyWrittenFiles() {
        Fixture fixture = fixture();
        UUID[] saved = new UUID[1];
        long beforeRuns = runRepository.count();
        long beforeFiles = artifactRepository.count();
        var service = service(artifactStore);
        assertThrows(IllegalStateException.class, () -> transactions.executeWithoutResult(status -> {
            saved[0] = service.validateAndRecord(UUID.randomUUID(), fixture.version(), fixture.user(),
                    sample(), Map.of()).getId();
            assertArrayEquals(sample().artifacts().getFirst().bytes(),
                    service.readArtifact(saved[0], sample().artifacts().getFirst().relativePath()));
            throw new IllegalStateException("force database rollback");
        }));
        assertNotNull(saved[0]);
        assertFalse(Files.exists(Path.of("target", "generation-artifact-tests", saved[0].toString())));
        assertEquals(beforeRuns, runRepository.count());
        assertEquals(beforeFiles, artifactRepository.count());
    }

    @Test void databaseConstraintFailureRemovesFilesAndRegistryRows() {
        Fixture fixture = fixture();
        UUID[] saved = new UUID[1];
        long beforeRuns = runRepository.count();
        long beforeFiles = artifactRepository.count();
        var service = service(artifactStore);
        var invalid = new GenerationResult(List.of(sample().artifacts().getFirst(),
                new GenerationResult.Artifact("src/main/java/example/Other.java",
                        "package example; class Other {}".getBytes(StandardCharsets.UTF_8),
                        "hash", "v".repeat(101), "1.0")), List.of(), ConsumerBuildContract.phaseOne());
        assertThrows(RuntimeException.class, () -> transactions.executeWithoutResult(status -> {
            saved[0] = service.validateAndRecord(UUID.randomUUID(), fixture.version(), fixture.user(),
                    invalid, Map.of()).getId();
            entityManager.flush();
        }));
        assertNotNull(saved[0]);
        assertFalse(Files.exists(Path.of("target", "generation-artifact-tests", saved[0].toString())));
        assertEquals(beforeRuns, runRepository.count());
        assertEquals(beforeFiles, artifactRepository.count());
    }

    @Test void workflowRecordsCompletedResultOnceForStableAttempt() {
        Fixture fixture = fixture();
        GenerationEngine engine = mock(GenerationEngine.class);
        var result = new GenerationResult(List.of(), List.of());
        when(engine.generate(eq(fixture.version()), isNull(), eq(Set.of(GenerationContext.Target.ENTITY)),
                eq("spring-angular"))).thenReturn(result);
        var workflow = new GenerationWorkflow(engine, runs);
        UUID attempt = UUID.randomUUID();
        long before = runRepository.count();

        var first = workflow.generateAndRecord(attempt, fixture.version(), fixture.user(), null,
                Set.of(GenerationContext.Target.ENTITY), "spring-angular", Map.of());
        recordedRunId = first.run().getId();
        var retry = workflow.generateAndRecord(attempt, fixture.version(), fixture.user(), null,
                Set.of(GenerationContext.Target.ENTITY), "spring-angular", Map.of());

        assertEquals(result.artifacts(), retry.result().artifacts());
        assertEquals(result.diagnostics(), retry.result().diagnostics());
        assertEquals(first.run().getId(), retry.run().getId());
        assertEquals(before + 1, runRepository.count());
        verify(engine, times(1)).generate(eq(fixture.version()), isNull(), anySet(), eq("spring-angular"));
    }

    @Test void changedGenerationOrBuildInputsRejectReusedAttemptBeforeGeneratingAgain() {
        Fixture fixture = fixture();
        GenerationEngine engine = mock(GenerationEngine.class);
        var result = new GenerationResult(List.of(), List.of());
        when(engine.generate(eq(fixture.version()), isNull(), anySet(), anyString())).thenReturn(result);
        var workflow = new GenerationWorkflow(engine, runs);
        UUID attempt = UUID.randomUUID();
        var first = workflow.generateAndRecord(attempt, fixture.version(), fixture.user(), null,
                Set.of(GenerationContext.Target.ENTITY), "spring-angular", Map.of());
        recordedRunId = first.run().getId();

        String fragment = "templates/freemarker/angular/table.ftl";
        String original = GenerationAttemptFingerprint.request(fixture.version(), fixture.user(), null,
                Set.of(GenerationContext.Target.ENTITY), "spring-angular", Map.of());
        String changedFragment = GenerationAttemptFingerprint.request(fixture.version(), fixture.user(), null,
                Set.of(GenerationContext.Target.ENTITY), "spring-angular", Map.of(),
                Map.of(fragment, "changed table fragment".getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        assertNotEquals(original, changedFragment);
        assertThrows(IllegalArgumentException.class, () -> runs.validateAndRecord(attempt,
                fixture.version(), fixture.user(), result, Map.of(), changedFragment));

        assertThrows(IllegalArgumentException.class, () -> workflow.generateAndRecord(attempt,
                fixture.version(), fixture.user(), null, Set.of(GenerationContext.Target.CONTROLLER),
                "spring-angular", Map.of()));
        assertThrows(IllegalArgumentException.class, () -> workflow.generateAndRecord(attempt,
                fixture.version(), fixture.user(), null, Set.of(GenerationContext.Target.ENTITY),
                "different-framework", Map.of()));
        assertThrows(IllegalArgumentException.class, () -> workflow.generateAndRecord(attempt,
                fixture.version(), fixture.user(), null, Set.of(GenerationContext.Target.ENTITY),
                "spring-angular", Map.of("src/test/java/example/Check.java", new byte[] {1})));
        assertThrows(IllegalArgumentException.class, () -> workflow.generateAndRecord(attempt,
                fixture.version(), fixture.user(), new GenerationSpecification("1.0", "example", null,
                        null, Map.of(), null), Set.of(GenerationContext.Target.ENTITY),
                "spring-angular", Map.of()));
        fixture.project().setName("Different project name");
        assertThrows(IllegalArgumentException.class, () -> workflow.generateAndRecord(attempt,
                fixture.version(), fixture.user(), null, Set.of(GenerationContext.Target.ENTITY),
                "spring-angular", Map.of()));
        verify(engine, times(1)).generate(any(), any(), anySet(), anyString());
        assertEquals(1, runRepository.findByAttemptId(attempt).stream().count());
    }

    @Test void concurrentWinningRunDeterminesReturnedResult() {
        Fixture fixture = fixture();
        GenerationEngine engine = mock(GenerationEngine.class);
        GenerationRunService service = mock(GenerationRunService.class);
        GenerationRun winningRun = new GenerationRun();
        var fresh = sample();
        var persisted = new GenerationResult(List.of(), List.of("original result"));
        UUID attempt = UUID.randomUUID();
        when(service.findByAttemptId(attempt)).thenReturn(java.util.Optional.empty());
        when(engine.generate(any(), any(), anySet(), anyString())).thenReturn(fresh);
        when(service.completeReservedAttempt(eq(attempt), any(), any(), same(fresh), anyMap(), anyString()))
                .thenReturn(winningRun);
        when(service.readResult(winningRun)).thenReturn(persisted);

        var completed = new GenerationWorkflow(engine, service).generateAndRecord(attempt,
                fixture.version(), fixture.user(), null, Set.of(GenerationContext.Target.ENTITY),
                "spring-angular", Map.of());

        assertSame(winningRun, completed.run());
        assertSame(persisted, completed.result());
    }

    @Test void generationFailureBeforeResultPersistsSafeStageWithoutArtifacts() {
        Fixture fixture = fixture();
        GenerationEngine engine = mock(GenerationEngine.class);
        when(engine.generate(eq(fixture.version()), isNull(), anySet(), eq("spring-angular")))
                .thenThrow(new GenerationValidationException(List.of("invalid specification")));
        var workflow = new GenerationWorkflow(engine, runs);
        long beforeRuns = runRepository.count();
        long beforeFiles = artifactRepository.count();

        UUID attempt = UUID.randomUUID();
        assertThrows(GenerationValidationException.class, () -> workflow.generateAndRecord(
                attempt, fixture.version(), fixture.user(), null,
                Set.of(GenerationContext.Target.ENTITY), "spring-angular", Map.of()));
        GenerationRun failed = runRepository.findByAttemptId(attempt).orElseThrow();
        recordedRunId = failed.getId();
        assertEquals(beforeRuns + 1, runRepository.count());
        assertEquals(beforeFiles, artifactRepository.count());
        assertEquals(GenerationRunStatus.FAILED, failed.getStatus());
        assertEquals("CONTRACT", failed.getFailureStage());
        assertEquals("VALIDATION_FAILED", failed.getFailureCode());
        assertThrows(IllegalStateException.class, () -> runs.readResult(failed));
        assertThrows(IllegalStateException.class, () -> workflow.generateAndRecord(attempt,
                fixture.version(), fixture.user(), null, Set.of(GenerationContext.Target.ENTITY),
                "spring-angular", Map.of()));
        verify(engine, times(1)).generate(any(), any(), anySet(), anyString());
    }

    @Test void explicitAiFailureAndInterruptedAttemptStayTerminal() {
        Fixture fixture = fixture();
        GenerationEngine engine = mock(GenerationEngine.class);
        when(engine.generate(any(), any(), anySet(), anyString()))
                .thenThrow(new GenerationStageException(GenerationFailureStage.AI, GenerationFailureCode.MODEL_UNAVAILABLE));
        UUID attempt = UUID.randomUUID();
        var workflow = new GenerationWorkflow(engine, runs);
        assertThrows(GenerationStageException.class, () -> workflow.generateAndRecord(attempt,
                fixture.version(), fixture.user(), null, Set.of(GenerationContext.Target.ENTITY),
                "spring-angular", Map.of()));
        GenerationRun failed = runRepository.findByAttemptId(attempt).orElseThrow();
        recordedRunId = failed.getId();
        assertEquals("AI", failed.getFailureStage());
        assertEquals("MODEL_UNAVAILABLE", failed.getFailureCode());

        UUID interruptedId = UUID.randomUUID();
        String fingerprint = GenerationAttemptFingerprint.request(fixture.version(), fixture.user(), null,
                Set.of(GenerationContext.Target.ENTITY), "spring-angular", Map.of());
        GenerationRun pending = runs.reserveAttempt(interruptedId, fixture.version(), fixture.user(), fingerprint);
        // This test removes both rows during fixture cleanup.
        transactions.executeWithoutResult(status -> entityManager.createQuery("delete from GenerationRun r where r.id = :runId")
                .setParameter("runId", failed.getId()).executeUpdate());
        recordedRunId = pending.getId();
        assertEquals(GenerationRunStatus.IN_PROGRESS, pending.getStatus());
        assertThrows(IllegalStateException.class, () -> workflow.generateAndRecord(interruptedId,
                fixture.version(), fixture.user(), null, Set.of(GenerationContext.Target.ENTITY),
                "spring-angular", Map.of()));
        assertEquals(GenerationRunStatus.IN_PROGRESS,
                runRepository.findByAttemptId(interruptedId).orElseThrow().getStatus());
    }

    @Test void concurrentSameIdRequestCannotGenerateOrChangeReservedFingerprint() throws Exception {
        Fixture fixture = fixture();
        GenerationEngine engine = mock(GenerationEngine.class);
        CountDownLatch generating = new CountDownLatch(1), release = new CountDownLatch(1);
        when(engine.generate(any(), any(), anySet(), anyString())).thenAnswer(invocation -> {
            generating.countDown();
            if (!release.await(20, TimeUnit.SECONDS)) throw new IllegalStateException("timed out");
            return new GenerationResult(List.of(), List.of());
        });
        var workflow = new GenerationWorkflow(engine, runs);
        UUID attempt = UUID.randomUUID();
        var executor = Executors.newSingleThreadExecutor();
        try {
            var first = executor.submit(() -> workflow.generateAndRecord(attempt, fixture.version(), fixture.user(),
                    null, Set.of(GenerationContext.Target.ENTITY), "spring-angular", Map.of()));
            assertTrue(generating.await(20, TimeUnit.SECONDS));
            assertThrows(IllegalStateException.class, () -> workflow.generateAndRecord(attempt,
                    fixture.version(), fixture.user(), null, Set.of(GenerationContext.Target.ENTITY),
                    "spring-angular", Map.of()));
            assertThrows(IllegalArgumentException.class, () -> workflow.generateAndRecord(attempt,
                    fixture.version(), fixture.user(), null, Set.of(GenerationContext.Target.CONTROLLER),
                    "spring-angular", Map.of()));
            release.countDown();
            var completed = first.get(30, TimeUnit.SECONDS);
            recordedRunId = completed.run().getId();
            assertEquals(GenerationRunStatus.BUILD_FAILED, completed.run().getStatus());
            assertEquals(1, runRepository.findByAttemptId(attempt).stream().count());
            verify(engine, times(1)).generate(any(), any(), anySet(), anyString());
        } finally {
            release.countDown();
            executor.shutdownNow();
        }
    }

    @Test void failedCompletionRollsBackArtifactsBeforeRecordingFailure() {
        Fixture fixture = fixture();
        GenerationEngine engine = mock(GenerationEngine.class);
        var artifact = sample().artifacts().getFirst();
        when(engine.generate(any(), any(), anySet(), anyString())).thenReturn(
                new GenerationResult(List.of(artifact, artifact), List.of(), ConsumerBuildContract.phaseOne()));
        UUID attempt = UUID.randomUUID();
        long beforeFiles = artifactRepository.count();
        assertThrows(GenerationStageException.class, () -> new GenerationWorkflow(engine, runs)
                .generateAndRecord(attempt, fixture.version(), fixture.user(), null,
                        Set.of(GenerationContext.Target.ENTITY), "spring-angular", Map.of()));
        GenerationRun failed = runRepository.findByAttemptId(attempt).orElseThrow();
        recordedRunId = failed.getId();
        assertEquals(GenerationRunStatus.FAILED, failed.getStatus());
        assertEquals("FINAL_VALIDATION", failed.getFailureStage());
        assertEquals(beforeFiles, artifactRepository.count());
        assertFalse(Files.exists(Path.of("target", "generation-artifact-tests", failed.getId().toString())));
    }

    @Test void failureReservationSurvivesCallerRollback() {
        Fixture fixture = fixture();
        GenerationEngine engine = mock(GenerationEngine.class);
        when(engine.generate(any(), any(), anySet(), anyString()))
                .thenThrow(new GenerationStageException(GenerationFailureStage.ASSEMBLY, GenerationFailureCode.INVALID_SOURCE_MAP));
        UUID attempt = UUID.randomUUID();
        var workflow = new GenerationWorkflow(engine, runs);
        transactions.executeWithoutResult(status -> {
            assertThrows(GenerationStageException.class, () -> workflow.generateAndRecord(attempt,
                    fixture.version(), fixture.user(), null, Set.of(GenerationContext.Target.ENTITY),
                    "spring-angular", Map.of()));
            status.setRollbackOnly();
        });
        GenerationRun saved = runRepository.findByAttemptId(attempt).orElseThrow();
        recordedRunId = saved.getId();
        assertEquals(GenerationRunStatus.FAILED, saved.getStatus());
        assertEquals("ASSEMBLY", saved.getFailureStage());
        assertEquals("INVALID_SOURCE_MAP", saved.getFailureCode());
        assertTrue(artifactRepository.findAllByGenerationRunIdOrderByRelativePathAsc(saved.getId()).isEmpty());
    }

    private Fixture fixture() {
        persistedFixture = transactions.execute(status -> {
            GsuifProject project = new GsuifProject(); project.setName("Workflow " + UUID.randomUUID());
            entityManager.persist(project);
            GsuifPage page = new GsuifPage(); page.setProject(project); page.setName("Page"); entityManager.persist(page);
            MetadataVersion version = new MetadataVersion(); version.setPage(page); version.setVersion(1);
            version.setSchemaVersion("1.0.0"); version.setSnapshot("{\"components\":[],\"apiBindings\":[]}");
            entityManager.persist(version);
            GsuifUser user = new GsuifUser(); user.setUsername("workflow-" + UUID.randomUUID());
            user.setPasswordHash("hash"); entityManager.persist(user);
            entityManager.flush();
            return new Fixture(project, page, version, user);
        });
        return persistedFixture;
    }

    private GenerationRunService service(GenerationArtifactStore store) {
        return new GenerationRunService(runRepository, artifactRepository,
                new ConsumerBuildValidator(Path.of("target", "generation-build-tests"),
                        (project, goal) -> {
                            if (goal.equals("test")) ConsumerBuildValidatorTest.writeReport(project, 1, 0);
                            return new ConsumerBuildValidator.CommandResult(0, "passed");
                        }), store);
    }

    private GenerationResult sample() {
        return new GenerationResult(List.of(new GenerationResult.Artifact(
                "src/main/java/example/Sample.java", "package example; class Sample {}".getBytes(StandardCharsets.UTF_8),
                "hash", "1.0", "1.0")), List.of(), ConsumerBuildContract.phaseOne());
    }

    private record Fixture(GsuifProject project, GsuifPage page, MetadataVersion version, GsuifUser user) { }
}
