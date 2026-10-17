package eg.mts.gsuif.generation;

import java.util.List;
import java.util.UUID;

public class GenerationValidationException extends IllegalArgumentException {
    private final List<String> diagnostics;
    private final UUID runId;

    public GenerationValidationException(List<String> diagnostics) {
        this(diagnostics, null);
    }

    public GenerationValidationException(List<String> diagnostics, UUID runId) {
        super(String.join("; ", diagnostics));
        this.diagnostics = List.copyOf(diagnostics);
        this.runId = runId;
    }

    public List<String> diagnostics() { return diagnostics; }
    public UUID runId() { return runId; }
}
