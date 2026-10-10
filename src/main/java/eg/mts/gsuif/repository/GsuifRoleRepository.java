package eg.mts.gsuif.repository;

import eg.mts.gsuif.entity.GsuifRole;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface GsuifRoleRepository extends JpaRepository<GsuifRole, UUID> {
    Optional<GsuifRole> findByName(String name);
}
