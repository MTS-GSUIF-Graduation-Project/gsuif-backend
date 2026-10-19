package eg.mts.gsuif.generation;

import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** Process and disposable-container mechanics shared by standalone backend acceptance tests. */
final class StandaloneBackendTestSupport {
    private final Path scratch;
    private final Path evidence;

    record CommandResult(int exit, String output) { }

    StandaloneBackendTestSupport(Path scratch) {
        this.scratch = scratch;
        this.evidence = Path.of(System.getProperty("gsuif.test.startup-evidence-dir"))
                .toAbsolutePath().normalize();
        assertFalse(evidence.startsWith(scratch.toAbsolutePath().normalize()));
    }

    Path buildJar(String label, Path extracted) throws Exception {
        Path backend = extracted.resolve("backend");
        Path log = scratch.resolve(label + "-build.log");
        var command = List.of(Path.of(System.getProperty("gsuif.test.maven-command")).toAbsolutePath().toString(),
                "-B", "-ntp", "-o", "-q", "-f", backend.resolve("pom.xml").toString(),
                "-Dmaven.repo.local=" + Path.of(System.getProperty("maven.repo.local")).toAbsolutePath(),
                "-DskipTests", "package");
        ProcessBuilder builder = new ProcessBuilder(command).directory(backend.toFile())
                .redirectErrorStream(true).redirectOutput(log.toFile());
        removeProviderCredentials(builder.environment());
        Process process = builder.start();
        try {
            assertTrue(process.waitFor(Duration.ofMinutes(4).toSeconds(), TimeUnit.SECONDS),
                    label + " Maven package timed out");
            assertEquals(0, process.exitValue(), label + " Maven package failed: " + safeLines(log));
        } finally { stop(process); }
        try (var stream = Files.list(backend.resolve("target"))) {
            var jars = stream.filter(path -> path.toString().endsWith(".jar")
                    && !path.toString().endsWith(".jar.original")).toList();
            assertEquals(1, jars.size());
            return jars.getFirst();
        }
    }

    Process startJar(Path jar, String url, String username, String password, String jwt,
            int port, Path log) throws Exception {
        return startJar(jar, List.of("--spring.profiles.active=test", "--server.address=127.0.0.1",
                "--server.port=" + port, "--spring.main.banner-mode=off"),
                Map.of("GENERATED_APP_DB_URL", url, "GENERATED_APP_DB_USER", username,
                        "GENERATED_APP_DB_PASSWORD", password, "GENERATED_APP_JWT_SECRET", jwt), log);
    }

    Process bootstrapJar(Path jar, String url, String username, String password,
            String admin, String adminPassword, Path log) throws Exception {
        return startJar(jar, List.of("bootstrap-admin"),
                Map.of("GENERATED_APP_DB_URL", url, "GENERATED_APP_DB_USER", username,
                        "GENERATED_APP_DB_PASSWORD", password,
                        "GENERATED_APP_BOOTSTRAP_USERNAME", admin,
                        "GENERATED_APP_BOOTSTRAP_PASSWORD", adminPassword), log);
    }

    private Process startJar(Path jar, List<String> arguments, Map<String, String> settings, Path log) throws Exception {
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        var command = new java.util.ArrayList<String>();
        command.add(java); command.add("-jar"); command.add(jar.toString()); command.addAll(arguments);
        ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(log.toFile());
        Map<String, String> environment = builder.environment();
        String systemRoot = environment.get("SystemRoot");
        String temp = environment.get("TEMP");
        String tmp = environment.get("TMP");
        environment.clear();
        if (systemRoot != null) environment.put("SystemRoot", systemRoot);
        if (temp != null) environment.put("TEMP", temp);
        if (tmp != null) environment.put("TMP", tmp);
        environment.putAll(settings);
        assertFalse(environment.containsKey("OPENROUTER_API_KEY"));
        return builder.start();
    }

    CommandResult docker(List<String> arguments) throws Exception {
        var command = new java.util.ArrayList<String>();
        command.add("docker"); command.addAll(arguments);
        ProcessBuilder builder = new ProcessBuilder(command).directory(scratch.toFile()).redirectErrorStream(true);
        removeProviderCredentials(builder.environment());
        Path log = scratch.resolve("docker-command-" + UUID.randomUUID() + ".log");
        builder.redirectOutput(log.toFile());
        Process process = builder.start();
        try {
            if (!process.waitFor(20, TimeUnit.SECONDS)) {
                stop(process);
                return new CommandResult(-1, "docker command timed out");
            }
            return new CommandResult(process.exitValue(), Files.readString(log, StandardCharsets.UTF_8).trim());
        } finally { stop(process); Files.deleteIfExists(log); }
    }

    void cleanupOwned(String label, String ownershipLabel, Path envFile, String password) throws Exception {
        StringBuilder cleanup = new StringBuilder();
        try {
            CommandResult owned = docker(List.of("ps", "-a", "-q", "--filter", "label=" + ownershipLabel));
            cleanup.append("owned container lookup: exit=").append(owned.exit()).append('\n')
                    .append(owned.output()).append('\n');
            assertEquals(0, owned.exit(), "Could not inspect test-owned disposable containers");
            for (String id : owned.output().lines().filter(line -> line.matches("[a-f0-9]{12,64}")).toList()) {
                CommandResult removed = docker(List.of("rm", "-f", id));
                cleanup.append("container removal: exit=").append(removed.exit()).append('\n')
                        .append(removed.output()).append('\n');
                assertEquals(0, removed.exit(), "Could not remove created disposable container");
            }
            CommandResult remaining = docker(List.of("ps", "-a", "-q", "--filter", "label=" + ownershipLabel));
            cleanup.append("remaining owned containers: exit=").append(remaining.exit()).append('\n')
                    .append(remaining.output()).append('\n');
            assertEquals(0, remaining.exit(), "Could not verify test-owned container cleanup");
            assertTrue(remaining.output().isBlank(), "Test-owned disposable container remains");
        } finally {
            Files.deleteIfExists(envFile);
            preserveEvidence(label + "-container-cleanup.txt", cleanup.toString(), password, null);
        }
    }

    void preserveEvidence(Path source, String password, String jwt) throws Exception {
        preserveEvidence(source.getFileName().toString(), Files.readString(source, StandardCharsets.UTF_8),
                password, jwt);
    }

    private void preserveEvidence(String name, String content, String password, String jwt) throws Exception {
        Files.createDirectories(evidence);
        String safe = content.replace(password, "[redacted]");
        if (jwt != null) safe = safe.replace(jwt, "[redacted]");
        Files.writeString(evidence.resolve(name), safe, StandardCharsets.UTF_8);
    }

    static void stop(Process process) throws InterruptedException {
        if (process == null || !process.isAlive()) return;
        process.descendants().forEach(ProcessHandle::destroyForcibly);
        process.destroyForcibly();
        process.waitFor(5, TimeUnit.SECONDS);
    }

    static int freePort() throws Exception {
        try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            return socket.getLocalPort();
        }
    }

    private static void removeProviderCredentials(Map<String, String> environment) {
        environment.keySet().removeIf(key -> key.equalsIgnoreCase("OPENROUTER_API_KEY")
                || key.equalsIgnoreCase("OPENROUTER_MODEL"));
    }

    static String safeLines(Path log) throws Exception {
        if (!Files.isRegularFile(log)) return "no child log";
        return Files.readAllLines(log, StandardCharsets.UTF_8).stream()
                .filter(line -> line.contains("ERROR") || line.contains("APPLICATION FAILED")
                        || line.contains("Description:"))
                .limit(8)
                .map(line -> line.replaceAll("(?i)(password|token|authorization|secret|api.key)[=: ]+\\S+", "$1=[redacted]"))
                .map(line -> line.length() > 180 ? line.substring(0, 180) : line)
                .reduce("", (a, b) -> a + "\n" + b);
    }
}
