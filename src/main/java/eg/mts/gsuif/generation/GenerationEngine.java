package eg.mts.gsuif.generation;

import eg.mts.gsuif.entity.MetadataVersion;
import java.util.Set;
import eg.mts.gsuif.generation.GenerationContext.Target;

/** Selects a provider without coupling the metadata model to an implementation. */
public final class GenerationEngine {
    private final GenerationContextBuilder contextBuilder;
    private final AICodeGenerationProvider provider;

    public GenerationEngine(GenerationContextBuilder contextBuilder, AICodeGenerationProvider provider) {
        this.contextBuilder = java.util.Objects.requireNonNull(contextBuilder);
        this.provider = java.util.Objects.requireNonNull(provider);
    }

    public static GenerationEngine templateOnly(GenerationContextBuilder contextBuilder) {
        return new GenerationEngine(contextBuilder, new TemplateOnlyProvider(contextBuilder));
    }

    public GenerationResult generate(MetadataVersion version, GenerationSpecification specification) {
        return generateValidated(contextBuilder.build(version, specification));
    }

    public GenerationResult generate(MetadataVersion version, GenerationSpecification specification,
                                     Set<Target> targets, String framework) {
        return generateValidated(contextBuilder.build(version, specification, targets, framework));
    }

    private GenerationResult generateValidated(GenerationContext context) {
        return provider instanceof TemplateOnlyProvider template
                ? template.generateValidated(context) : provider.generate(context);
    }
}
