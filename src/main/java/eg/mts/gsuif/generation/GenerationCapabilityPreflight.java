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
            @SuppressWarnings("unchecked")
            List<String> dtoRequired = (List<String>) contract.api().getOrDefault("dtoRequired", List.of());
            for (var update : contract.updateFields().entrySet()) {
                FieldSpec field = fields.get(update.getKey());
                if (!update.getValue().required())
                    errors.add("contract.update." + update.getKey() + ": editable field must be required");
                if (!dtoRequired.contains(update.getKey()))
                    errors.add("contract.dto." + update.getKey() + ": response field must be required");
                if (field != null) {
                    compatible(field, contract.updateSchemas().get(update.getKey()), "contract.update." + update.getKey(), errors);
                    compatible(field, contract.dtoSchemas().get(update.getKey()), "contract.dto." + update.getKey(), errors);
                    var requestSchema = contract.updateSchemas().get(update.getKey());
                    var responseSchema = contract.dtoSchemas().get(update.getKey());
                    if (requestSchema != null && responseSchema != null
                            && !requestSchema.values().equals(responseSchema.values()))
                        errors.add("contract.dto." + update.getKey() + ": enum values differ from update request");
                }
            }
        }
        if (!errors.isEmpty()) throw new GenerationValidationException(errors);
        return context;
    }

    private static void compatible(FieldSpec entity, PackagedContractLoader.FieldSchema schema,
            String path, List<String> errors) {
        if (schema == null) { errors.add(path + ": field missing from contract"); return; }
        if (entity.nullable() != schema.nullable()) errors.add(path + ": nullability differs from entity field");
        String javaType = entity.javaType();
        String expected = switch (javaType) {
            case "String" -> "string:";
            case "UUID" -> "string:uuid";
            case "LocalDate" -> "string:date";
            case "LocalDateTime" -> "string:date-time";
            case "Integer" -> "integer:int32";
            case "Long" -> "integer:int64";
            case "BigDecimal" -> "number:";
            case "Boolean" -> "boolean:";
            default -> entity.enumType() == null ? "unsupported" : "string:";
        };
        if (!expected.equals(schema.type() + ":" + schema.format()))
            errors.add(path + ": type or format differs from entity field");
        if (entity.enumType() == null) {
            if (!schema.enumName().isEmpty() || !schema.values().isEmpty())
                errors.add(path + ": unexpected contract enum");
        } else if (!entity.enumType().equals(schema.enumName()) || schema.values().isEmpty())
            errors.add(path + ": enum differs from entity field");
    }
}
