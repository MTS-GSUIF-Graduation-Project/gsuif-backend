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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.zip.ZipFile;

import static org.junit.jupiter.api.Assertions.*;

/** Runs the documented fixture through real generation, build, registry, download, and exports. */
@SpringBootTest
@ActiveProfiles("test")
class WomsDocumentedRunIntegrationTest {
    @TempDir Path temp;
    @Autowired EntityManager entities;
    @Autowired GenerationApiService api;
    @Autowired GenerationRunService runs;
    @Autowired GeneratedArtifactRepository artifacts;
    @Autowired GsuifProjectRepository projects;
    @Autowired MetadataVersionService metadataVersions;
    @Autowired TransactionTemplate transactions;
    private Fixture persistedFixture;
    private UUID recordedRunId;

    @AfterEach void removeCommittedFixture() {
        if (persistedFixture == null || recordedRunId == null) return;
        transactions.executeWithoutResult(status -> {
            entities.createNativeQuery("DELETE FROM gsuif_generated_artifact WHERE generation_run_id = :id")
                    .setParameter("id", recordedRunId.toString()).executeUpdate();
            entities.createNativeQuery("DELETE FROM gsuif_generation_run WHERE id = :id")
                    .setParameter("id", recordedRunId.toString()).executeUpdate();
            entities.createNativeQuery("UPDATE gsuif_page SET current_metadata_version_id = NULL WHERE id = :id")
                    .setParameter("id", persistedFixture.pageId().toString()).executeUpdate();
            entities.createNativeQuery("DELETE FROM gsuif_metadata_version WHERE id = :id")
                    .setParameter("id", persistedFixture.versionId().toString()).executeUpdate();
            entities.createNativeQuery("DELETE FROM gsuif_page WHERE id = :id")
                    .setParameter("id", persistedFixture.pageId().toString()).executeUpdate();
            entities.createNativeQuery("DELETE FROM gsuif_project WHERE id = :id")
                    .setParameter("id", persistedFixture.projectId().toString()).executeUpdate();
            entities.createNativeQuery("DELETE FROM gsuif_user WHERE id = :id")
                    .setParameter("id", persistedFixture.userId().toString()).executeUpdate();
        });
    }

    @Test void documentedFixtureBuildsAndItsSavedRunDownloadsAndExports() throws Exception {
        var mapper = new ObjectMapper();
        var metadata = mapper.readTree(Files.readString(Path.of("demo/woms-sample-metadata.json")));
        var request = mapper.readTree(Files.readString(Path.of("demo/woms-generation-request.json")));
        // A reserved attempt commits before generation and therefore requires committed metadata/user inputs.
        Fixture fixture = transactions.execute(status -> {
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
            return new Fixture(project.getId(), page.getId(), user.getId(), user.getUsername(), savedVersion.id());
        });
        persistedFixture = fixture;

        var specification = mapper.treeToValue(request.path("specification"), GenerationSpecification.class);
        var created = api.generate(new GenerationApiDtos.GenerateRequest(fixture.pageId(), "FULL_STACK", specification),
                fixture.username());
        recordedRunId = created.runId();
        assertEquals(fixture.versionId(), created.metadataVersionId());
        var details = api.run(created.runId());
        assertEquals("SUCCESS", created.status(), details.compileOutput() + "\n" + details.testOutput());
        assertEquals("SUCCESS", details.status(), details.compileOutput() + "\n" + details.testOutput());
        assertEquals(0, details.compileExitCode());
        assertEquals(0, details.testExitCode());
        assertEquals(fixture.versionId(), details.metadataVersionId());
        assertFalse(details.artifacts().isEmpty());

        String path = details.artifacts().getFirst().relativePath();
        byte[] downloaded = api.download(created.runId(), path).bytes();
        assertArrayEquals(runs.readArtifact(created.runId(), path), downloaded);
        assertTrue(api.artifactHistory(path).stream().anyMatch(trace ->
                trace.runId().equals(created.runId()) && trace.metadataVersionId().equals(fixture.versionId())));

        var exports = new GenerationExportService(temp.toString(), runs, artifacts, projects);
        exports.configure(fixture.projectId(), "local", new GenerationExportService.DestinationConfig("LOCAL_FOLDER", "delivery/folder"));
        exports.configure(fixture.projectId(), "zip", new GenerationExportService.DestinationConfig("ZIP", "delivery/archive"));
        Path folder = Path.of(URI.create(transactions.execute(status -> exports.export(created.runId(), "local")).location()));
        assertArrayEquals(downloaded, Files.readAllBytes(folder.resolve(path)));
        Path zip = Path.of(URI.create(transactions.execute(status -> exports.export(created.runId(), "zip")).location()));
        try (var archive = new ZipFile(zip.toFile())) {
            assertArrayEquals(downloaded, archive.getInputStream(archive.getEntry(path)).readAllBytes());
        }
        assertEquals("SUCCESS", api.run(created.runId()).status());
    }

    private record Fixture(UUID projectId, UUID pageId, UUID userId, String username, UUID versionId) { }
}
