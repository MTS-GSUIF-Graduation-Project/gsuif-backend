package eg.mts.gsuif.validator;

import java.util.List;
import eg.mts.gsuif.validator.MetadataSchemaValidator.ValidationError;

/** Implement as a Spring bean to extend validation without changing the core. */
public interface MetadataBusinessRule {
    String name();
    boolean supports(MetadataValidationContext context);
    List<ValidationError> validate(MetadataValidationContext context);
}
