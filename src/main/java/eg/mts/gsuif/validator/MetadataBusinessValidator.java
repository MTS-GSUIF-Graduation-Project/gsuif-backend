package eg.mts.gsuif.validator;

import eg.mts.gsuif.validator.MetadataSchemaValidator.ValidationError;
import org.springframework.stereotype.Component;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class MetadataBusinessValidator {
    private final List<MetadataBusinessRule> rules;

    public MetadataBusinessValidator(List<MetadataBusinessRule> rules) {
        this.rules = List.copyOf(rules);
    }

    public List<ValidationError> validate(MetadataValidationContext context) {
        return rules.stream().filter(rule -> rule.supports(context))
                .flatMap(rule -> rule.validate(context).stream())
                .sorted(Comparator.comparing(ValidationError::path).thenComparing(ValidationError::message))
                .toList();
    }

    /** Preserve every message when the existing API representation groups by field. */
    public static Map<String, String> groupErrors(List<ValidationError> errors) {
        Map<String, String> grouped = new LinkedHashMap<>();
        errors.forEach(error -> grouped.merge(error.path(), error.message(),
                (previous, next) -> previous + "; " + next));
        return grouped;
    }
}
