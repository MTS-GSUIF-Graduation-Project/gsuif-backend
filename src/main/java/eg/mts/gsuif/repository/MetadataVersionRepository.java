package eg.mts.gsuif.repository;

import eg.mts.gsuif.entity.MetadataVersion;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.UUID;

@Repository
public interface MetadataVersionRepository extends JpaRepository<MetadataVersion, UUID> {
}
