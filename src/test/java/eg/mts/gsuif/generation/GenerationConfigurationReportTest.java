package eg.mts.gsuif.generation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class GenerationConfigurationReportTest {
    private static final String IMAGE = "sha256:" + "a".repeat(64);
    private static final String SECRET = "disposable-secret-value";

    private static Map<String, String> complete() {
        Map<String, String> environment = new HashMap<>();
        environment.put("OPENROUTER_API_KEY", SECRET);
        environment.put("OPENROUTER_MODEL", OpenRouterBackendModelAdapter.MODEL);
        environment.put("GSUIF_BACKEND_COMPILER_IMAGE_ID", IMAGE);
        environment.put("GSUIF_BACKEND_BUILD_IMAGE_ID", IMAGE);
        environment.put("GSUIF_MAVEN_COMMAND", "C:\\maven\\bin\\mvn.cmd");
        environment.put("GSUIF_MAVEN_REPOSITORY", "C:\\m2\\repository");
        return environment;
    }

    @Test
    void completeSettingsHaveNoProblems() {
        assertEquals(List.of(), GenerationConfigurationReport.problems(complete()::get, name -> null));
    }

    @Test
    void missingSettingsAreNamedWithoutValues() {
        List<String> problems = GenerationConfigurationReport.problems(name -> null, name -> null);

        assertEquals(List.of("OPENROUTER_API_KEY is not set", "OPENROUTER_MODEL is not set",
                "GSUIF_BACKEND_COMPILER_IMAGE_ID is not set", "GSUIF_BACKEND_BUILD_IMAGE_ID is not set",
                "GSUIF_MAVEN_COMMAND is not set", "GSUIF_MAVEN_REPOSITORY is not set"), problems);
    }

    @Test
    void reportsWrongModelAndUnpinnedImagesWithoutEchoingValues() {
        Map<String, String> environment = complete();
        environment.put("OPENROUTER_MODEL", "other/model");
        environment.put("GSUIF_BACKEND_COMPILER_IMAGE_ID", "eclipse-temurin:21-jdk");
        environment.put("GSUIF_BACKEND_BUILD_IMAGE_ID", " ");

        List<String> problems = GenerationConfigurationReport.problems(environment::get, name -> null);

        assertEquals(List.of("GSUIF_BACKEND_BUILD_IMAGE_ID is not set",
                "OPENROUTER_MODEL is not the pinned model " + OpenRouterBackendModelAdapter.MODEL,
                "GSUIF_BACKEND_COMPILER_IMAGE_ID is not an immutable sha256 image ID"), problems);
        String joined = String.join(" ", problems);
        assertFalse(joined.contains("other/model"));
        assertFalse(joined.contains("eclipse-temurin"));
        assertFalse(joined.contains(SECRET));
    }

    @Test
    void acceptsTheMavenRepositoryFromTheJavaProperty() {
        Map<String, String> environment = complete();
        environment.remove("GSUIF_MAVEN_REPOSITORY");

        assertTrue(GenerationConfigurationReport.problems(environment::get,
                name -> "maven.repo.local".equals(name) ? "C:\\m2\\repository" : null).isEmpty());
    }
}
