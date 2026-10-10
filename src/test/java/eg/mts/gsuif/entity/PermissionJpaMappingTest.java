package eg.mts.gsuif.entity;

import eg.mts.gsuif.repository.GsuifRolePermissionRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class PermissionJpaMappingTest {

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private GsuifRolePermissionRepository rolePermissionRepository;

    @Test
    void persistsAuditedManyToManyRolePermissionAssignments() {
        GsuifRole administrator = role("ADMIN");
        GsuifRole viewer = role("VIEWER");
        GsuifPermission read = permission("catalog.read", "Read catalog");
        GsuifPermission write = permission("catalog.write", "Write catalog");

        entityManager.persist(administrator);
        entityManager.persist(viewer);
        entityManager.persist(read);
        entityManager.persist(write);

        entityManager.persist(assignment(administrator, read));
        entityManager.persist(assignment(administrator, write));
        entityManager.persist(assignment(viewer, read));
        entityManager.flush();
        entityManager.clear();

        GsuifRole reloadedAdministrator = entityManager.find(GsuifRole.class, administrator.getId());
        GsuifPermission reloadedRead = entityManager.find(GsuifPermission.class, read.getId());

        assertThat(rolePermissionRepository.findByRole(reloadedAdministrator)).hasSize(2);
        assertThat(rolePermissionRepository.findByPermission(reloadedRead)).hasSize(2);

        GsuifRolePermission reloadedAssignment = entityManager.find(
                GsuifRolePermission.class,
                new GsuifRolePermissionId(administrator.getId(), read.getId()));
        assertThat(reloadedAssignment.getCreatedAt()).isNotNull();
        assertThat(reloadedAssignment.getCreatedBy()).isNotBlank();
    }

    @Test
    void rejectsDuplicatePermissionCode() {
        entityManager.persist(permission("catalog.read", "Read catalog"));
        entityManager.flush();
        entityManager.persist(permission("catalog.read", "Read catalog duplicate"));

        assertThatThrownBy(() -> entityManager.flush())
                .isInstanceOf(PersistenceException.class);
    }

    @Test
    void codeIsNotUpdatedAfterCreation() {
        GsuifPermission permission = permission("catalog.read", "Read catalog");
        entityManager.persist(permission);
        entityManager.flush();

        permission.setCode("catalog.changed");
        entityManager.flush();
        entityManager.clear();

        assertThat(entityManager.find(GsuifPermission.class, permission.getId()).getCode())
                .isEqualTo("catalog.read");
    }

    private GsuifRole role(String name) {
        GsuifRole role = new GsuifRole();
        role.setName(name);
        return role;
    }

    private GsuifPermission permission(String code, String name) {
        GsuifPermission permission = new GsuifPermission();
        permission.setCode(code);
        permission.setName(name);
        return permission;
    }

    private GsuifRolePermission assignment(GsuifRole role, GsuifPermission permission) {
        GsuifRolePermission assignment = new GsuifRolePermission();
        assignment.setId(new GsuifRolePermissionId(role.getId(), permission.getId()));
        assignment.setRole(role);
        assignment.setPermission(permission);
        return assignment;
    }
}
