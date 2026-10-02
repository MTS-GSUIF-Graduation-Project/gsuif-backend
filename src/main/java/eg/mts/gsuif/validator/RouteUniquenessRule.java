package eg.mts.gsuif.validator;

import eg.mts.gsuif.repository.GsuifPageRepository;
import eg.mts.gsuif.validator.MetadataSchemaValidator.ValidationError;
import org.springframework.stereotype.Component;
import java.util.List;

@Component
public class RouteUniquenessRule implements MetadataBusinessRule {
    private final GsuifPageRepository pages;
    public RouteUniquenessRule(GsuifPageRepository pages) { this.pages = pages; }
    @Override public String name() { return "RouteUniqueness"; }
    @Override public boolean supports(MetadataValidationContext context) {
        return context.operation() == MetadataValidationContext.Operation.PAGE;
    }
    @Override public List<ValidationError> validate(MetadataValidationContext context) {
        if (!supports(context) || context.route() == null) return List.of();
        boolean exists = context.excludedPageId() == null
                ? pages.existsByProjectIdAndRoute(context.projectId(), context.route())
                : pages.existsByProjectIdAndRouteAndIdNot(context.projectId(), context.route(), context.excludedPageId());
        return exists ? List.of(new ValidationError("route", name() + ": Page with route '"
                + context.route() + "' already exists in project '" + context.projectId() + "'")) : List.of();
    }
}
