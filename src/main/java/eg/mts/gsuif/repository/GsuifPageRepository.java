package eg.mts.gsuif.repository;

import eg.mts.gsuif.entity.GsuifPage;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import jakarta.persistence.LockModeType;

import java.util.Optional;
import java.util.UUID;

/**
 * Repository interface for managing {@link GsuifPage} entities.
 */
@Repository
public interface GsuifPageRepository extends JpaRepository<GsuifPage, UUID> {

    boolean existsByProjectId(UUID projectId);

    Page<GsuifPage> findAllByProjectId(UUID projectId, Pageable pageable);

    Optional<GsuifPage> findByIdAndProjectId(UUID id, UUID projectId);

    boolean existsByProjectIdAndName(UUID projectId, String name);

    boolean existsByProjectIdAndNameAndIdNot(UUID projectId, String name, UUID id);

    boolean existsByProjectIdAndRoute(UUID projectId, String route);

    boolean existsByProjectIdAndRouteAndIdNot(UUID projectId, String route, UUID id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from GsuifPage p where p.id = :id")
    Optional<GsuifPage> findByIdWithLock(@Param("id") UUID id);
}
