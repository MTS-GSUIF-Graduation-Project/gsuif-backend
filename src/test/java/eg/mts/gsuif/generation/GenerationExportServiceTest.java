package eg.mts.gsuif.generation;

import eg.mts.gsuif.entity.GeneratedArtifact;
import eg.mts.gsuif.entity.GenerationRun;
import eg.mts.gsuif.entity.GenerationRunStatus;
import eg.mts.gsuif.entity.GsuifPage;
import eg.mts.gsuif.entity.GsuifProject;
import eg.mts.gsuif.entity.MetadataVersion;
import eg.mts.gsuif.repository.GeneratedArtifactRepository;
import eg.mts.gsuif.repository.GsuifProjectRepository;
import eg.mts.gsuif.security.EntityPermissionChecker;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

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

    @Test void directPackageNeedsCommittedBackendAndNeverConfiguresExport() throws Exception {
        UUID runId = UUID.randomUUID();
        var runs = mock(GenerationRunService.class);
        var artifacts = mock(GeneratedArtifactRepository.class);
        var projects = mock(GsuifProjectRepository.class);
        var permissions = mock(EntityPermissionChecker.class);
        var run = mock(GenerationRun.class);
        when(runs.findById(runId)).thenReturn(Optional.of(run));
        var service = new GenerationExportService(temp.toString(), runs, artifacts, projects, permissions);

        assertThrows(IllegalStateException.class, () -> service.directPackage(runId));
        when(run.getStatus()).thenReturn(GenerationRunStatus.SUCCESS);
        when(run.getToolName()).thenReturn("TemplateOnlyProvider");
        assertThrows(IllegalStateException.class, () -> service.directPackage(runId));
        when(run.getToolName()).thenReturn(BackendPackageIntegrity.TOOL);
        byte[] source = "class Thing {}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        when(runs.readBackendPackage(eq(run), eq(64L * 1024 * 1024), eq(512)))
                .thenReturn(new GenerationResult(List.of(new GenerationResult.Artifact(
                        "src/main/java/Thing.java", source, BackendPackageIntegrity.hash(source), "1", "1")), List.of()));
        var direct = service.directPackage(runId);
        assertEquals(runId + ".zip", direct.fileName());
        var output = new java.io.ByteArrayOutputStream();
        direct.writeTo(output);
        try (var zip = new java.util.zip.ZipInputStream(new java.io.ByteArrayInputStream(output.toByteArray()))) {
            assertEquals("src/main/java/Thing.java", zip.getNextEntry().getName());
            assertArrayEquals(source, zip.readAllBytes());
            assertNull(zip.getNextEntry());
        }
        assertFalse(Files.exists(temp.resolve(runId + ".zip")));
        var closed = new java.util.concurrent.atomic.AtomicBoolean();
        var disconnected = new java.io.OutputStream() {
            @Override public void write(int value) throws java.io.IOException { throw new java.io.IOException("client closed"); }
            @Override public void close() { closed.set(true); }
        };
        assertThrows(java.io.IOException.class, () -> direct.writeTo(disconnected));
        assertTrue(closed.get());
        verifyNoInteractions(artifacts, projects);

        doThrow(new AccessDeniedException("denied"))
                .when(permissions).requireEntityAccess(eq("GsuifPage"), any());
        assertThrows(AccessDeniedException.class, () -> service.directPackage(runId));
        verify(runs, times(1)).readBackendPackage(eq(run), anyLong(), anyInt());
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

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
        when(run.getStatus()).thenReturn(GenerationRunStatus.SUCCESS);
        when(run.getMetadataVersion()).thenReturn(version);
        when(version.getPage()).thenReturn(page);
        when(page.getProject()).thenReturn(project);
        when(project.getId()).thenReturn(projectId);
        when(artifacts.findAllByGenerationRunIdOrderByRelativePathAsc(runId)).thenReturn(List.of(artifact));
        when(artifact.getRelativePath()).thenReturn(path);
        when(runs.readArtifact(runId, path)).thenReturn(bytes);
        when(runs.readResult(run)).thenReturn(new GenerationResult(List.of(
                new GenerationResult.Artifact(path, bytes, "hash", "1", "1")), List.of()));
        var service = new GenerationExportService(temp.toString(), runs, artifacts, projects,
                mock(EntityPermissionChecker.class));
        service.configure(projectId, "local", new GenerationExportService.DestinationConfig("LOCAL_FOLDER", "delivery/folder"));
        service.configure(projectId, "zip", new GenerationExportService.DestinationConfig("ZIP", "delivery/archive"));
        assertThrows(eg.mts.gsuif.exception.ResourceNotFoundException.class,
                () -> service.download(runId, "zip"));
        var folder = Path.of(java.net.URI.create(service.export(runId, "local").location()));
        assertEquals(temp.resolve(projectId.toString()).resolve("delivery/folder").resolve(runId.toString()), folder);
        assertArrayEquals(bytes, Files.readAllBytes(folder.resolve(path)));
        var exported = service.export(runId, "zip");
        var zip = Path.of(java.net.URI.create(exported.location()));
        assertEquals("/api/v1/generation/runs/" + runId + "/exports/zip/download", exported.downloadUrl());
        assertEquals(runId + ".zip", service.download(runId, "zip").fileName());
        assertArrayEquals(Files.readAllBytes(zip), service.download(runId, "zip").bytes());
        byte[] originalZip = Files.readAllBytes(zip);
        Files.write(zip, java.util.Arrays.copyOf(originalZip, originalZip.length + 1));
        assertThrows(IllegalStateException.class, () -> service.download(runId, "zip"));
        Files.write(zip, originalZip);
        byte[] centralTampered = originalZip.clone();
        int central = -1;
        for (int i = 0; i < centralTampered.length - 4; i++) {
            if (centralTampered[i] == 'P' && centralTampered[i + 1] == 'K'
                    && centralTampered[i + 2] == 1 && centralTampered[i + 3] == 2) {
                central = i;
                break;
            }
        }
        assertTrue(central >= 0);
        centralTampered[central + 46] ^= 1; // Central filename only; local entry and payload stay intact.
        Files.write(zip, centralTampered);
        assertThrows(IllegalStateException.class, () -> service.download(runId, "zip"));
        Files.write(zip, originalZip);
        centralTampered = originalZip.clone();
        centralTampered[central + 38] ^= 1; // Central external attributes only.
        Files.write(zip, centralTampered);
        assertThrows(IllegalStateException.class, () -> service.download(runId, "zip"));
        Files.write(zip, originalZip);
        assertEquals(temp.resolve(projectId.toString()).resolve("delivery/archive").resolve(runId + ".zip"), zip);
        try (var archive = new ZipFile(zip.toFile())) {
            assertArrayEquals(bytes, archive.getInputStream(archive.getEntry(path)).readAllBytes());
        }
        assertThrows(eg.mts.gsuif.exception.ResourceNotFoundException.class,
                () -> service.download(runId, "missing"));
        assertThrows(eg.mts.gsuif.exception.ResourceNotFoundException.class,
                () -> service.download(runId, "local"));
        Path altered = temp.resolve("altered.zip");
        new ZipExportDestination().export(altered, java.util.Map.of(path, "tampered".getBytes()));
        Files.copy(altered, zip, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        assertThrows(IllegalStateException.class, () -> service.download(runId, "zip"));
        when(run.getStatus()).thenReturn(GenerationRunStatus.BUILD_FAILED);
        assertThrows(IllegalStateException.class, () -> service.download(runId, "zip"));
        when(run.getStatus()).thenReturn(GenerationRunStatus.SUCCESS);
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

    @Test void invalidDestinationTypesReturnValidationErrorsWithoutWritingConfiguration() {
        UUID projectId = UUID.randomUUID();
        var projects = mock(GsuifProjectRepository.class);
        when(projects.existsById(projectId)).thenReturn(true);
        var service = new GenerationExportService(temp.toString(), mock(GenerationRunService.class),
                mock(GeneratedArtifactRepository.class), projects, mock(EntityPermissionChecker.class));
        assertThrows(GenerationValidationException.class, () -> service.configure(projectId, "null-config", null));
        assertThrows(GenerationValidationException.class, () -> service.configure(projectId, "null-type",
                new GenerationExportService.DestinationConfig(null, "safe/path")));
        assertThrows(GenerationValidationException.class, () -> service.configure(projectId, "blank-type",
                new GenerationExportService.DestinationConfig(" ", "safe/path")));
        assertThrows(GenerationValidationException.class, () -> service.configure(projectId, "unsupported-type",
                new GenerationExportService.DestinationConfig("REMOTE", "safe/path")));
        Path configDirectory = temp.resolve("config").resolve(projectId.toString());
        assertFalse(Files.exists(configDirectory.resolve("null-config.json")));
        assertFalse(Files.exists(configDirectory.resolve("null-type.json")));
        assertFalse(Files.exists(configDirectory.resolve("blank-type.json")));
        assertFalse(Files.exists(configDirectory.resolve("unsupported-type.json")));
    }

    @Test void configureWithoutProjectAccessDoesNotCreateConfigurationFile() {
        SecurityContextHolder.getContext().setAuthentication(
                UsernamePasswordAuthenticationToken.authenticated("no-project-access", "n/a", List.of()));
        UUID projectId = UUID.randomUUID();
        var runs = mock(GenerationRunService.class);
        var artifacts = mock(GeneratedArtifactRepository.class);
        var projects = mock(GsuifProjectRepository.class);
        var permissionChecker = mock(EntityPermissionChecker.class);
        doThrow(new AccessDeniedException("Entity access denied: GsuifProject"))
                .when(permissionChecker).requireEntityAccess(eq("GsuifProject"), any());
        var service = new GenerationExportService(temp.toString(), runs, artifacts, projects, permissionChecker);

        assertThrows(AccessDeniedException.class, () -> service.configure(projectId, "local",
                new GenerationExportService.DestinationConfig("LOCAL_FOLDER", "delivery/folder")));

        assertFalse(Files.exists(temp.resolve("config").resolve(projectId.toString()).resolve("local.json")));
        verifyNoInteractions(runs, artifacts, projects);
    }

    @Test void exportDeniedWhenProjectAccessDenied() throws Exception {
        SecurityContextHolder.getContext().setAuthentication(
                UsernamePasswordAuthenticationToken.authenticated("no-project-access", "n/a", List.of()));
        var runs = mock(GenerationRunService.class);
        var artifacts = mock(GeneratedArtifactRepository.class);
        var projects = mock(GsuifProjectRepository.class);
        var permissionChecker = mock(EntityPermissionChecker.class);
        doThrow(new AccessDeniedException("Entity access denied: GsuifProject"))
                .when(permissionChecker).requireEntityAccess(eq("GsuifProject"), any());
        var service = new GenerationExportService(temp.toString(), runs, artifacts, projects, permissionChecker);

        assertThrows(AccessDeniedException.class, () -> service.export(UUID.randomUUID(), "local"));
        assertThrows(AccessDeniedException.class, () -> service.download(UUID.randomUUID(), "local"));

        try (var entries = Files.list(temp)) {
            assertTrue(entries.findAny().isEmpty());
        }
        verifyNoInteractions(runs, artifacts, projects);
    }

    @Test void exportDeniedWhenPageAccessDenied() throws Exception {
        SecurityContextHolder.getContext().setAuthentication(
                UsernamePasswordAuthenticationToken.authenticated("no-page-access", "n/a", List.of()));
        var runs = mock(GenerationRunService.class);
        var artifacts = mock(GeneratedArtifactRepository.class);
        var projects = mock(GsuifProjectRepository.class);
        var permissionChecker = mock(EntityPermissionChecker.class);
        doThrow(new AccessDeniedException("Entity access denied: GsuifPage"))
                .when(permissionChecker).requireEntityAccess(eq("GsuifPage"), any());
        var service = new GenerationExportService(temp.toString(), runs, artifacts, projects, permissionChecker);

        assertThrows(AccessDeniedException.class, () -> service.export(UUID.randomUUID(), "local"));

        verify(runs, never()).findById(any());
        try (var entries = Files.list(temp)) {
            assertTrue(entries.findAny().isEmpty());
        }
    }

    @Test void exportDeniedWhenMetadataVersionAccessDenied() throws Exception {
        SecurityContextHolder.getContext().setAuthentication(
                UsernamePasswordAuthenticationToken.authenticated("no-version-access", "n/a", List.of()));
        var runs = mock(GenerationRunService.class);
        var artifacts = mock(GeneratedArtifactRepository.class);
        var projects = mock(GsuifProjectRepository.class);
        var permissionChecker = mock(EntityPermissionChecker.class);
        doThrow(new AccessDeniedException("Entity access denied: MetadataVersion"))
                .when(permissionChecker).requireEntityAccess(eq("MetadataVersion"), any());
        var service = new GenerationExportService(temp.toString(), runs, artifacts, projects, permissionChecker);

        assertThrows(AccessDeniedException.class, () -> service.export(UUID.randomUUID(), "local"));

        verify(runs, never()).findById(any());
        try (var entries = Files.list(temp)) {
            assertTrue(entries.findAny().isEmpty());
        }
    }

    @Test void exportSuccessWithAllPermissions() throws Exception {
        SecurityContextHolder.getContext().setAuthentication(
                UsernamePasswordAuthenticationToken.authenticated("admin", "n/a", List.of()));
        UUID projectId = UUID.randomUUID();
        UUID runId = UUID.randomUUID();
        String path = "src/main/java/Thing.java";
        byte[] bytes = "class Thing {}".getBytes();
        var runs = mock(GenerationRunService.class);
        var artifacts = mock(GeneratedArtifactRepository.class);
        var projects = mock(GsuifProjectRepository.class);
        var permissionChecker = mock(EntityPermissionChecker.class);
        var run = mock(GenerationRun.class);
        var version = mock(MetadataVersion.class);
        var page = mock(GsuifPage.class);
        var project = mock(GsuifProject.class);
        var artifact = mock(GeneratedArtifact.class);
        when(projects.existsById(projectId)).thenReturn(true);
        when(runs.findById(runId)).thenReturn(Optional.of(run));
        when(run.getStatus()).thenReturn(GenerationRunStatus.SUCCESS);
        when(run.getMetadataVersion()).thenReturn(version);
        when(version.getPage()).thenReturn(page);
        when(page.getProject()).thenReturn(project);
        when(project.getId()).thenReturn(projectId);
        when(artifacts.findAllByGenerationRunIdOrderByRelativePathAsc(runId)).thenReturn(List.of(artifact));
        when(artifact.getRelativePath()).thenReturn(path);
        when(runs.readArtifact(runId, path)).thenReturn(bytes);
        when(runs.readResult(run)).thenReturn(new GenerationResult(List.of(
                new GenerationResult.Artifact(path, bytes, "hash", "1", "1")), List.of()));
        var service = new GenerationExportService(temp.toString(), runs, artifacts, projects, permissionChecker);

        service.configure(projectId, "local",
                new GenerationExportService.DestinationConfig("LOCAL_FOLDER", "delivery/folder"));
        clearInvocations(permissionChecker);
        Path folder = Path.of(java.net.URI.create(service.export(runId, "local").location()));

        assertTrue(Files.exists(folder.resolve(path)));
        assertArrayEquals(bytes, Files.readAllBytes(folder.resolve(path)));
        var ordered = inOrder(permissionChecker);
        ordered.verify(permissionChecker).requireEntityAccess(eq("GsuifProject"),
                same(SecurityContextHolder.getContext().getAuthentication()));
        ordered.verify(permissionChecker).requireEntityAccess(eq("GsuifPage"),
                same(SecurityContextHolder.getContext().getAuthentication()));
        ordered.verify(permissionChecker).requireEntityAccess(eq("MetadataVersion"),
                same(SecurityContextHolder.getContext().getAuthentication()));
    }
}
