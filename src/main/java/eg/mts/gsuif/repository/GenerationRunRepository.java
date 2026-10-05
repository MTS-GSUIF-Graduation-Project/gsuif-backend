package eg.mts.gsuif.repository;

import eg.mts.gsuif.entity.GenerationRun;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.UUID;

/** Internal lookup for persisted generation outcomes. */
public interface GenerationRunRepository extends JpaRepository<GenerationRun, UUID> { }
