package eg.mts.gsuif.generation;

import com.fasterxml.jackson.databind.ObjectMapper;
import eg.mts.gsuif.entity.GenerationRun;
import eg.mts.gsuif.entity.GsuifUser;
import eg.mts.gsuif.entity.MetadataVersion;
import eg.mts.gsuif.generation.GenerationContext.Target;
import eg.mts.gsuif.validator.MetadataSchemaValidator;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Production entry point for a completed generation and consumer build attempt. */
@Service
public class GenerationWorkflow {
    private final GenerationEngine engine;
    private final GenerationRunService runs;

    @Autowired
    public GenerationWorkflow(MetadataSchemaValidator schemaValidator, GenerationRunService runs) {
        this(GenerationEngine.templateOnly(new GenerationContextBuilder(new ObjectMapper(), schemaValidator)), runs);
    }

    GenerationWorkflow(GenerationEngine engine, GenerationRunService runs) {
        this.engine = Objects.requireNonNull(engine);
        this.runs = Objects.requireNonNull(runs);
    }

    /** A retry must pass the same attempt ID. Generation failures before a result is returned are not recorded. */
    public CompletedAttempt generateAndRecord(UUID attemptId, MetadataVersion version, GsuifUser user,
            GenerationSpecification specification, Set<Target> targets, String framework,
            Map<String, byte[]> consumerInputs) {
        Objects.requireNonNull(attemptId);
        GenerationResult result = engine.generate(version, specification, targets, framework);
        GenerationRun run = runs.validateAndRecord(attemptId, version, user, result, consumerInputs);
        return new CompletedAttempt(result, run);
    }

    public record CompletedAttempt(GenerationResult result, GenerationRun run) { }
}
