package eg.mts.gsuif.generation;

import com.fasterxml.jackson.databind.ObjectMapper;
import eg.mts.gsuif.entity.GenerationRun;
import eg.mts.gsuif.entity.GenerationRunStatus;
import eg.mts.gsuif.entity.GsuifUser;
import eg.mts.gsuif.entity.MetadataVersion;
import eg.mts.gsuif.generation.GenerationContext.Target;
import eg.mts.gsuif.validator.MetadataSchemaValidator;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Production entry point for a completed generation and consumer build attempt. */
@Service
public class GenerationWorkflow {
    private final GenerationEngine engine;
    private final GenerationRunService runs;

    @Autowired
    public GenerationWorkflow(MetadataSchemaValidator schemaValidator, GenerationRunService runs) {
        this(GenerationEngine.templateOnly(new GenerationContextBuilder(new ObjectMapper(), schemaValidator)), runs);
    }

    GenerationWorkflow(GenerationEngine engine, GenerationRunService runs) {
        this.engine = Objects.requireNonNull(engine);
        this.runs = Objects.requireNonNull(runs);
    }

    /** Stable IDs replay complete results; failed/interrupted IDs remain terminal and require a new ID. */
    public CompletedAttempt generateAndRecord(UUID attemptId, MetadataVersion version, GsuifUser user,
            GenerationSpecification specification, Set<Target> targets, String framework,
            Map<String, byte[]> consumerInputs) {
        Objects.requireNonNull(attemptId);
        var prepared = GenerationAttemptFingerprint.prepare(version, user, specification, targets, framework, consumerInputs);
        String fingerprint = prepared.value();
        CompletedAttempt replay = reserveOrReplay(attemptId, version, user, fingerprint);
        if (replay != null) return replay;
        if (prepared.resourceUnavailable()) {
            GenerationRun failed = runs.recordFailure(attemptId, fingerprint, GenerationFailureStage.CONFIG, GenerationFailureCode.CONFIG_MISSING);
            throw new GenerationStageException(GenerationFailureStage.CONFIG, GenerationFailureCode.CONFIG_MISSING, idOf(failed));
        }
        GenerationResult result;
        try {
            result = engine.generate(version, specification, targets, framework);
        } catch (GenerationValidationException ex) {
            GenerationRun failed = runs.recordFailure(attemptId, fingerprint, GenerationFailureStage.CONTRACT, GenerationFailureCode.VALIDATION_FAILED);
            throw new GenerationValidationException(ex.diagnostics(), idOf(failed));
        } catch (GenerationStageException ex) {
            GenerationRun failed = runs.recordFailure(attemptId, fingerprint, ex.stage(), ex.code());
            throw new GenerationStageException(ex.stage(), ex.code(), idOf(failed));
        } catch (RuntimeException ex) {
            GenerationRun failed = runs.recordFailure(attemptId, fingerprint, GenerationFailureStage.ASSEMBLY, GenerationFailureCode.UNEXPECTED_FAILURE);
            throw new GenerationStageException(GenerationFailureStage.ASSEMBLY, GenerationFailureCode.UNEXPECTED_FAILURE, idOf(failed));
        }
        GenerationRun run;
        try {
            run = runs.completeReservedAttempt(attemptId, version, user, result, consumerInputs, fingerprint);
        } catch (RuntimeException ex) {
            GenerationRun failed = runs.recordFailure(attemptId, fingerprint, GenerationFailureStage.FINAL_VALIDATION, GenerationFailureCode.RESULT_VALIDATION_FAILED);
            throw new GenerationStageException(GenerationFailureStage.FINAL_VALIDATION, GenerationFailureCode.RESULT_VALIDATION_FAILED, idOf(failed));
        }
        return new CompletedAttempt(runs.readResult(run), run);
    }

    /** Invalid client inputs are rejected before this point; internal input preparation is recorded. */
    public void recordPreparationFailure(UUID attemptId, MetadataVersion version, GsuifUser user,
            GenerationSpecification specification, Set<Target> targets, String framework) {
        var prepared = GenerationAttemptFingerprint.prepare(version, user, specification, targets, framework, Map.of());
        if (reserveOrReplay(attemptId, version, user, prepared.value()) != null)
            throw new IllegalStateException("Attempt has already completed");
        GenerationRun failed = runs.recordFailure(attemptId, prepared.value(), GenerationFailureStage.CONFIG, GenerationFailureCode.CONFIG_MISSING);
        throw new GenerationStageException(GenerationFailureStage.CONFIG, GenerationFailureCode.CONFIG_MISSING, idOf(failed));
    }

    private static UUID idOf(GenerationRun run) { return run == null ? null : run.getId(); }

    private CompletedAttempt reserveOrReplay(UUID attemptId, MetadataVersion version, GsuifUser user, String fingerprint) {
        var existing = runs.findByAttemptId(attemptId);
        if (existing.isPresent()) return replayOrReject(existing.get(), fingerprint);
        try {
            runs.reserveAttempt(attemptId, version, user, fingerprint);
        } catch (DataIntegrityViolationException | IllegalStateException ex) {
            GenerationRun winner = runs.findByAttemptId(attemptId).orElseThrow(() -> ex);
            return replayOrReject(winner, fingerprint);
        }
        return null;
    }

    private CompletedAttempt replayOrReject(GenerationRun run, String fingerprint) {
        GenerationRunService.requireSameAttempt(run, fingerprint);
        if (run.getStatus() == GenerationRunStatus.SUCCESS || run.getStatus() == GenerationRunStatus.BUILD_FAILED)
            return new CompletedAttempt(runs.readResult(run), run);
        throw new IllegalStateException("Attempt is " + run.getStatus()
                + "; inspect its saved stage/code and use a new attempt ID");
    }

    public record CompletedAttempt(GenerationResult result, GenerationRun run) { }
}
