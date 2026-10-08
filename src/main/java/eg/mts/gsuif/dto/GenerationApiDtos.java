package eg.mts.gsuif.dto;

import eg.mts.gsuif.generation.GenerationSpecification;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Public generation request and response shapes. The specification remains explicit and versioned. */
public final class GenerationApiDtos {
    private GenerationApiDtos() { }

    public record GenerateRequest(@NotNull UUID pageId, @NotBlank String generationType,
                                  @NotNull GenerationSpecification specification) { }

    public record TextArtifact(String relativePath, String content, String sha256,
                               String templateVersion, String catalogVersion) { }

    public record CreatedRun(UUID runId, String status, UUID metadataVersionId,
                             List<TextArtifact> artifacts) { }

    public record Provider(String name, boolean available) { }

    public record LinkedArtifact(UUID id, String artifactName, String artifactType,
                                 String relativePath, String templateVersion) { }

    public record RunDetails(UUID id, String status, Instant createdAt, Instant updatedAt,
                             UUID metadataVersionId, List<LinkedArtifact> artifacts,
                             Integer compileExitCode, String compileOutput,
                             Integer testExitCode, String testOutput) { }
}
