package eg.mts.gsuif.repository;

import eg.mts.gsuif.entity.GenerationRun;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import jakarta.persistence.LockModeType;
import java.util.UUID;
import java.util.Optional;

/** Internal lookup for persisted generation outcomes. */
public interface GenerationRunRepository extends JpaRepository<GenerationRun, UUID> {
    Optional<GenerationRun> findByAttemptId(UUID attemptId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from GenerationRun r where r.attemptId = :attemptId")
    Optional<GenerationRun> findLockedByAttemptId(UUID attemptId);
}
