package eg.mts.gsuif.generation;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.HashMap;

import static org.junit.jupiter.api.Assertions.*;

class IsolatedBackendDockerSupportTest {
    private static final String IMAGE = "sha256:" + "a".repeat(64);

    @Test void mockedOrchestrationUsesOnlyInternalNetworkAndCleansOwnedResources() throws Exception {
        List<List<String>> commands = new ArrayList<>();
        var runner = (IsolatedBackendDockerSupport.Runner) (command, limit) -> {
            commands.add(command);
            if (command.contains("inspect")) return new IsolatedBackendDockerSupport.Result(0, "true");
            if (command.contains("ps")) return new IsolatedBackendDockerSupport.Result(0, "");
            if (command.contains("ls")) return new IsolatedBackendDockerSupport.Result(0, "");
            return new IsolatedBackendDockerSupport.Result(0, "ok");
        };
        try (var docker = new IsolatedBackendDockerSupport(runner)) {
            docker.createInternalNetwork();
            String volume = docker.volume("package");
            docker.create(List.of("--network=none", "--mount=type=volume,src=" + volume + ",dst=/package",
                    IMAGE, "sleep", "600"), "stage");
            var restricted = IsolatedBackendDockerSupport.restricted(IMAGE, docker.network(), "65532:65532",
                    512, volume, "/package", true);
            docker.runDetached(restricted, "app");
            assertTrue(restricted.contains("--read-only"));
            assertTrue(restricted.contains("--cap-drop=ALL"));
            assertTrue(restricted.contains("--security-opt=no-new-privileges"));
            assertTrue(restricted.contains("--user=65532:65532"));
            assertTrue(restricted.stream().anyMatch(value -> value.endsWith(",dst=/package,readonly")));
            assertFalse(String.join(" ", restricted).contains("OPENROUTER"));
            assertFalse(String.join(" ", restricted).contains("docker.sock"));
            assertFalse(String.join(" ", restricted).contains("type=bind"));
        }
        assertTrue(commands.stream().anyMatch(c -> c.contains("--internal")));
        assertTrue(commands.stream().anyMatch(c -> c.contains("rm") && c.contains("-f")));
        assertTrue(commands.stream().anyMatch(c -> c.contains("volume") && c.contains("rm")));
        assertTrue(commands.stream().anyMatch(c -> c.contains("network") && c.contains("rm")));
    }

    @Test void rejectsMutableImagesAndCleanupFailure() throws Exception {
        assertThrows(IllegalArgumentException.class, () -> IsolatedBackendDockerSupport.restricted(
                "eclipse-temurin:21-jdk", "network", "65532:65532", 512, null, null, false));
        var docker = new IsolatedBackendDockerSupport((command, limit) ->
                new IsolatedBackendDockerSupport.Result(command.contains("rm") ? 1 : 0,
                        command.contains("volume") && command.contains("ls") ? "owned-volume" : ""));
        docker.volume("owned");
        assertEquals("ISOLATED_CLEANUP_UNCONFIRMED",
                assertThrows(IllegalStateException.class, docker::close).getMessage());
    }

    @Test void childEnvironmentDropsDisposableProviderCredentials() {
        var environment = new HashMap<String, String>();
        environment.put("OPENROUTER_API_KEY", "disposable-fake-key");
        environment.put("OpenRouter_Model", "fake/model");
        environment.put("PATH", "disposable-path");
        IsolatedBackendDockerSupport.removeProviderCredentials(environment);
        assertEquals("disposable-path", environment.get("PATH"));
        assertFalse(environment.keySet().stream().anyMatch(key -> key.equalsIgnoreCase("OPENROUTER_API_KEY")
                || key.equalsIgnoreCase("OPENROUTER_MODEL")));
    }
}
