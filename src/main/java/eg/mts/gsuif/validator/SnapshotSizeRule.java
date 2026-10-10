package eg.mts.gsuif.validator;

import eg.mts.gsuif.validator.MetadataSchemaValidator.ValidationError;
import org.springframework.stereotype.Component;
import java.nio.charset.StandardCharsets;
import java.util.List;

@Component
public class SnapshotSizeRule implements MetadataBusinessRule {
    public static final int MAX_UTF8_BYTES = 5_000_000;
    @Override public String name() { return "SnapshotSize"; }
    @Override public boolean supports(MetadataValidationContext context) {
        return context.operation() == MetadataValidationContext.Operation.SNAPSHOT;
    }
    @Override public List<ValidationError> validate(MetadataValidationContext context) {
        if (!supports(context)) return List.of();
        return context.exactPersistedJson().getBytes(StandardCharsets.UTF_8).length <= MAX_UTF8_BYTES
                ? List.of() : List.of(new ValidationError("$.snapshot",
                name() + ": persisted snapshot must not exceed " + MAX_UTF8_BYTES + " UTF-8 bytes"));
    }
}
