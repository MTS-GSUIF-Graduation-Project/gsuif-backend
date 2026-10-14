package eg.mts.gsuif.generation;

import eg.mts.gsuif.entity.MetadataVersion;
import eg.mts.gsuif.generation.GenerationContext.Target;
import eg.mts.gsuif.generation.GenerationSpecification.FieldSpec;

import java.util.*;

/** Input checks for future complete packages; this stage does not generate an application. */
final class GenerationCapabilityPreflight {
    enum OutputMode { FRONTEND, BACKEND, FULL_STACK }

    private final GenerationContextBuilder contextBuilder;
    private final PackagedContractLoader contractLoader;

    GenerationCapabilityPreflight(GenerationContextBuilder contextBuilder, PackagedContractRegistry registry) {
        this.contextBuilder = Objects.requireNonNull(contextBuilder);
        this.contractLoader = new PackagedContractLoader(Objects.requireNonNull(registry));
    }

    GenerationContext check(MetadataVersion version, GenerationSpecification specification, OutputMode mode) {
        if (mode == null) throw new GenerationValidationException(List.of("outputMode: required"));
        Set<Target> targets = switch (mode) {
            case FRONTEND -> Set.of(Target.ANGULAR);
            case BACKEND -> Set.of(Target.ENTITY, Target.CONTROLLER);
            case FULL_STACK -> EnumSet.allOf(Target.class);
        };
        GenerationContext context = contextBuilder.build(version, specification, targets, "spring-angular");
        List<String> errors = new ArrayList<>();
        var contract = contractLoader.load(specification.openApi(), errors);
        if (contract == null) {
            errors.add("specification.openApi: registered contract required");
        } else {
            if (!contract.descriptor().fullUpdate())
                errors.add("specification.openApi.contractVersion: complete output requires the full-update contract profile");
            if (!new HashSet<>(specification.openApi().bindingOperations().values())
                    .equals(new HashSet<>(contract.descriptor().operations().values())))
                errors.add("specification.openApi.bindingOperations: all five mapped CRUD operations required");
            Map<String, FieldSpec> fields = new LinkedHashMap<>();
            for (FieldSpec field : specification.entity().fields()) fields.put(field.name(), field);
            if (!fields.keySet().equals(contract.updateFields().keySet()))
                errors.add("contract.update: editable fields must match explicit entity fields");
            for (var update : contract.updateFields().entrySet()) {
                FieldSpec field = fields.get(update.getKey());
                if (!update.getValue().required())
                    errors.add("contract.update." + update.getKey() + ": editable field must be required");
                if (field != null && field.nullable() != update.getValue().nullable())
                    errors.add("contract.update." + update.getKey() + ": nullability differs from entity field");
            }
        }
        if (!errors.isEmpty()) throw new GenerationValidationException(errors);
        return context;
    }
}
