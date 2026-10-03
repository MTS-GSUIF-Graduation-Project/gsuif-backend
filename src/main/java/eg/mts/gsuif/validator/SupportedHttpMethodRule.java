package eg.mts.gsuif.validator;

import eg.mts.gsuif.validator.MetadataSchemaValidator.ValidationError;
import org.springframework.stereotype.Component;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

@Component
public class SupportedHttpMethodRule implements MetadataBusinessRule {
    private static final Set<String> METHODS = Set.of("GET", "POST", "PUT", "DELETE");
    @Override public String name() { return "SupportedHttpMethod"; }
    @Override public boolean supports(MetadataValidationContext context) {
        return context.operation() == MetadataValidationContext.Operation.SNAPSHOT;
    }
    @Override public List<ValidationError> validate(MetadataValidationContext context) {
        if (!supports(context)) return List.of();
        List<ValidationError> errors = new ArrayList<>();
        var bindings = context.snapshot().path("apiBindings");
        for (int i = 0; i < bindings.size(); i++) {
            if (!METHODS.contains(bindings.get(i).path("httpMethod").asText())) {
                errors.add(new ValidationError("$.snapshot.apiBindings[" + i + "].httpMethod",
                        name() + ": HTTP method must be GET, POST, PUT or DELETE"));
            }
        }
        return errors;
    }
}
