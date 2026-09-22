package eg.mts.gsuif.repository;

import eg.mts.gsuif.entity.GsuifPage;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.UUID;

/**
 * Repository interface for managing {@link GsuifPage} entities.
 */
@Repository
public interface GsuifPageRepository extends JpaRepository<GsuifPage, UUID> {

    boolean existsByProjectId(UUID projectId);
}
