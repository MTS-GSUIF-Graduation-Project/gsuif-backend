package eg.mts.gsuif.generation;

import eg.mts.gsuif.entity.GeneratedArtifact;
import eg.mts.gsuif.entity.GenerationRun;
import eg.mts.gsuif.entity.GenerationRunStatus;
import eg.mts.gsuif.entity.GsuifPage;
import eg.mts.gsuif.entity.GsuifUser;
import eg.mts.gsuif.entity.MetadataVersion;
import eg.mts.gsuif.exception.ResourceNotFoundException;
import eg.mts.gsuif.generation.GenerationContext.Target;
import eg.mts.gsuif.repository.GeneratedArtifactRepository;
import eg.mts.gsuif.repository.GsuifPageRepository;
import eg.mts.gsuif.repository.GsuifUserRepository;
import eg.mts.gsuif.repository.MetadataVersionRepository;
import eg.mts.gsuif.validator.MetadataSchemaValidator;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class GenerationApiServiceTest {
    GsuifPageRepository pages = mock(GsuifPageRepository.class);
    MetadataVersionRepository versions = mock(MetadataVersionRepository.class);
    GsuifUserRepository users = mock(GsuifUserRepository.class);
    GeneratedArtifactRepository artifacts = mock(GeneratedArtifactRepository.class);
    GenerationWorkflow workflow = mock(GenerationWorkflow.class);
    GenerationRunService runs = mock(GenerationRunService.class);
    GenerationApiService service = new GenerationApiService(pages, versions, users, artifacts, workflow,
            runs, mock(MetadataSchemaValidator.class));

    @Test void downloadRequiresSavedRunRegisteredSafePathAndVerifiedStorage() {
        UUID id = UUID.randomUUID();
        String path = "src/main/java/Thing.java";
        assertThrows(ResourceNotFoundException.class, () -> service.download(id, path));
        when(runs.findById(id)).thenReturn(Optional.of(mock(GenerationRun.class)));
        assertThrows(GenerationValidationException.class, () -> service.download(id, "../outside"));
        assertThrows(ResourceNotFoundException.class, () -> service.download(id, path));
        when(runs.findFile(id, path)).thenReturn(Optional.of(mock(GeneratedArtifact.class)));
        when(runs.readArtifact(id, path)).thenReturn("saved".getBytes(StandardCharsets.UTF_8));
        assertArrayEquals("saved".getBytes(StandardCharsets.UTF_8), service.download(id, path).bytes());
        when(runs.readArtifact(id, path)).thenThrow(new IllegalStateException("Stored artifact content changed"));
        assertThrows(IllegalStateException.class, () -> service.download(id, path));
    }

    @Test void artifactHistoryTracesFileThroughRunToVersion() {
        String path = "src/main/java/Thing.java";
        UUID artifactId = UUID.randomUUID(), runId = UUID.randomUUID(), versionId = UUID.randomUUID();
        var artifact = mock(GeneratedArtifact.class);
        var run = mock(GenerationRun.class);
        var version = mock(MetadataVersion.class);
        when(runs.findFileHistory(path)).thenReturn(List.of(artifact));
        when(artifact.getId()).thenReturn(artifactId);
        when(artifact.getRelativePath()).thenReturn(path);
        when(artifact.getGenerationRun()).thenReturn(run);
        when(run.getId()).thenReturn(runId);
        when(run.getMetadataVersion()).thenReturn(version);
        when(version.getId()).thenReturn(versionId);
        var trace = service.artifactHistory(path).getFirst();
        assertEquals(artifactId, trace.artifactId());
        assertEquals(runId, trace.runId());
        assertEquals(versionId, trace.metadataVersionId());
    }

    @Test void supportedTargetsMatchApprovedTeamMapping() {
        assertEquals(Set.of(Target.ENTITY), GenerationApiService.targets("ENTITY"));
        assertEquals(Set.of(Target.CONTROLLER), GenerationApiService.targets("CONTROLLER"));
        assertEquals(Set.of(Target.ANGULAR), GenerationApiService.targets("ANGULAR"));
        assertEquals(Set.of(Target.ENTITY, Target.CONTROLLER, Target.ANGULAR), GenerationApiService.targets("FULL_STACK"));
        assertThrows(GenerationValidationException.class, () -> GenerationApiService.targets("ALL"));
    }

    @Test void invalidTypeAndAbsentSelectionNeverStartGeneration() {
        UUID pageId = UUID.randomUUID();
        var request = new eg.mts.gsuif.dto.GenerationApiDtos.GenerateRequest(pageId, "OTHER", specification());
        assertThrows(GenerationValidationException.class, () -> service.generate(request, "esraa"));
        verifyNoInteractions(workflow, pages, versions, users);

        when(users.findByUsername("esraa")).thenReturn(Optional.of(mock(GsuifUser.class)));
        GsuifPage page = mock(GsuifPage.class);
        when(pages.findById(pageId)).thenReturn(Optional.of(page));
        assertThrows(ResourceNotFoundException.class, () -> service.generate(
                new eg.mts.gsuif.dto.GenerationApiDtos.GenerateRequest(pageId, "ENTITY", specification()), "esraa"));
        verifyNoInteractions(workflow);
    }

    @Test void generationUsesSelectedVersionAndReturnsPersistedStatusAndText() {
        UUID pageId = UUID.randomUUID(), versionId = UUID.randomUUID(), runId = UUID.randomUUID();
        GsuifPage page = mock(GsuifPage.class);
        MetadataVersion version = mock(MetadataVersion.class);
        GsuifUser user = mock(GsuifUser.class);
        GenerationRun run = mock(GenerationRun.class);
        when(users.findByUsername("esraa")).thenReturn(Optional.of(user));
        when(pages.findById(pageId)).thenReturn(Optional.of(page));
        when(page.getCurrentMetadataVersionId()).thenReturn(versionId);
        when(versions.findByIdAndPageId(versionId, pageId)).thenReturn(Optional.of(version));
        when(version.getId()).thenReturn(versionId);
        when(run.getId()).thenReturn(runId);
        when(run.getStatus()).thenReturn(GenerationRunStatus.SUCCESS);
        var generated = new GenerationResult.Artifact("Thing.java", "class Thing {}".getBytes(StandardCharsets.UTF_8),
                "abc", "1.0.0", "1.0.0");
        when(workflow.generateAndRecord(any(UUID.class), same(version), same(user), any(),
                eq(Set.of(Target.ENTITY)), eq("spring-angular"), eq(Map.of())))
                .thenReturn(new GenerationWorkflow.CompletedAttempt(new GenerationResult(List.of(generated), List.of()), run));
        var result = service.generate(new eg.mts.gsuif.dto.GenerationApiDtos.GenerateRequest(pageId, "ENTITY", specification()), "esraa");
        assertEquals(runId, result.runId());
        assertEquals("SUCCESS", result.status());
        assertEquals(versionId, result.metadataVersionId());
        assertEquals("class Thing {}", result.artifacts().getFirst().content());
    }

    @Test void runLookupReturnsRegistryAndPersistedBuildDiagnostics() {
        UUID id = UUID.randomUUID(), versionId = UUID.randomUUID(), artifactId = UUID.randomUUID();
        GenerationRun run = mock(GenerationRun.class);
        MetadataVersion version = mock(MetadataVersion.class);
        GeneratedArtifact artifact = mock(GeneratedArtifact.class);
        when(runs.findById(id)).thenReturn(Optional.of(run));
        when(run.getId()).thenReturn(id);
        when(run.getStatus()).thenReturn(GenerationRunStatus.BUILD_FAILED);
        when(run.getMetadataVersion()).thenReturn(version);
        when(version.getId()).thenReturn(versionId);
        when(run.getCreatedAt()).thenReturn(Instant.parse("2026-10-01T00:00:00Z"));
        when(run.getUpdatedAt()).thenReturn(Instant.parse("2026-10-01T00:00:01Z"));
        when(run.getCompileExitCode()).thenReturn(1);
        when(run.getCompileOutput()).thenReturn("compile error");
        when(run.getTestExitCode()).thenReturn((Integer) null);
        when(artifacts.findAllByGenerationRunIdOrderByRelativePathAsc(id)).thenReturn(List.of(artifact));
        when(artifact.getId()).thenReturn(artifactId);
        when(artifact.getRelativePath()).thenReturn("Thing.java");
        var detail = service.run(id);
        assertEquals("BUILD_FAILED", detail.status());
        assertEquals(versionId, detail.metadataVersionId());
        assertEquals(artifactId, detail.artifacts().getFirst().id());
        assertEquals("compile error", detail.compileOutput());
        assertNull(detail.testExitCode());
        assertThrows(ResourceNotFoundException.class, () -> service.run(UUID.randomUUID()));
    }

    private static GenerationSpecification specification() {
        return new GenerationSpecification("1.0.0", "com.example.fixture", null, null, null, null);
    }
}
