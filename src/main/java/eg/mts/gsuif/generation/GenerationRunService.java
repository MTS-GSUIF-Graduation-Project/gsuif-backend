package eg.mts.gsuif.generation;

import eg.mts.gsuif.entity.GenerationRun;
import eg.mts.gsuif.entity.GeneratedArtifact;
import eg.mts.gsuif.entity.GenerationRunStatus;
import eg.mts.gsuif.entity.GsuifUser;
import eg.mts.gsuif.entity.MetadataVersion;
import eg.mts.gsuif.repository.GenerationRunRepository;
import eg.mts.gsuif.repository.GeneratedArtifactRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Propagation;

import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Internal generation-run persistence and lookup; REST exposure belongs to SCRUM-48. */
@Service
public class GenerationRunService {
    private final GenerationRunRepository runs;
    private final GeneratedArtifactRepository artifacts;
    private final ConsumerBuildValidator validator;
    private final GenerationArtifactStore store;

    @Autowired
    public GenerationRunService(GenerationRunRepository runs, GeneratedArtifactRepository artifacts,
            GenerationArtifactStore store) {
        this(runs, artifacts, new ConsumerBuildValidator(Path.of("target", "generation-builds")), store);
    }

    public GenerationRunService(GenerationRunRepository runs, GeneratedArtifactRepository artifacts, ConsumerBuildValidator validator) {
        this(runs, artifacts, validator, new GenerationArtifactStore(Path.of("target", "generation-artifact-tests")));
    }

    public GenerationRunService(GenerationRunRepository runs, GeneratedArtifactRepository artifacts,
            ConsumerBuildValidator validator, GenerationArtifactStore store) {
        this.runs = Objects.requireNonNull(runs);
        this.artifacts = Objects.requireNonNull(artifacts);
        this.validator = Objects.requireNonNull(validator);
        this.store = Objects.requireNonNull(store);
    }

    /** Commits before any provider runs, so a later exception cannot erase the attempt. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public GenerationRun reserveAttempt(UUID attemptId, MetadataVersion version, GsuifUser user, String fingerprint) {
        return reserve(attemptId, version, user, fingerprint, "TemplateOnlyProvider", null);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public GenerationRun reserveBackendAttempt(UUID attemptId, MetadataVersion version, GsuifUser user,
            String fingerprint, CompleteBackendPackageAssembler.Provenance provenance) {
        return reserve(attemptId, version, user, fingerprint, BackendPackageIntegrity.TOOL,
                provenance.providerVersion() + "|" + provenance.modelId());
    }

    private GenerationRun reserve(UUID attemptId, MetadataVersion version, GsuifUser user,
            String fingerprint, String tool, String backendVersion) {
        Objects.requireNonNull(attemptId);
        Objects.requireNonNull(version);
        Objects.requireNonNull(user);
        Objects.requireNonNull(fingerprint);
        var existing = runs.findByAttemptId(attemptId);
        if (existing.isPresent()) {
            requireSameAttempt(existing.get(), fingerprint);
            throw new IllegalStateException("Attempt ID is already reserved; inspect its saved status before retrying");
        }
        GenerationRun run = new GenerationRun();
        run.setAttemptId(attemptId);
        run.setInputFingerprint(fingerprint);
        run.setMetadataVersion(version);
        run.setTriggeringUser(user);
        run.setTemplateVersion("pending");
        // Reservation must remain possible when the packaged catalog itself is broken.
        String generatorVersion = "unavailable", providerVersion = "unavailable";
        try {
            var catalog = GenerationCatalog.load();
            generatorVersion = catalog.version();
            providerVersion = catalog.providerVersion();
        } catch (RuntimeException ignored) {
            // The workflow records CONFIG/CONFIG_MISSING after this committed reservation.
        }
        run.setGeneratorVersion(generatorVersion);
        run.setToolName(tool);
        run.setToolVersion(backendVersion == null ? providerVersion : backendVersion);
        run.setStatus(GenerationRunStatus.IN_PROGRESS);
        return runs.saveAndFlush(run);
    }

    /** Build has already passed; only row locking, registration and atomic publication occur here. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public GenerationRun completeBackendAttempt(UUID attemptId, String fingerprint,
            GenerationArtifactStore.Staged staged, GenerationResult result,
            CompleteBackendPackageAssembler.Provenance provenance) {
        GenerationRun run = runs.findLockedByAttemptId(Objects.requireNonNull(attemptId))
                .orElseThrow(() -> new IllegalStateException("Attempt was not reserved"));
        requireSameAttempt(run, fingerprint);
        if (run.getStatus() != GenerationRunStatus.IN_PROGRESS || !BackendPackageIntegrity.TOOL.equals(run.getToolName())
                || !run.getId().equals(staged.runId()) || !fingerprint.equals(staged.fingerprint())
                || !(provenance.providerVersion() + "|" + provenance.modelId()).equals(run.getToolVersion()))
            throw new IllegalStateException("Backend attempt cannot be completed");
        run.setTemplateVersion("complete-backend");
        run.setGeneratorVersion(result.artifacts().getFirst().catalogVersion());
        run.setPackageManifestSha256(result.artifacts().stream()
                .filter(a -> a.relativePath().equals("generation-manifest.json"))
                .findFirst().orElseThrow(() -> new IllegalStateException("Backend manifest missing")).sha256());
        run.setCompileExitCode(0);
        run.setCompileOutput("Isolated generated backend build passed");
        run.setStatus(GenerationRunStatus.SUCCESS);
        runs.saveAndFlush(run);
        for (var generated : result.artifacts()) {
            GeneratedArtifact artifact = new GeneratedArtifact();
            artifact.setGenerationRun(run);
            artifact.setRelativePath(generated.relativePath());
            artifact.setTemplateVersion(generated.templateVersion());
            String name = Path.of(generated.relativePath()).getFileName().toString();
            artifact.setArtifactName(name);
            artifact.setArtifactType(name.endsWith(".java") ? "java-source" : "generated-file");
            artifacts.save(artifact);
        }
        artifacts.flush();
        store.publish(staged);
        return run;
    }

    /** Persist only a fixed stage and bounded code; exception text and provider output are excluded. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public GenerationRun recordFailure(UUID attemptId, String fingerprint, GenerationFailureStage stage, GenerationFailureCode code) {
        Objects.requireNonNull(stage);
        Objects.requireNonNull(code);
        GenerationRun run = runs.findLockedByAttemptId(Objects.requireNonNull(attemptId))
                .orElseThrow(() -> new IllegalStateException("Attempt was not reserved"));
        requireSameAttempt(run, fingerprint);
        if (run.getStatus() != GenerationRunStatus.IN_PROGRESS) return run;
        run.setFailureStage(stage.name());
        run.setFailureCode(code.name());
        run.setStatus(GenerationRunStatus.FAILED);
        return runs.saveAndFlush(run);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public GenerationRun completeReservedAttempt(UUID attemptId, MetadataVersion version, GsuifUser user,
            GenerationResult result, Map<String, byte[]> consumerInputs, String fingerprint) {
        return recordResult(attemptId, version, user, result, consumerInputs, fingerprint, true);
    }

    /** The caller supplies a stable attempt ID and reuses it when retrying the same attempt. */
    @Transactional
    public GenerationRun validateAndRecord(UUID attemptId, MetadataVersion version, GsuifUser user,
            GenerationResult result, Map<String, byte[]> consumerInputs) {
        return validateAndRecord(attemptId, version, user, result, consumerInputs,
                GenerationAttemptFingerprint.result(version, user, result, consumerInputs));
    }

    @Transactional
    public GenerationRun validateAndRecord(UUID attemptId, MetadataVersion version, GsuifUser user,
            GenerationResult result, Map<String, byte[]> consumerInputs, String fingerprint) {
        return recordResult(attemptId, version, user, result, consumerInputs, fingerprint, false);
    }

    private GenerationRun recordResult(UUID attemptId, MetadataVersion version, GsuifUser user,
            GenerationResult result, Map<String, byte[]> consumerInputs, String fingerprint, boolean reservedCompletion) {
        Objects.requireNonNull(attemptId);
        Objects.requireNonNull(version);
        Objects.requireNonNull(user);
        Objects.requireNonNull(result);
        var existing = runs.findLockedByAttemptId(attemptId);
        if (reservedCompletion && existing.isEmpty()) throw new IllegalStateException("Attempt was not reserved");
        if (existing.isPresent()) {
            requireSameAttempt(existing.get(), fingerprint);
            if (existing.get().getStatus() != GenerationRunStatus.IN_PROGRESS) return existing.get();
            if (!reservedCompletion) throw new IllegalStateException("Attempt is reserved by generation workflow");
        }
        var build = validator.validate(result, consumerInputs);
        GenerationRun run = existing.orElseGet(GenerationRun::new);
        run.setAttemptId(attemptId);
        run.setInputFingerprint(fingerprint);
        run.setMetadataVersion(version);
        run.setTriggeringUser(user);
        String templateVersion = result.artifacts().isEmpty() ? "unknown" : result.artifacts().getFirst().templateVersion();
        run.setTemplateVersion(templateVersion);
        run.setGeneratorVersion(result.artifacts().isEmpty() ? "unknown" : result.artifacts().getFirst().catalogVersion());
        run.setToolName("TemplateOnlyProvider");
        run.setToolVersion(GenerationCatalog.load().providerVersion());
        run.setCompileExitCode(build.compile().exitCode());
        run.setCompileOutput(build.compile().output());
        run.setTestExitCode(build.test() == null ? null : build.test().exitCode());
        run.setTestOutput(build.test() == null ? null : build.test().output());
        run.setStatus(build.passed() ? GenerationRunStatus.SUCCESS : GenerationRunStatus.BUILD_FAILED);
        run = runs.save(run);
        store.save(run.getId(), fingerprint, result);
        for (GenerationResult.Artifact generated : result.artifacts()) {
            GeneratedArtifact artifact = new GeneratedArtifact();
            artifact.setGenerationRun(run);
            artifact.setRelativePath(generated.relativePath());
            artifact.setTemplateVersion(generated.templateVersion());
            String fileName = Path.of(generated.relativePath()).getFileName().toString();
            artifact.setArtifactName(fileName);
            artifact.setArtifactType(fileName.endsWith(".java") ? "java-source" : "generated-file");
            artifacts.save(artifact);
        }
        return run;
    }

    @Transactional(readOnly = true)
    public Optional<GeneratedArtifact> findFile(UUID runId, String relativePath) {
        return artifacts.findFile(Objects.requireNonNull(runId), Objects.requireNonNull(relativePath));
    }

    @Transactional(readOnly = true)
    public java.util.List<GeneratedArtifact> findFileHistory(String relativePath) {
        return artifacts.findFileHistory(Objects.requireNonNull(relativePath));
    }

    public Optional<GenerationRun> findById(UUID id) {
        return runs.findById(Objects.requireNonNull(id));
    }

    /** Return only fixed, persisted failure values for the authenticated run owner. */
    @Transactional(readOnly = true)
    public Optional<PersistedFailure> persistedFailure(UUID runId, String username) {
        if (runId == null || username == null || username.isBlank()) return Optional.empty();
        return runs.findById(runId).flatMap(run -> {
            if (run.getStatus() != GenerationRunStatus.FAILED || !runId.equals(run.getId())
                    || run.getTriggeringUser() == null
                    || !username.equals(run.getTriggeringUser().getUsername())) return Optional.empty();
            try {
                return Optional.of(new PersistedFailure(runId,
                        GenerationFailureStage.valueOf(run.getFailureStage()),
                        GenerationFailureCode.valueOf(run.getFailureCode())));
            } catch (IllegalArgumentException | NullPointerException invalidPersistedValue) {
                return Optional.empty();
            }
        });
    }

    public record PersistedFailure(UUID runId, GenerationFailureStage stage, GenerationFailureCode code) { }

    public Optional<GenerationRun> findByAttemptId(UUID attemptId) {
        return runs.findByAttemptId(Objects.requireNonNull(attemptId));
    }

    @Transactional(readOnly = true)
    public GenerationResult readResult(GenerationRun run) {
        if (run.getStatus() != GenerationRunStatus.SUCCESS && run.getStatus() != GenerationRunStatus.BUILD_FAILED)
            throw new IllegalStateException("Attempt has no complete generation result: " + run.getStatus());
        boolean backend = BackendPackageIntegrity.TOOL.equals(run.getToolName());
        GenerationResult result = backend
                ? store.readVerified(Objects.requireNonNull(run.getId()), run.getInputFingerprint())
                : store.read(Objects.requireNonNull(run.getId()));
        if (backend)
            BackendPackageIntegrity.verify(run, result,
                    artifacts.findAllByGenerationRunIdOrderByRelativePathAsc(run.getId()));
        return result;
    }

    /** Complete BACKEND package only, with a bounded in-memory snapshot for ZIP streaming. */
    @Transactional(readOnly = true)
    public GenerationResult readBackendPackage(GenerationRun run, long maxBytes, int maxFiles) {
        if (run.getStatus() != GenerationRunStatus.SUCCESS
                || !BackendPackageIntegrity.TOOL.equals(run.getToolName()))
            throw new IllegalStateException("Run is not a successful complete BACKEND package");
        GenerationResult result = store.readVerified(run.getId(), run.getInputFingerprint(), maxBytes, maxFiles);
        BackendPackageIntegrity.verify(run, result,
                artifacts.findAllByGenerationRunIdOrderByRelativePathAsc(run.getId()));
        return result;
    }

    @Transactional(readOnly = true)
    public byte[] readArtifact(UUID runId, String relativePath) {
        if (findFile(runId, relativePath).isEmpty()) throw new IllegalArgumentException("Artifact is not registered for run");
        GenerationRun run = runs.findById(runId).orElseThrow(() -> new IllegalArgumentException("Run missing"));
        return readResult(run).artifacts().stream()
                .filter(a -> a.relativePath().equals(relativePath)).findFirst()
                .orElseThrow(() -> new IllegalStateException("Registered artifact is missing from storage")).bytes();
    }

    static void requireSameAttempt(GenerationRun run, String fingerprint) {
        if (!Objects.equals(run.getInputFingerprint(), fingerprint))
            throw new IllegalArgumentException("Attempt ID is already bound to different generation or build inputs");
    }
}
