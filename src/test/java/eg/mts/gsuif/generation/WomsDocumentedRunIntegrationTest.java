package eg.mts.gsuif.generation;

import com.fasterxml.jackson.databind.ObjectMapper;
import eg.mts.gsuif.dto.GenerationApiDtos;
import eg.mts.gsuif.dto.CreateMetadataVersionRequest;
import eg.mts.gsuif.entity.GsuifPage;
import eg.mts.gsuif.entity.GsuifProject;
import eg.mts.gsuif.entity.GsuifRole;
import eg.mts.gsuif.entity.GsuifRolePermission;
import eg.mts.gsuif.entity.GsuifRolePermissionId;
import eg.mts.gsuif.entity.GsuifUser;
import eg.mts.gsuif.entity.GsuifUserRole;
import eg.mts.gsuif.entity.GsuifUserRoleId;
import eg.mts.gsuif.entity.MetadataVersion;
import eg.mts.gsuif.repository.GeneratedArtifactRepository;
import eg.mts.gsuif.repository.GsuifPermissionRepository;
import eg.mts.gsuif.repository.GsuifProjectRepository;
import eg.mts.gsuif.security.EntityPermissionChecker;
import eg.mts.gsuif.service.MetadataVersionService;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

import java.net.URI;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
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
    @Autowired GsuifPermissionRepository permissions;
    @Autowired MetadataVersionService metadataVersions;
    @Autowired TransactionTemplate transactions;
    @Value("${gsuif.generation.artifact-root}") String artifactRoot;
    private Fixture persistedFixture;

    @AfterEach void removeCommittedFixture() throws IOException {
        if (persistedFixture == null) return;
        List<UUID> runIds = transactions.execute(status -> {
            List<UUID> ids = entities.createNativeQuery(
                            "SELECT id FROM gsuif_generation_run WHERE metadata_version_id = :versionId")
                    .setParameter("versionId", persistedFixture.versionId().toString())
                    .getResultStream().map(id -> UUID.fromString(id.toString())).toList();
            entities.createNativeQuery("DELETE FROM gsuif_generated_artifact WHERE generation_run_id IN "
                            + "(SELECT id FROM gsuif_generation_run WHERE metadata_version_id = :versionId)")
                    .setParameter("versionId", persistedFixture.versionId().toString()).executeUpdate();
            entities.createNativeQuery("DELETE FROM gsuif_generation_run WHERE metadata_version_id = :versionId")
                    .setParameter("versionId", persistedFixture.versionId().toString()).executeUpdate();
            entities.createNativeQuery("UPDATE gsuif_page SET current_metadata_version_id = NULL WHERE id = :id")
                    .setParameter("id", persistedFixture.pageId().toString()).executeUpdate();
            entities.createNativeQuery("DELETE FROM gsuif_metadata_version WHERE id = :id")
                    .setParameter("id", persistedFixture.versionId().toString()).executeUpdate();
            entities.createNativeQuery("DELETE FROM gsuif_page WHERE id = :id")
                    .setParameter("id", persistedFixture.pageId().toString()).executeUpdate();
            entities.createNativeQuery("DELETE FROM gsuif_project WHERE id = :id")
                    .setParameter("id", persistedFixture.projectId().toString()).executeUpdate();
            entities.createNativeQuery("DELETE FROM gsuif_user_role WHERE user_id = :id")
                    .setParameter("id", persistedFixture.userId().toString()).executeUpdate();
            entities.createNativeQuery("DELETE FROM gsuif_role_permission WHERE role_id = :id")
                    .setParameter("id", persistedFixture.roleId().toString()).executeUpdate();
            entities.createNativeQuery("DELETE FROM gsuif_role WHERE id = :id")
                    .setParameter("id", persistedFixture.roleId().toString()).executeUpdate();
            entities.createNativeQuery("DELETE FROM gsuif_user WHERE id = :id")
                    .setParameter("id", persistedFixture.userId().toString()).executeUpdate();
            return ids;
        });
        Path root = Path.of(artifactRoot).toAbsolutePath().normalize();
        if (Files.isSymbolicLink(root)) throw new IOException("Artifact root is a symbolic link");
        for (UUID runId : runIds) {
            Path directory = root.resolve(runId.toString());
            if (!Files.exists(directory)) continue;
            if (Files.isSymbolicLink(directory)) throw new IOException("Run artifact directory is a symbolic link");
            try (var files = Files.walk(directory)) {
                for (Path file : files.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(file);
            }
        }
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test void documentedFixtureBuildsAndItsSavedRunDownloadsAndExports(
            @Autowired EntityPermissionChecker permissionChecker) throws Exception {
        var mapper = new ObjectMapper();
        var request = mapper.readTree(Files.readString(Path.of("demo/woms-generation-request.json")));
        Fixture fixture = createCommittedFixture();

        var specification = mapper.treeToValue(request.path("specification"), GenerationSpecification.class);
        var created = api.generate(new GenerationApiDtos.GenerateRequest(fixture.pageId(), "FULL_STACK", specification),
                fixture.username());
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

        var exports = new GenerationExportService(temp.toString(), runs, artifacts, projects, permissionChecker);
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

    @Test void cleanupFindsReservedAttemptWithoutCompletedRunId() throws Exception {
        Fixture fixture = createCommittedFixture();
        UUID runId = transactions.execute(status -> runs.reserveAttempt(UUID.randomUUID(),
                entities.find(MetadataVersion.class, fixture.versionId()),
                entities.find(GsuifUser.class, fixture.userId()), "fixture-fingerprint").getId());
        assertTrue(runs.findById(runId).isPresent());

        removeCommittedFixture();
        persistedFixture = null;
        entities.clear();
        assertTrue(runs.findById(runId).isEmpty());
    }

    private Fixture createCommittedFixture() throws Exception {
        var metadata = new ObjectMapper().readTree(Files.readString(Path.of("demo/woms-sample-metadata.json")));
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
            GsuifRole role = new GsuifRole();
            role.setName("ROLE_WOMS_" + UUID.randomUUID());
            entities.persist(role);
            entities.flush();
            GsuifUserRole membership = new GsuifUserRole();
            membership.setId(new GsuifUserRoleId(user.getId(), role.getId()));
            membership.setUser(user);
            membership.setRole(role);
            entities.persist(membership);
            for (String code : List.of("ENTITY_ACCESS:GsuifProject", "ENTITY_ACCESS:GsuifPage",
                    "ENTITY_ACCESS:MetadataVersion")) {
                var permission = permissions.findByCode(code).orElseThrow();
                GsuifRolePermission grant = new GsuifRolePermission();
                grant.setId(new GsuifRolePermissionId(role.getId(), permission.getId()));
                grant.setRole(role);
                grant.setPermission(permission);
                entities.persist(grant);
            }
            entities.flush();
            SecurityContextHolder.getContext().setAuthentication(
                    new UsernamePasswordAuthenticationToken(user.getUsername(), null,
                            List.of(new SimpleGrantedAuthority(role.getName()))));
            var savedVersion = metadataVersions.create(page.getId(), new CreateMetadataVersionRequest(
                    metadata.path("schemaVersion").asText(),
                    new tools.jackson.databind.ObjectMapper().readTree(metadata.path("snapshot").toString())));
            assertEquals(5, savedVersion.snapshot().path("apiBindings").size());
            assertEquals(savedVersion.id(), metadataVersions.getCurrent(page.getId()).id());
            return new Fixture(project.getId(), page.getId(), user.getId(), role.getId(), user.getUsername(), savedVersion.id());
        });
        persistedFixture = fixture;
        return fixture;
    }

    private record Fixture(UUID projectId, UUID pageId, UUID userId, UUID roleId, String username, UUID versionId) { }
}
