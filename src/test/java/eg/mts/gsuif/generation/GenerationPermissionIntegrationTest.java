package eg.mts.gsuif.generation;

import eg.mts.gsuif.dto.GenerationApiDtos;
import eg.mts.gsuif.entity.GenerationRun;
import eg.mts.gsuif.entity.GenerationRunStatus;
import eg.mts.gsuif.entity.GeneratedArtifact;
import eg.mts.gsuif.entity.GsuifPage;
import eg.mts.gsuif.entity.GsuifPermission;
import eg.mts.gsuif.entity.GsuifProject;
import eg.mts.gsuif.entity.GsuifRole;
import eg.mts.gsuif.entity.GsuifRolePermission;
import eg.mts.gsuif.entity.GsuifRolePermissionId;
import eg.mts.gsuif.entity.GsuifUser;
import eg.mts.gsuif.entity.GsuifUserRole;
import eg.mts.gsuif.entity.GsuifUserRoleId;
import eg.mts.gsuif.entity.MetadataVersion;
import eg.mts.gsuif.repository.GenerationRunRepository;
import eg.mts.gsuif.repository.GsuifPermissionRepository;
import eg.mts.gsuif.repository.GsuifRolePermissionRepository;
import eg.mts.gsuif.repository.GsuifRoleRepository;
import eg.mts.gsuif.repository.GsuifUserRepository;
import eg.mts.gsuif.repository.GsuifUserRoleRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.anonymous;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = "gsuif.generation.export-root=target/generation-permission-exports")
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class GenerationPermissionIntegrationTest {

    private static final Path EXPORT_ROOT = Path.of("target", "generation-permission-exports")
            .toAbsolutePath().normalize();

    private final MockMvc mvc;
    private final GenerationExportService exportService;

    @Autowired private GenerationApiService service;
    @Autowired private GenerationRunRepository runRepository;
    @Autowired private GsuifUserRepository userRepository;
    @Autowired private GsuifRoleRepository roleRepository;
    @Autowired private GsuifUserRoleRepository userRoleRepository;
    @Autowired private GsuifPermissionRepository permissionRepository;
    @Autowired private GsuifRolePermissionRepository rolePermissionRepository;
    @Autowired private EntityManager entityManager;
    @MockitoBean private GenerationWorkflow workflow;
    @MockitoBean private GenerationRunService runs;

    @Autowired
    GenerationPermissionIntegrationTest(MockMvc mvc, GenerationExportService exportService) {
        this.mvc = mvc;
        this.exportService = exportService;
    }

    @AfterEach
    void clearSecurityContext() throws IOException {
        SecurityContextHolder.clearContext();
        if (Files.exists(EXPORT_ROOT)) {
            try (var paths = Files.walk(EXPORT_ROOT)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                    Files.deleteIfExists(path);
                }
            }
        }
    }

    @Test
    void generate_withoutPageAccess_deniesBeforeCreatingRun() {
        Fixture fixture = fixture("generation-no-page", "ROLE_GENERATION_NO_PAGE");
        long runsBefore = runRepository.count();

        assertThatThrownBy(() -> service.generate(request(fixture.pageId()), fixture.username()))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessage("Entity access denied: GsuifPage");

        assertThat(runRepository.count()).isEqualTo(runsBefore);
        verifyNoInteractions(workflow);
    }

    @Test
    void generate_withoutMetadataVersionAccess_deniesBeforeCreatingRun() {
        Fixture fixture = fixture("generation-no-version", "ROLE_GENERATION_NO_VERSION",
                "ENTITY_ACCESS:GsuifPage");
        long runsBefore = runRepository.count();

        assertThatThrownBy(() -> service.generate(request(fixture.pageId()), fixture.username()))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessage("Entity access denied: MetadataVersion");

        assertThat(runRepository.count()).isEqualTo(runsBefore);
        verifyNoInteractions(workflow);
    }

    @Test
    void generate_withPageAndMetadataVersionAccess_reachesGenerationWorkflow() {
        Fixture fixture = fixture("generation-allowed", "ROLE_GENERATION_ALLOWED",
                "ENTITY_ACCESS:GsuifPage", "ENTITY_ACCESS:MetadataVersion");
        GenerationRun run = mock(GenerationRun.class);
        when(run.getId()).thenReturn(UUID.randomUUID());
        when(run.getStatus()).thenReturn(GenerationRunStatus.SUCCESS);
        when(workflow.generateAndRecord(any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(new GenerationWorkflow.CompletedAttempt(
                        new GenerationResult(List.of(), List.of()), run));

        GenerationApiDtos.CreatedRun result = service.generate(request(fixture.pageId()), fixture.username());

        assertThat(result.metadataVersionId()).isEqualTo(fixture.versionId());
        verify(workflow).generateAndRecord(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void download_withoutPageAccess_deniesBeforeReturningBytes() {
        fixture("download-no-page", "ROLE_DOWNLOAD_NO_PAGE");

        assertThatThrownBy(() -> service.download(UUID.randomUUID(), "src/main/java/Thing.java"))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessage("Entity access denied: GsuifPage");

        verifyNoInteractions(runs);
    }

    @Test
    void download_withoutMetadataVersionAccess_isDenied() {
        fixture("download-no-version", "ROLE_DOWNLOAD_NO_VERSION",
                "ENTITY_ACCESS:GsuifPage");

        assertThatThrownBy(() -> service.download(UUID.randomUUID(), "src/main/java/Thing.java"))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessage("Entity access denied: MetadataVersion");

        verifyNoInteractions(runs);
    }

    @Test
    void artifactHistory_withoutPageAccess_deniesBeforeDisclosingIds() {
        fixture("history-no-page", "ROLE_HISTORY_NO_PAGE");

        assertThatThrownBy(() -> service.artifactHistory("src/main/java/Thing.java"))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessage("Entity access denied: GsuifPage");

        verifyNoInteractions(runs);
    }

    @Test
    void admin_canDownloadAndReadArtifactHistory() {
        authenticate("admin", "ROLE_ADMIN");
        UUID runId = UUID.randomUUID();
        UUID versionId = UUID.randomUUID();
        String path = "src/main/java/Thing.java";
        byte[] bytes = "class Thing {}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        GenerationRun run = mock(GenerationRun.class);
        MetadataVersion version = mock(MetadataVersion.class);
        GeneratedArtifact artifact = mock(GeneratedArtifact.class);
        when(runs.findById(runId)).thenReturn(Optional.of(run));
        when(runs.findFile(runId, path)).thenReturn(Optional.of(artifact));
        when(runs.readArtifact(runId, path)).thenReturn(bytes);
        when(runs.findFileHistory(path)).thenReturn(List.of(artifact));
        when(artifact.getId()).thenReturn(UUID.randomUUID());
        when(artifact.getRelativePath()).thenReturn(path);
        when(artifact.getGenerationRun()).thenReturn(run);
        when(run.getId()).thenReturn(runId);
        when(run.getMetadataVersion()).thenReturn(version);
        when(version.getId()).thenReturn(versionId);

        assertThat(service.download(runId, path).bytes()).isEqualTo(bytes);
        assertThat(service.artifactHistory(path))
                .singleElement()
                .satisfies(trace -> {
                    assertThat(trace.runId()).isEqualTo(runId);
                    assertThat(trace.metadataVersionId()).isEqualTo(versionId);
                });
    }

    @Test
    void exportDeniedWithPageDenied_returns403() throws Exception {
        Fixture fixture = fixture("export-no-page", "ROLE_EXPORT_NO_PAGE",
                "ENTITY_ACCESS:GsuifProject", "ENTITY_ACCESS:MetadataVersion");
        exportService.configure(fixture.projectId(), "local",
                new GenerationExportService.DestinationConfig("LOCAL_FOLDER", "delivery/folder"));
        var requestAuthentication = SecurityContextHolder.getContext().getAuthentication();
        UUID runId = UUID.randomUUID();

        mvc.perform(post("/api/v1/generation/runs/{id}/exports/{name}", runId, "local")
                        .with(authentication(requestAuthentication)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.statusCode").value(403))
                .andExpect(jsonPath("$.status").value("FORBIDDEN"));

        assertThat(EXPORT_ROOT.resolve(fixture.projectId().toString())).doesNotExist();
        verify(runs, never()).findById(any());
    }

    @Test
    void exportDeniedWithMetadataVersionDenied_returns403() throws Exception {
        Fixture fixture = fixture("export-no-version", "ROLE_EXPORT_NO_VERSION",
                "ENTITY_ACCESS:GsuifProject", "ENTITY_ACCESS:GsuifPage");
        exportService.configure(fixture.projectId(), "local",
                new GenerationExportService.DestinationConfig("LOCAL_FOLDER", "delivery/folder"));
        var requestAuthentication = SecurityContextHolder.getContext().getAuthentication();
        UUID runId = UUID.randomUUID();

        mvc.perform(post("/api/v1/generation/runs/{id}/exports/{name}", runId, "local")
                        .with(authentication(requestAuthentication)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.statusCode").value(403))
                .andExpect(jsonPath("$.status").value("FORBIDDEN"));

        assertThat(EXPORT_ROOT.resolve(fixture.projectId().toString())).doesNotExist();
        verify(runs, never()).findById(any());
    }

    @Test
    void zipDownloadAnonymousIsRejectedByEnforcedSecurity() throws Exception {
        mvc.perform(get("/api/v1/generation/runs/{id}/exports/zip/download", UUID.randomUUID())
                        .with(anonymous()))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(runs);
    }

    @Test
    void directPackageAnonymousIsRejectedBeforeLookup() throws Exception {
        mvc.perform(get("/api/v1/generation/runs/{id}/package.zip", UUID.randomUUID())
                        .with(anonymous()))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(runs);
    }

    @Test
    void directPackageNeedsProjectPageAndMetadataGrants() throws Exception {
        for (String missing : List.of("GsuifProject", "GsuifPage", "MetadataVersion")) {
            String username = "direct-" + missing.toLowerCase() + "-" + UUID.randomUUID().toString().substring(0, 6);
            String role = "ROLE_DIRECT_" + UUID.randomUUID().toString().substring(0, 8);
            var allowed = List.of("GsuifProject", "GsuifPage", "MetadataVersion").stream()
                    .filter(name -> !name.equals(missing)).map(name -> "ENTITY_ACCESS:" + name).toArray(String[]::new);
            fixture(username, role, allowed);
            mvc.perform(get("/api/v1/generation/runs/{id}/package.zip", UUID.randomUUID())
                            .with(authentication(SecurityContextHolder.getContext().getAuthentication())))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.statusCode").value(403));
        }
        verify(runs, never()).findById(any());
    }

    @Test
    void zipDownloadRequiresProjectPageAndMetadataGrants() throws Exception {
        assertZipDownloadDenied("zip-no-project", "ROLE_ZIP_NO_PROJECT",
                "ENTITY_ACCESS:GsuifPage", "ENTITY_ACCESS:MetadataVersion");
        assertZipDownloadDenied("zip-no-page", "ROLE_ZIP_NO_PAGE",
                "ENTITY_ACCESS:GsuifProject", "ENTITY_ACCESS:MetadataVersion");
        assertZipDownloadDenied("zip-no-version", "ROLE_ZIP_NO_VERSION",
                "ENTITY_ACCESS:GsuifProject", "ENTITY_ACCESS:GsuifPage");
        verify(runs, never()).findById(any());
    }

    private void assertZipDownloadDenied(String username, String role, String... grants) throws Exception {
        fixture(username, role, grants);
        var requestAuthentication = SecurityContextHolder.getContext().getAuthentication();
        mvc.perform(get("/api/v1/generation/runs/{id}/exports/zip/download", UUID.randomUUID())
                        .with(authentication(requestAuthentication)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.statusCode").value(403));
    }

    @Test
    void authorizedZipDownloadReturnsVerifiedArchive() throws Exception {
        Fixture fixture = fixture("zip-allowed", "ROLE_ZIP_ALLOWED",
                "ENTITY_ACCESS:GsuifProject", "ENTITY_ACCESS:GsuifPage", "ENTITY_ACCESS:MetadataVersion");
        exportService.configure(fixture.projectId(), "zip",
                new GenerationExportService.DestinationConfig("ZIP", "delivery/archive"));
        UUID runId = UUID.randomUUID();
        String path = "src/main/java/Thing.java";
        byte[] bytes = "class Thing {}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        Path zip = EXPORT_ROOT.resolve(fixture.projectId().toString())
                .resolve("delivery/archive").resolve(runId + ".zip");
        Files.createDirectories(zip.getParent());
        new ZipExportDestination().export(zip, java.util.Map.of(path, bytes));
        GenerationRun run = mock(GenerationRun.class);
        when(run.getStatus()).thenReturn(GenerationRunStatus.SUCCESS);
        when(run.getMetadataVersion()).thenReturn(entityManager.find(MetadataVersion.class, fixture.versionId()));
        when(runs.findById(runId)).thenReturn(Optional.of(run));
        when(runs.readResult(run)).thenReturn(new GenerationResult(List.of(
                new GenerationResult.Artifact(path, bytes, "hash", "1", "1")), List.of()));

        mvc.perform(get("/api/v1/generation/runs/{id}/exports/zip/download", runId)
                        .with(authentication(SecurityContextHolder.getContext().getAuthentication())))
                .andExpect(status().isOk())
                .andExpect(content().contentType("application/zip"))
                .andExpect(header().string("Content-Disposition", "attachment; filename=\"" + runId + ".zip\""))
                .andExpect(content().bytes(Files.readAllBytes(zip)));
    }

    @Test
    void runDetailsDeniedWithPageDenied_returns403() throws Exception {
        fixture("run-no-page", "ROLE_RUN_NO_PAGE", "ENTITY_ACCESS:MetadataVersion");
        var requestAuthentication = SecurityContextHolder.getContext().getAuthentication();
        UUID runId = UUID.randomUUID();

        mvc.perform(get("/api/v1/generation/runs/{id}", runId)
                        .with(authentication(requestAuthentication)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.statusCode").value(403));

        verify(runs, never()).findById(any());
    }

    @Test
    void runDetailsSuccessWithBothPermissions() throws Exception {
        authenticate("admin", "ROLE_ADMIN");
        var requestAuthentication = SecurityContextHolder.getContext().getAuthentication();
        UUID runId = UUID.randomUUID();
        UUID versionId = UUID.randomUUID();
        GenerationRun run = mock(GenerationRun.class);
        MetadataVersion version = mock(MetadataVersion.class);
        when(runs.findById(runId)).thenReturn(Optional.of(run));
        when(run.getId()).thenReturn(runId);
        when(run.getStatus()).thenReturn(GenerationRunStatus.SUCCESS);
        when(run.getCreatedAt()).thenReturn(Instant.parse("2026-10-15T00:00:00Z"));
        when(run.getUpdatedAt()).thenReturn(Instant.parse("2026-10-15T00:00:01Z"));
        when(run.getMetadataVersion()).thenReturn(version);
        when(version.getId()).thenReturn(versionId);

        mvc.perform(get("/api/v1/generation/runs/{id}", runId)
                        .with(authentication(requestAuthentication)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.body.id").value(runId.toString()));
    }

    private Fixture fixture(String username, String roleName, String... permissionCodes) {
        GsuifProject project = new GsuifProject();
        project.setName("Generation permission " + UUID.randomUUID());
        entityManager.persist(project);
        GsuifPage page = new GsuifPage();
        page.setProject(project);
        page.setName("Generation page");
        entityManager.persist(page);
        MetadataVersion version = new MetadataVersion();
        version.setPage(page);
        version.setVersion(1);
        version.setSchemaVersion("1.0.0");
        version.setSnapshot("{\"components\":[],\"apiBindings\":[]}");
        entityManager.persist(version);
        page.setCurrentMetadataVersionId(version.getId());

        createUserWithPermissions(username, roleName, permissionCodes);
        authenticate(username, roleName);
        entityManager.flush();
        return new Fixture(username, project.getId(), page.getId(), version.getId());
    }

    private void createUserWithPermissions(String username, String roleName, String... permissionCodes) {
        GsuifUser user = new GsuifUser();
        user.setUsername(username);
        user.setPasswordHash("test-only");
        user = userRepository.save(user);
        GsuifRole role = new GsuifRole();
        role.setName(roleName);
        role = roleRepository.save(role);
        GsuifUserRole userRole = new GsuifUserRole();
        userRole.setId(new GsuifUserRoleId(user.getId(), role.getId()));
        userRole.setUser(user);
        userRole.setRole(role);
        userRoleRepository.save(userRole);
        for (String code : permissionCodes) {
            GsuifPermission permission = permissionRepository.findByCode(code).orElseThrow();
            GsuifRolePermission assignment = new GsuifRolePermission();
            assignment.setId(new GsuifRolePermissionId(role.getId(), permission.getId()));
            assignment.setRole(role);
            assignment.setPermission(permission);
            rolePermissionRepository.save(assignment);
        }
    }

    private void authenticate(String username, String roleName) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(username, "n/a",
                        List.of(new SimpleGrantedAuthority(roleName))));
    }

    private GenerationApiDtos.GenerateRequest request(UUID pageId) {
        return new GenerationApiDtos.GenerateRequest(pageId, "ENTITY",
                new GenerationSpecification("1.0.0", "com.example.fixture", null, null, null, null));
    }

    private record Fixture(String username, UUID projectId, UUID pageId, UUID versionId) { }
}
