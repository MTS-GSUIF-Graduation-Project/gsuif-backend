package eg.mts.gsuif.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
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

    /** Complete BACKEND output; provider and model are server configuration, not request fields. */
    public record BackendRequest(@NotNull UUID pageId, @NotNull GenerationSpecification specification,
                                 UUID attemptId) { }
    public record BackendCreated(UUID runId, UUID attemptId, String status, UUID metadataVersionId,
                                 int artifactCount, BackendPackage backendPackage) { }

    /** Exact contract and validation evidence for a committed complete BACKEND package. */
    public record BackendPackage(String contractId, String contractVersion, String manifestSha256,
                                 ValidationScope validationScope) { }

    public record TextArtifact(String relativePath, String content, String sha256,
                               String templateVersion, String catalogVersion) { }

    public record ValidationScope(List<String> passedTargets, List<String> notValidatedTargets) {
        public ValidationScope {
            passedTargets = List.copyOf(passedTargets);
            notValidatedTargets = List.copyOf(notValidatedTargets);
        }
    }

    public record CreatedRun(UUID runId, String status, UUID metadataVersionId,
                             List<TextArtifact> artifacts, ValidationScope validationScope) { }

    public record Provider(String name, boolean available) { }

    public record LinkedArtifact(UUID id, String artifactName, String artifactType,
                                 String relativePath, String templateVersion) { }

    public record RunDetails(UUID id, String status, Instant createdAt, Instant updatedAt,
                             UUID metadataVersionId, List<LinkedArtifact> artifacts,
                             Integer compileExitCode, String compileOutput,
                             Integer testExitCode, String testOutput, ValidationScope validationScope,
                             @JsonInclude(JsonInclude.Include.NON_NULL) BackendPackage backendPackage) {
        public RunDetails(UUID id, String status, Instant createdAt, Instant updatedAt,
                UUID metadataVersionId, List<LinkedArtifact> artifacts,
                Integer compileExitCode, String compileOutput,
                Integer testExitCode, String testOutput, ValidationScope validationScope) {
            this(id, status, createdAt, updatedAt, metadataVersionId, artifacts,
                    compileExitCode, compileOutput, testExitCode, testOutput, validationScope, null);
        }
    }

    public record FailedAttempt(UUID runId, UUID attemptId, UUID metadataVersionId,
                                String status, String failureStage, String failureCode) { }

    /** Catalog eligibility is contract-level; a page still requires its own preflight. */
    public record AvailableContract(String contractId, String contractVersion, String label,
                                    List<String> operationIds, String updateMode,
                                    boolean pageCompatibilityChecked) { }

    public record AttemptStatus(UUID runId, UUID attemptId, String status, UUID metadataVersionId,
                                String failureStage, String failureCode) { }
}
