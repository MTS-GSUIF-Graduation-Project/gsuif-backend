package eg.mts.gsuif.validator;

import eg.mts.gsuif.validator.MetadataSchemaValidator.ValidationError;
import org.springframework.stereotype.Component;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Component
public class ComponentIdUniquenessRule implements MetadataBusinessRule {
    @Override public String name() { return "ComponentIdUniqueness"; }
    @Override public boolean supports(MetadataValidationContext context) {
        return context.operation() == MetadataValidationContext.Operation.SNAPSHOT;
    }
    @Override public List<ValidationError> validate(MetadataValidationContext context) {
        if (!supports(context)) return List.of();
        List<ValidationError> errors = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        var components = context.snapshot().path("components");
        for (int i = 0; i < components.size(); i++) {
            if (!ids.add(components.get(i).path("id").asText())) {
                errors.add(new ValidationError("$.snapshot.components[" + i + "].id",
                        name() + ": component ID must be unique within the page snapshot"));
            }
        }
        return errors;
    }
}
