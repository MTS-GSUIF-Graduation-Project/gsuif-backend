package eg.mts.gsuif.security;

import eg.mts.gsuif.dto.ProjectDto;
import eg.mts.gsuif.dto.CreateMetadataVersionRequest;
import eg.mts.gsuif.entity.GsuifPage;
import eg.mts.gsuif.entity.GsuifPermission;
import eg.mts.gsuif.entity.GsuifProject;
import eg.mts.gsuif.entity.MetadataVersion;
import eg.mts.gsuif.entity.GsuifRole;
import eg.mts.gsuif.entity.GsuifRolePermission;
import eg.mts.gsuif.entity.GsuifRolePermissionId;
import eg.mts.gsuif.entity.GsuifUser;
import eg.mts.gsuif.entity.GsuifUserRole;
import eg.mts.gsuif.entity.GsuifUserRoleId;
import eg.mts.gsuif.repository.GsuifPermissionRepository;
import eg.mts.gsuif.repository.GsuifProjectRepository;
import eg.mts.gsuif.repository.GsuifPageRepository;
import eg.mts.gsuif.repository.MetadataVersionRepository;
import eg.mts.gsuif.repository.GsuifRolePermissionRepository;
import eg.mts.gsuif.repository.GsuifRoleRepository;
import eg.mts.gsuif.repository.GsuifUserRepository;
import eg.mts.gsuif.repository.GsuifUserRoleRepository;
import eg.mts.gsuif.service.GsuifProjectService;
import eg.mts.gsuif.service.MetadataVersionService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class EntityPermissionIntegrationTest {

    @Autowired private GsuifProjectService projectService;
    @Autowired private MetadataVersionService metadataVersionService;
    @Autowired private GsuifProjectRepository projectRepository;
    @Autowired private GsuifPageRepository pageRepository;
    @Autowired private MetadataVersionRepository metadataVersionRepository;
    @Autowired private GsuifUserRepository userRepository;
    @Autowired private GsuifRoleRepository roleRepository;
    @Autowired private GsuifUserRoleRepository userRoleRepository;
    @Autowired private GsuifPermissionRepository permissionRepository;
    @Autowired private GsuifRolePermissionRepository rolePermissionRepository;

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void projectRead_withoutEntityAccess_throwsAccessDeniedException() {
        createUserWithPermissions("scrum61-denied", "ROLE_SCRUM61_DENIED");
        authenticate("scrum61-denied", "ROLE_SCRUM61_DENIED");

        assertThatThrownBy(() -> projectService.getAll(PageRequest.of(0, 20)))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessage("Entity access denied: GsuifProject");
    }

    @Test
    void projectRead_adminFullAccessPathStillWorks() {
        authenticate("admin", "ROLE_ADMIN");

        assertThatCode(() -> projectService.getAll(PageRequest.of(0, 20)))
                .doesNotThrowAnyException();
    }

    @Test
    void projectRead_withFieldDeny_nullsOnlyDeniedField() {
        createUserWithPermissions(
                "scrum61-field-denied",
                "ROLE_SCRUM61_FIELD_DENIED",
                "ENTITY_ACCESS:GsuifProject",
                "FIELD_DENY:GsuifProject:createdBy");
        authenticate("scrum61-field-denied", "ROLE_SCRUM61_FIELD_DENIED");

        GsuifProject project = new GsuifProject();
        project.setName("SCRUM-61 field filtering");
        project.setDescription("visible");
        projectRepository.saveAndFlush(project);

        ProjectDto result = projectService.getById(project.getId());

        assertThat(result.createdBy()).isNull();
        assertThat(result.name()).isEqualTo("SCRUM-61 field filtering");
        assertThat(result.description()).isEqualTo("visible");
    }

    @Test
    void metadataCreate_withoutPageAccess_persistsNothingAndPreservesPagePointer() {
        createUserWithPermissions(
                "scrum61-metadata-create",
                "ROLE_SCRUM61_METADATA_CREATE",
                "ENTITY_ACCESS:MetadataVersion");
        authenticate("scrum61-metadata-create", "ROLE_SCRUM61_METADATA_CREATE");
        VersionFixture fixture = versionFixture(false);
        long versionsBefore = metadataVersionRepository.count();
        UUID pointerBefore = pageRepository.findById(fixture.pageId()).orElseThrow()
                .getCurrentMetadataVersionId();

        assertThatThrownBy(() -> metadataVersionService.create(
                fixture.pageId(),
                new CreateMetadataVersionRequest(
                        "1.0.0", tools.jackson.databind.node.JsonNodeFactory.instance.objectNode())))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessage("Entity access denied: GsuifPage");

        assertThat(metadataVersionRepository.count()).isEqualTo(versionsBefore);
        assertThat(pageRepository.findById(fixture.pageId()).orElseThrow().getCurrentMetadataVersionId())
                .isEqualTo(pointerBefore);
    }

    @Test
    void selectCurrent_withoutPageAccess_preservesPagePointer() {
        createUserWithPermissions(
                "scrum61-metadata-select",
                "ROLE_SCRUM61_METADATA_SELECT",
                "ENTITY_ACCESS:MetadataVersion");
        authenticate("scrum61-metadata-select", "ROLE_SCRUM61_METADATA_SELECT");
        VersionFixture fixture = versionFixture(true);
        UUID pointerBefore = pageRepository.findById(fixture.pageId()).orElseThrow()
                .getCurrentMetadataVersionId();

        assertThatThrownBy(() -> metadataVersionService.selectCurrent(
                fixture.pageId(), fixture.alternateVersionId()))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessage("Entity access denied: GsuifPage");

        assertThat(pageRepository.findById(fixture.pageId()).orElseThrow().getCurrentMetadataVersionId())
                .isEqualTo(pointerBefore);
    }

    private VersionFixture versionFixture(boolean includeAlternate) {
        GsuifProject project = new GsuifProject();
        project.setName("SCRUM-61 metadata permission " + UUID.randomUUID());
        project = projectRepository.save(project);
        GsuifPage page = new GsuifPage();
        page.setProject(project);
        page.setName("Protected page");
        page = pageRepository.save(page);
        MetadataVersion current = metadataVersion(page, 1);
        page.setCurrentMetadataVersionId(current.getId());
        pageRepository.saveAndFlush(page);
        MetadataVersion alternate = includeAlternate ? metadataVersion(page, 2) : current;
        return new VersionFixture(page.getId(), alternate.getId());
    }

    private MetadataVersion metadataVersion(GsuifPage page, int number) {
        MetadataVersion version = new MetadataVersion();
        version.setPage(page);
        version.setVersion(number);
        version.setSchemaVersion("1.0.0");
        version.setSnapshot("{\"components\":[],\"apiBindings\":[]}");
        return metadataVersionRepository.saveAndFlush(version);
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
            GsuifPermission permission = permissionRepository.findByCode(code).orElseGet(() -> {
                GsuifPermission created = new GsuifPermission();
                created.setCode(code);
                created.setName("Test " + code);
                return permissionRepository.save(created);
            });

            GsuifRolePermission assignment = new GsuifRolePermission();
            assignment.setId(new GsuifRolePermissionId(role.getId(), permission.getId()));
            assignment.setRole(role);
            assignment.setPermission(permission);
            rolePermissionRepository.save(assignment);
        }
    }

    private void authenticate(String username, String role) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(
                        username,
                        "n/a",
                        List.of(new SimpleGrantedAuthority(role))));
    }

    private record VersionFixture(UUID pageId, UUID alternateVersionId) { }
}
