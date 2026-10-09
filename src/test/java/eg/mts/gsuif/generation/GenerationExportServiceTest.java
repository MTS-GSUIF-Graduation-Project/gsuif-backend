package eg.mts.gsuif.generation;

import eg.mts.gsuif.entity.GeneratedArtifact;
import eg.mts.gsuif.entity.GenerationRun;
import eg.mts.gsuif.entity.GsuifPage;
import eg.mts.gsuif.entity.GsuifProject;
import eg.mts.gsuif.entity.MetadataVersion;
import eg.mts.gsuif.repository.GeneratedArtifactRepository;
import eg.mts.gsuif.repository.GsuifProjectRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.zip.ZipFile;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class GenerationExportServiceTest {
    @TempDir Path temp;

    @Test void bothDestinationsWriteOnlyVerifiedRunFilesAndKeepProjectScope() throws Exception {
        UUID projectId = UUID.randomUUID(), runId = UUID.randomUUID();
        String path = "src/main/java/Thing.java";
        byte[] bytes = "class Thing {}".getBytes();
        var runs = mock(GenerationRunService.class);
        var artifacts = mock(GeneratedArtifactRepository.class);
        var projects = mock(GsuifProjectRepository.class);
        var run = mock(GenerationRun.class);
        var version = mock(MetadataVersion.class);
        var page = mock(GsuifPage.class);
        var project = mock(GsuifProject.class);
        var artifact = mock(GeneratedArtifact.class);
        when(projects.existsById(projectId)).thenReturn(true);
        when(runs.findById(runId)).thenReturn(Optional.of(run));
        when(run.getMetadataVersion()).thenReturn(version);
        when(version.getPage()).thenReturn(page);
        when(page.getProject()).thenReturn(project);
        when(project.getId()).thenReturn(projectId);
        when(artifacts.findAllByGenerationRunIdOrderByRelativePathAsc(runId)).thenReturn(List.of(artifact));
        when(artifact.getRelativePath()).thenReturn(path);
        when(runs.readArtifact(runId, path)).thenReturn(bytes);
        when(runs.readResult(run)).thenReturn(new GenerationResult(List.of(
                new GenerationResult.Artifact(path, bytes, "hash", "1", "1")), List.of()));
        var service = new GenerationExportService(temp.toString(), runs, artifacts, projects);
        service.configure(projectId, "local", new GenerationExportService.DestinationConfig("LOCAL_FOLDER", "delivery/folder"));
        service.configure(projectId, "zip", new GenerationExportService.DestinationConfig("ZIP", "delivery/archive"));
        var folder = Path.of(java.net.URI.create(service.export(runId, "local").location()));
        assertEquals(temp.resolve(projectId.toString()).resolve("delivery/folder").resolve(runId.toString()), folder);
        assertArrayEquals(bytes, Files.readAllBytes(folder.resolve(path)));
        var zip = Path.of(java.net.URI.create(service.export(runId, "zip").location()));
        assertEquals(temp.resolve(projectId.toString()).resolve("delivery/archive").resolve(runId + ".zip"), zip);
        try (var archive = new ZipFile(zip.toFile())) {
            assertArrayEquals(bytes, archive.getInputStream(archive.getEntry(path)).readAllBytes());
        }
        assertThrows(IllegalStateException.class, () -> service.export(runId, "zip"));
        assertThrows(GenerationValidationException.class, () -> service.configure(projectId, "../escape",
                new GenerationExportService.DestinationConfig("ZIP", "safe")));
        assertThrows(GenerationValidationException.class, () -> service.configure(projectId, "unsafe",
                new GenerationExportService.DestinationConfig("ZIP", "../escape")));
        assertThrows(eg.mts.gsuif.exception.ResourceNotFoundException.class,
                () -> service.export(runId, "other"));
        UUID anotherProject = UUID.randomUUID();
        when(projects.existsById(anotherProject)).thenReturn(true);
        service.configure(anotherProject, "other", new GenerationExportService.DestinationConfig("ZIP", "delivery/archive"));
        assertThrows(eg.mts.gsuif.exception.ResourceNotFoundException.class,
                () -> service.export(runId, "other"));
        assertThrows(eg.mts.gsuif.exception.ResourceNotFoundException.class,
                () -> service.export(UUID.randomUUID(), "local"));
        verify(runs, times(3)).readArtifact(runId, path);
    }
}
