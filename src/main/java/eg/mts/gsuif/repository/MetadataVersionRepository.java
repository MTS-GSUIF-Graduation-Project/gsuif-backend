package eg.mts.gsuif.repository;

import eg.mts.gsuif.entity.MetadataVersion;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.UUID;

/**
 * Repository interface for managing {@link MetadataVersion} entities.
 */
@Repository
public interface MetadataVersionRepository extends JpaRepository<MetadataVersion, UUID> {

    boolean existsByPageId(UUID pageId);

    org.springframework.data.domain.Page<MetadataVersion> findAllByPageId(UUID pageId, org.springframework.data.domain.Pageable pageable);

    java.util.Optional<MetadataVersion> findFirstByPageIdOrderByVersionDesc(UUID pageId);

    java.util.Optional<MetadataVersion> findByIdAndPageId(UUID id, UUID pageId);
}
