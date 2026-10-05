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
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

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

        assertSame(result, first.result());
        assertEquals(first.run().getId(), retry.run().getId());
        assertEquals(before + 1, runRepository.count());
        verify(engine, times(2)).generate(eq(fixture.version()), isNull(), anySet(), eq("spring-angular"));
    }

    @Test void generationFailureBeforeResultDoesNotCreateRun() {
        Fixture fixture = fixture();
        GenerationEngine engine = mock(GenerationEngine.class);
        when(engine.generate(eq(fixture.version()), isNull(), anySet(), eq("spring-angular")))
                .thenThrow(new GenerationValidationException(List.of("invalid specification")));
        var workflow = new GenerationWorkflow(engine, runs);
        long beforeRuns = runRepository.count();
        long beforeFiles = artifactRepository.count();

        assertThrows(GenerationValidationException.class, () -> workflow.generateAndRecord(
                UUID.randomUUID(), fixture.version(), fixture.user(), null,
                Set.of(GenerationContext.Target.ENTITY), "spring-angular", Map.of()));
        assertEquals(beforeRuns, runRepository.count());
        assertEquals(beforeFiles, artifactRepository.count());
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

    private record Fixture(GsuifProject project, GsuifPage page, MetadataVersion version, GsuifUser user) { }
}
