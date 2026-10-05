package eg.mts.gsuif.generation;

import java.util.List;

public class GenerationValidationException extends IllegalArgumentException {
    private final List<String> diagnostics;

    public GenerationValidationException(List<String> diagnostics) {
        super(String.join("; ", diagnostics));
        this.diagnostics = List.copyOf(diagnostics);
    }

    public List<String> diagnostics() { return diagnostics; }
}
