package eg.mts.gsuif.generation;

import eg.mts.gsuif.dto.GenerationApiDtos;
import eg.mts.gsuif.entity.GenerationRun;
import eg.mts.gsuif.entity.GenerationRunStatus;
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
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class GenerationPermissionIntegrationTest {

    @Autowired private GenerationApiService service;
    @Autowired private GenerationRunRepository runRepository;
    @Autowired private GsuifUserRepository userRepository;
    @Autowired private GsuifRoleRepository roleRepository;
    @Autowired private GsuifUserRoleRepository userRoleRepository;
    @Autowired private GsuifPermissionRepository permissionRepository;
    @Autowired private GsuifRolePermissionRepository rolePermissionRepository;
    @Autowired private EntityManager entityManager;
    @MockitoBean private GenerationWorkflow workflow;

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
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
        return new Fixture(username, page.getId(), version.getId());
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

    private record Fixture(String username, UUID pageId, UUID versionId) { }
}
