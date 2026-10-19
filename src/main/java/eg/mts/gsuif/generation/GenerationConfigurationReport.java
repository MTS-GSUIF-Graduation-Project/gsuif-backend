package eg.mts.gsuif.generation;

import java.util.ArrayList;
import java.util.List;
import java.util.function.UnaryOperator;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Reports at startup whether backend generation settings are present. It logs variable names
 * only, never values, and never stops startup: everything except generation works without them.
 */
@Component
final class GenerationConfigurationReport {
    private static final Logger log = LoggerFactory.getLogger(GenerationConfigurationReport.class);
    private static final Pattern IMAGE = Pattern.compile("sha256:[0-9a-f]{64}");
    private static final String FIX = "Run scripts\\Initialize-GsuifLocal.ps1 once, then start with scripts\\Start-GsuifLocal.ps1 "
            + "(or -WithoutGeneration for other work).";

    @EventListener(ApplicationReadyEvent.class)
    void report() {
        List<String> problems = problems(System::getenv, System::getProperty);
        if (problems.isEmpty()) {
            log.info("Backend generation settings are present (presence and format only; images, Maven cache and provider are checked when used).");
        } else {
            log.warn("Backend generation is not configured; generation requests will fail with CONFIG_MISSING. Problems: {}. {}",
                    String.join("; ", problems), FIX);
        }
    }

    static List<String> problems(UnaryOperator<String> environment, UnaryOperator<String> properties) {
        List<String> problems = new ArrayList<>();
        for (String name : List.of("OPENROUTER_API_KEY", "OPENROUTER_MODEL", "GSUIF_BACKEND_COMPILER_IMAGE_ID",
                "GSUIF_BACKEND_BUILD_IMAGE_ID", "GSUIF_MAVEN_COMMAND")) {
            if (blank(environment.apply(name))) problems.add(name + " is not set");
        }
        if (blank(properties.apply("maven.repo.local")) && blank(environment.apply("GSUIF_MAVEN_REPOSITORY")))
            problems.add("GSUIF_MAVEN_REPOSITORY is not set");
        String model = environment.apply("OPENROUTER_MODEL");
        if (!blank(model) && !OpenRouterBackendModelAdapter.MODEL.equals(model))
            problems.add("OPENROUTER_MODEL is not the pinned model " + OpenRouterBackendModelAdapter.MODEL);
        for (String name : List.of("GSUIF_BACKEND_COMPILER_IMAGE_ID", "GSUIF_BACKEND_BUILD_IMAGE_ID")) {
            String image = environment.apply(name);
            if (!blank(image) && !IMAGE.matcher(image).matches()) problems.add(name + " is not an immutable sha256 image ID");
        }
        return List.copyOf(problems);
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
