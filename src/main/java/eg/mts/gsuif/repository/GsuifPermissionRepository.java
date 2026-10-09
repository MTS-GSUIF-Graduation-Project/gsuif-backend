package eg.mts.gsuif.repository;

import eg.mts.gsuif.entity.GsuifPermission;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface GsuifPermissionRepository extends JpaRepository<GsuifPermission, UUID> {
    Optional<GsuifPermission> findByCode(String code);
    boolean existsByCode(String code);
}
