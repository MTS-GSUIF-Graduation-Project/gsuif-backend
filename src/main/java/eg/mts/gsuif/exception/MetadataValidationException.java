package eg.mts.gsuif.exception;

import java.util.Map;

public class MetadataValidationException extends RuntimeException {

    private final Map<String, String> errors;

    public MetadataValidationException(String message, Map<String, String> errors) {
        super(message);
        this.errors = errors;
    }

    public Map<String, String> getErrors() {
        return errors;
    }
}
