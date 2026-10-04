package eg.mts.gsuif.generation;

import eg.mts.gsuif.entity.GenerationRun;
import eg.mts.gsuif.entity.GenerationRunStatus;
import eg.mts.gsuif.entity.GsuifUser;
import eg.mts.gsuif.entity.MetadataVersion;
import eg.mts.gsuif.generation.GenerationContext.Target;
import eg.mts.gsuif.repository.GenerationRunRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** Internal generation-run persistence and lookup; REST exposure belongs to SCRUM-48. */
@Service
public class GenerationRunService {
    private final GenerationRunRepository runs;
    private final ConsumerBuildValidator validator;

    @Autowired
    public GenerationRunService(GenerationRunRepository runs) {
        this(runs, new ConsumerBuildValidator(Path.of("target", "generation-builds")));
    }

    public GenerationRunService(GenerationRunRepository runs, ConsumerBuildValidator validator) {
        this.runs = Objects.requireNonNull(runs);
        this.validator = Objects.requireNonNull(validator);
    }

    public GenerationRun generateAndRecord(GenerationEngine engine, MetadataVersion version,
            GenerationSpecification specification, Set<Target> targets, String framework,
            GsuifUser user, Map<String, byte[]> consumerInputs) {
        return validateAndRecord(version, user, engine.generate(version, specification, targets, framework), consumerInputs);
    }

    /** Persists only after both build commands have completed or validation has failed. */
    public GenerationRun validateAndRecord(MetadataVersion version, GsuifUser user,
            GenerationResult result, Map<String, byte[]> consumerInputs) {
        Objects.requireNonNull(version);
        Objects.requireNonNull(user);
        Objects.requireNonNull(result);
        var build = validator.validate(result, consumerInputs);
        GenerationRun run = new GenerationRun();
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
        return runs.save(run);
    }

    public Optional<GenerationRun> findById(UUID id) {
        return runs.findById(Objects.requireNonNull(id));
    }
}
