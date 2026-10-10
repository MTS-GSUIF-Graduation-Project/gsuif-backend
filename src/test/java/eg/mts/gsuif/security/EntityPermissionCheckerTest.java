package eg.mts.gsuif.security;

import eg.mts.gsuif.entity.GsuifPermission;
import eg.mts.gsuif.entity.GsuifRole;
import eg.mts.gsuif.entity.GsuifRolePermission;
import eg.mts.gsuif.entity.GsuifUser;
import eg.mts.gsuif.entity.GsuifUserRole;
import eg.mts.gsuif.repository.GsuifRolePermissionRepository;
import eg.mts.gsuif.repository.GsuifUserRepository;
import eg.mts.gsuif.repository.GsuifUserRoleRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class EntityPermissionCheckerTest {

    @Mock private GsuifUserRepository userRepository;
    @Mock private GsuifUserRoleRepository userRoleRepository;
    @Mock private GsuifRolePermissionRepository rolePermissionRepository;
    @Mock private Authentication authentication;

    private GsuifUser user;
    private GsuifRole role;

    @BeforeEach
    void setUp() {
        user = new GsuifUser();
        user.setUsername("member");
        role = new GsuifRole();
        role.setName("ROLE_MEMBER");
    }

    @Test
    void requireEntityAccess_passesWhenAnyRoleHasEntityPermission() {
        EntityPermissionChecker checker = checker("prod");
        arrangePermissions("ENTITY_ACCESS:GsuifProject");

        assertThatCode(() -> checker.requireEntityAccess("GsuifProject", authentication))
                .doesNotThrowAnyException();
    }

    @Test
    void requireEntityAccess_throwsWhenNoRoleHasEntityPermission() {
        EntityPermissionChecker checker = checker("prod");
        arrangePermissions("FIELD_DENY:GsuifProject:createdBy");

        assertThatThrownBy(() -> checker.requireEntityAccess("GsuifProject", authentication))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessage("Entity access denied: GsuifProject");
    }

    @Test
    void requireEntityAccess_skipsAllRepositoriesInDevMode() {
        EntityPermissionChecker checker = checker("dev");

        checker.requireEntityAccess("GsuifProject", null);

        verifyNoInteractions(userRepository, userRoleRepository, rolePermissionRepository, authentication);
    }

    @Test
    void getDeniedFields_returnsOnlyMatchingEntityFieldNames() {
        EntityPermissionChecker checker = checker("prod");
        arrangePermissions(
                "ENTITY_ACCESS:GsuifProject",
                "FIELD_DENY:GsuifProject:createdBy",
                "FIELD_DENY:GsuifProject:description",
                "FIELD_DENY:WorkOrder:createdBy");

        assertThat(checker.getDeniedFields("GsuifProject", authentication))
                .containsExactlyInAnyOrder("createdBy", "description");
    }

    @Test
    void getDeniedFields_returnsEmptySetWithoutRepositoryCallsInDevMode() {
        EntityPermissionChecker checker = checker("DEV");

        assertThat(checker.getDeniedFields("GsuifProject", null)).isEmpty();
        verifyNoInteractions(userRepository, userRoleRepository, rolePermissionRepository, authentication);
    }

    private EntityPermissionChecker checker(String mode) {
        return new EntityPermissionChecker(userRepository, userRoleRepository, rolePermissionRepository, mode);
    }

    private void arrangePermissions(String... codes) {
        when(authentication.isAuthenticated()).thenReturn(true);
        when(authentication.getName()).thenReturn("member");
        when(userRepository.findByUsername("member")).thenReturn(Optional.of(user));
        GsuifUserRole userRole = new GsuifUserRole();
        userRole.setUser(user);
        userRole.setRole(role);
        when(userRoleRepository.findByUser(user)).thenReturn(List.of(userRole));
        List<GsuifRolePermission> assignments = java.util.Arrays.stream(codes)
                .map(this::assignment)
                .toList();
        when(rolePermissionRepository.findByRole(role)).thenReturn(assignments);
    }

    private GsuifRolePermission assignment(String code) {
        GsuifPermission permission = new GsuifPermission();
        permission.setCode(code);
        GsuifRolePermission assignment = new GsuifRolePermission();
        assignment.setRole(role);
        assignment.setPermission(permission);
        return assignment;
    }
}
