package eg.mts.gsuif.generation;

import com.fasterxml.jackson.databind.ObjectMapper;
import eg.mts.gsuif.dto.GenerationApiDtos;
import eg.mts.gsuif.dto.CreateMetadataVersionRequest;
import eg.mts.gsuif.entity.GsuifPage;
import eg.mts.gsuif.entity.GsuifProject;
import eg.mts.gsuif.entity.GsuifUser;
import eg.mts.gsuif.repository.GeneratedArtifactRepository;
import eg.mts.gsuif.repository.GsuifProjectRepository;
import eg.mts.gsuif.service.MetadataVersionService;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.zip.ZipFile;

import static org.junit.jupiter.api.Assertions.*;

/** Runs the documented fixture through real generation, build, registry, download, and exports. */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class WomsDocumentedRunIntegrationTest {
    @TempDir Path temp;
    @Autowired EntityManager entities;
    @Autowired GenerationApiService api;
    @Autowired GenerationRunService runs;
    @Autowired GeneratedArtifactRepository artifacts;
    @Autowired GsuifProjectRepository projects;
    @Autowired MetadataVersionService metadataVersions;

    @Test void documentedFixtureBuildsAndItsSavedRunDownloadsAndExports() throws Exception {
        var mapper = new ObjectMapper();
        var metadata = mapper.readTree(Files.readString(Path.of("demo/woms-sample-metadata.json")));
        var request = mapper.readTree(Files.readString(Path.of("demo/woms-generation-request.json")));
        GsuifProject project = new GsuifProject();
        project.setName("WOMS " + UUID.randomUUID());
        entities.persist(project);
        GsuifPage page = new GsuifPage();
        page.setProject(project);
        page.setName("Asset tickets");
        entities.persist(page);
        GsuifUser user = new GsuifUser();
        user.setUsername("woms-" + UUID.randomUUID());
        user.setPasswordHash("hash");
        entities.persist(user);
        entities.flush();
        var savedVersion = metadataVersions.create(page.getId(), new CreateMetadataVersionRequest(
                metadata.path("schemaVersion").asText(),
                new tools.jackson.databind.ObjectMapper().readTree(metadata.path("snapshot").toString())));
        assertEquals(5, savedVersion.snapshot().path("apiBindings").size());
        assertEquals(savedVersion.id(), metadataVersions.getCurrent(page.getId()).id());

        var specification = mapper.treeToValue(request.path("specification"), GenerationSpecification.class);
        var created = api.generate(new GenerationApiDtos.GenerateRequest(page.getId(), "FULL_STACK", specification),
                user.getUsername());
        assertEquals(savedVersion.id(), created.metadataVersionId());
        var details = api.run(created.runId());
        assertEquals("SUCCESS", created.status(), details.compileOutput() + "\n" + details.testOutput());
        assertEquals("SUCCESS", details.status(), details.compileOutput() + "\n" + details.testOutput());
        assertEquals(0, details.compileExitCode());
        assertEquals(0, details.testExitCode());
        assertEquals(savedVersion.id(), details.metadataVersionId());
        assertFalse(details.artifacts().isEmpty());

        String path = details.artifacts().getFirst().relativePath();
        byte[] downloaded = api.download(created.runId(), path).bytes();
        assertArrayEquals(runs.readArtifact(created.runId(), path), downloaded);
        assertTrue(api.artifactHistory(path).stream().anyMatch(trace ->
                trace.runId().equals(created.runId()) && trace.metadataVersionId().equals(savedVersion.id())));

        var exports = new GenerationExportService(temp.toString(), runs, artifacts, projects);
        exports.configure(project.getId(), "local", new GenerationExportService.DestinationConfig("LOCAL_FOLDER", "delivery/folder"));
        exports.configure(project.getId(), "zip", new GenerationExportService.DestinationConfig("ZIP", "delivery/archive"));
        Path folder = Path.of(URI.create(exports.export(created.runId(), "local").location()));
        assertArrayEquals(downloaded, Files.readAllBytes(folder.resolve(path)));
        Path zip = Path.of(URI.create(exports.export(created.runId(), "zip").location()));
        try (var archive = new ZipFile(zip.toFile())) {
            assertArrayEquals(downloaded, archive.getInputStream(archive.getEntry(path)).readAllBytes());
        }
        assertEquals("SUCCESS", api.run(created.runId()).status());
    }
}
