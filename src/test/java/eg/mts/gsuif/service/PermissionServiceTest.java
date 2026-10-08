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
import jakarta.validation.ConstraintViolationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.validation.beanvalidation.MethodValidationPostProcessor;

import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@SpringJUnitConfig(PermissionServiceTest.Config.class)
class PermissionServiceTest {

    @Autowired
    private GsuifPermissionRepository permissionRepository;

    @Autowired
    private GsuifRolePermissionRepository rolePermissionRepository;

    @Autowired
    private PermissionService permissionService;

    @BeforeEach
    void setUp() {
        reset(permissionRepository, rolePermissionRepository);
    }

    @Configuration(proxyBeanMethods = false)
    static class Config {

        @Bean
        static MethodValidationPostProcessor methodValidationPostProcessor() {
            return new MethodValidationPostProcessor();
        }

        @Bean
        GsuifPermissionRepository permissionRepository() {
            return org.mockito.Mockito.mock(GsuifPermissionRepository.class);
        }

        @Bean
        GsuifRolePermissionRepository rolePermissionRepository() {
            return org.mockito.Mockito.mock(GsuifRolePermissionRepository.class);
        }

        @Bean
        PermissionService permissionService(GsuifPermissionRepository permissionRepository,
                                            GsuifRolePermissionRepository rolePermissionRepository) {
            return new PermissionServiceImpl(permissionRepository, rolePermissionRepository);
        }
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

    @ParameterizedTest(name = "create rejects invalid {0}")
    @MethodSource("invalidCreateRequests")
    void create_whenDeclaredDtoConstraintIsViolated_rejectsBeforePersistence(
            String field, CreatePermissionRequest request) {
        assertThatThrownBy(() -> permissionService.create(request))
                .isInstanceOf(ConstraintViolationException.class)
                .hasMessageContaining(field);

        verifyNoInteractions(permissionRepository, rolePermissionRepository);
    }

    private static Stream<Arguments> invalidCreateRequests() {
        return Stream.of(
                Arguments.of("code", new CreatePermissionRequest(null, "Read catalog", null)),
                Arguments.of("code", new CreatePermissionRequest("", "Read catalog", null)),
                Arguments.of("code", new CreatePermissionRequest("   ", "Read catalog", null)),
                Arguments.of("code", new CreatePermissionRequest("c".repeat(51), "Read catalog", null)),
                Arguments.of("name", new CreatePermissionRequest("catalog.read", null, null)),
                Arguments.of("name", new CreatePermissionRequest("catalog.read", "", null)),
                Arguments.of("name", new CreatePermissionRequest("catalog.read", "   ", null)),
                Arguments.of("name", new CreatePermissionRequest("catalog.read", "n".repeat(201), null))
        );
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

    @ParameterizedTest(name = "update rejects invalid name: {0}")
    @MethodSource("invalidUpdateRequests")
    void update_whenDeclaredNameConstraintIsViolated_leavesPermissionUnchanged(
            String caseName, UpdatePermissionRequest request) {
        UUID id = UUID.randomUUID();
        GsuifPermission existing = permission("catalog.read", "Read catalog");
        existing.setDescription("Original description");

        assertThatThrownBy(() -> permissionService.update(id, request))
                .isInstanceOf(ConstraintViolationException.class)
                .hasMessageContaining("name");

        assertThat(existing.getName()).isEqualTo("Read catalog");
        assertThat(existing.getDescription()).isEqualTo("Original description");
        verifyNoInteractions(permissionRepository, rolePermissionRepository);
    }

    private static Stream<Arguments> invalidUpdateRequests() {
        return Stream.of(
                Arguments.of("null", new UpdatePermissionRequest(null, "Changed")),
                Arguments.of("blank", new UpdatePermissionRequest("", "Changed")),
                Arguments.of("whitespace-only", new UpdatePermissionRequest("   ", "Changed")),
                Arguments.of("oversized", new UpdatePermissionRequest("n".repeat(201), "Changed"))
        );
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
