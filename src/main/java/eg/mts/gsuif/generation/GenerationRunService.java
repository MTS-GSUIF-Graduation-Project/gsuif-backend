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
        Objects.requireNonNull(attemptId);
        Objects.requireNonNull(version);
        Objects.requireNonNull(user);
        Objects.requireNonNull(result);
        var existing = runs.findByAttemptId(attemptId);
        if (existing.isPresent()) {
            requireSameAttempt(existing.get(), fingerprint);
            return existing.get();
        }
        var build = validator.validate(result, consumerInputs);
        GenerationRun run = new GenerationRun();
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

    public Optional<GenerationRun> findByAttemptId(UUID attemptId) {
        return runs.findByAttemptId(Objects.requireNonNull(attemptId));
    }

    public GenerationResult readResult(GenerationRun run) {
        return store.read(Objects.requireNonNull(run.getId()));
    }

    @Transactional(readOnly = true)
    public byte[] readArtifact(UUID runId, String relativePath) {
        if (findFile(runId, relativePath).isEmpty()) throw new IllegalArgumentException("Artifact is not registered for run");
        return store.read(runId).artifacts().stream()
                .filter(a -> a.relativePath().equals(relativePath)).findFirst()
                .orElseThrow(() -> new IllegalStateException("Registered artifact is missing from storage")).bytes();
    }

    static void requireSameAttempt(GenerationRun run, String fingerprint) {
        if (!Objects.equals(run.getInputFingerprint(), fingerprint))
            throw new IllegalArgumentException("Attempt ID is already bound to different generation or build inputs");
    }
}
