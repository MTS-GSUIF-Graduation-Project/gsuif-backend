package eg.mts.gsuif.repository;

import eg.mts.gsuif.entity.GeneratedArtifact;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** File identity is the path within a run; a path alone has a history of runs. */
public interface GeneratedArtifactRepository extends JpaRepository<GeneratedArtifact, UUID> {
    List<GeneratedArtifact> findAllByGenerationRunIdOrderByRelativePathAsc(UUID runId);
    @Query("select a from GeneratedArtifact a join fetch a.generationRun r join fetch r.metadataVersion "
            + "where r.id = :runId and a.relativePath = :path")
    Optional<GeneratedArtifact> findFile(@Param("runId") UUID runId, @Param("path") String path);

    @Query("select a from GeneratedArtifact a join fetch a.generationRun r join fetch r.metadataVersion "
            + "where a.relativePath = :path order by r.createdAt desc")
    List<GeneratedArtifact> findFileHistory(@Param("path") String path);
}
