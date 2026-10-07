package eg.mts.gsuif.service;

import eg.mts.gsuif.dto.CreatePermissionRequest;
import eg.mts.gsuif.dto.PermissionDto;
import eg.mts.gsuif.dto.UpdatePermissionRequest;
import eg.mts.gsuif.entity.GsuifPermission;
import eg.mts.gsuif.exception.DuplicateResourceException;
import eg.mts.gsuif.exception.PermissionDeletionException;
import eg.mts.gsuif.exception.ResourceNotFoundException;
import eg.mts.gsuif.repository.GsuifPermissionRepository;
import eg.mts.gsuif.repository.GsuifRolePermissionRepository;
import eg.mts.gsuif.service.impl.PermissionServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PermissionServiceTest {

    @Mock
    private GsuifPermissionRepository permissionRepository;

    @Mock
    private GsuifRolePermissionRepository rolePermissionRepository;

    private PermissionService permissionService;

    @BeforeEach
    void setUp() {
        permissionService = new PermissionServiceImpl(permissionRepository, rolePermissionRepository);
    }

    @Test
    void create_whenCodeIsUnique_savesPermission() {
        CreatePermissionRequest request = new CreatePermissionRequest(
                "catalog.read", "Read catalog", "Allows catalog reads");
        when(permissionRepository.existsByCode("catalog.read")).thenReturn(false);
        when(permissionRepository.save(any(GsuifPermission.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        PermissionDto result = permissionService.create(request);

        assertThat(result.code()).isEqualTo("catalog.read");
        assertThat(result.name()).isEqualTo("Read catalog");
        assertThat(result.description()).isEqualTo("Allows catalog reads");
    }

    @Test
    void create_whenCodeExists_rejectsDuplicate() {
        CreatePermissionRequest request = new CreatePermissionRequest("catalog.read", "Read catalog", null);
        when(permissionRepository.existsByCode("catalog.read")).thenReturn(true);

        assertThatThrownBy(() -> permissionService.create(request))
                .isInstanceOf(DuplicateResourceException.class)
                .extracting("fieldName")
                .isEqualTo("code");

        verify(permissionRepository, never()).save(any());
    }

    @Test
    void update_changesOnlyMutableFields() {
        UUID id = UUID.randomUUID();
        GsuifPermission permission = permission("catalog.read", "Read catalog");
        when(permissionRepository.findById(id)).thenReturn(Optional.of(permission));
        when(permissionRepository.save(permission)).thenReturn(permission);

        PermissionDto result = permissionService.update(
                id, new UpdatePermissionRequest("View catalog", "Updated description"));

        assertThat(result.code()).isEqualTo("catalog.read");
        assertThat(result.name()).isEqualTo("View catalog");
        assertThat(result.description()).isEqualTo("Updated description");
    }

    @Test
    void delete_whenUnassigned_deletesPermission() {
        UUID id = UUID.randomUUID();
        GsuifPermission permission = permission("catalog.read", "Read catalog");
        when(permissionRepository.findById(id)).thenReturn(Optional.of(permission));
        when(rolePermissionRepository.existsByPermission(permission)).thenReturn(false);

        permissionService.delete(id);

        verify(permissionRepository).delete(permission);
    }

    @Test
    void delete_whenAssigned_rejectsWithoutDeletingAssignments() {
        UUID id = UUID.randomUUID();
        GsuifPermission permission = permission("catalog.read", "Read catalog");
        when(permissionRepository.findById(id)).thenReturn(Optional.of(permission));
        when(rolePermissionRepository.existsByPermission(permission)).thenReturn(true);

        assertThatThrownBy(() -> permissionService.delete(id))
                .isInstanceOf(PermissionDeletionException.class)
                .hasMessageContaining("assigned to one or more roles");

        verify(permissionRepository, never()).delete(any());
    }

    @Test
    void getById_whenMissing_reportsNotFound() {
        UUID id = UUID.randomUUID();
        when(permissionRepository.findById(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> permissionService.getById(id))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining(id.toString());
    }

    private GsuifPermission permission(String code, String name) {
        GsuifPermission permission = new GsuifPermission();
        permission.setCode(code);
        permission.setName(name);
        return permission;
    }
}
