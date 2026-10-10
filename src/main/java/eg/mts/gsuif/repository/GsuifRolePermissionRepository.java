package eg.mts.gsuif.repository;

import eg.mts.gsuif.entity.GsuifPermission;
import eg.mts.gsuif.entity.GsuifRole;
import eg.mts.gsuif.entity.GsuifRolePermission;
import eg.mts.gsuif.entity.GsuifRolePermissionId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface GsuifRolePermissionRepository extends JpaRepository<GsuifRolePermission, GsuifRolePermissionId> {
    List<GsuifRolePermission> findByRole(GsuifRole role);
    List<GsuifRolePermission> findByPermission(GsuifPermission permission);
    boolean existsByPermission(GsuifPermission permission);
}
