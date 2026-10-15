package eg.mts.gsuif.generation;

import java.util.Objects;
import java.util.UUID;

/** Future stage adapters wrap failures here; only the fixed stage/code are persisted. */
public final class GenerationStageException extends RuntimeException {
    private final GenerationFailureStage stage;
    private final GenerationFailureCode code;
    private final UUID runId;

    public GenerationStageException(GenerationFailureStage stage, GenerationFailureCode code) {
        this(stage, code, null);
    }

    public GenerationStageException(GenerationFailureStage stage, GenerationFailureCode code, UUID runId) {
        super("Generation stage failed: " + Objects.requireNonNull(stage));
        this.stage = stage;
        this.code = Objects.requireNonNull(code);
        this.runId = runId;
    }

    public GenerationFailureStage stage() { return stage; }
    public GenerationFailureCode code() { return code; }
    public UUID runId() { return runId; }
}
