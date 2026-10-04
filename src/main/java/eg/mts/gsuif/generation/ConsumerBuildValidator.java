package eg.mts.gsuif.generation;

import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/** Stages a standalone consumer and validates Java output with sequential Maven goals. */
public final class ConsumerBuildValidator {
    public static final int OUTPUT_LIMIT = 16000;
    private static final Duration COMMAND_LIMIT = Duration.ofMinutes(5);

    public record CommandResult(Integer exitCode, String output) {
        public CommandResult { output = bounded(output); }
        public boolean passed() { return exitCode != null && exitCode == 0; }
    }
    public record BuildResult(CommandResult compile, CommandResult test) {
        public boolean passed() { return compile.passed() && test != null && test.passed(); }
    }
    @FunctionalInterface public interface CommandRunner {
        CommandResult run(Path project, String goal) throws IOException, InterruptedException;
    }

    private final Path stagingRoot;
    private final CommandRunner runner;

    public ConsumerBuildValidator(Path stagingRoot) {
        this(stagingRoot, ConsumerBuildValidator::runMaven);
    }

    public ConsumerBuildValidator(Path stagingRoot, CommandRunner runner) {
        this.stagingRoot = Objects.requireNonNull(stagingRoot).toAbsolutePath().normalize();
        this.runner = Objects.requireNonNull(runner);
    }

    /** Extra inputs contain the selected OpenAPI interface, DTOs, service and consumer tests. */
    public BuildResult validate(GenerationResult result, Map<String, byte[]> extraInputs) {
        Objects.requireNonNull(result);
        Objects.requireNonNull(extraInputs);
        if (result.consumerBuild() == null)
            return new BuildResult(new CommandResult(null, "Java consumer build contract is missing"), null);
        if (result.artifacts().stream().noneMatch(artifact -> artifact.relativePath() != null
                && artifact.relativePath().startsWith("src/main/java/") && artifact.relativePath().endsWith(".java")))
            return new BuildResult(new CommandResult(null, "Generated Java sources are missing"), null);
        Path project = null;
        CommandResult compile = null;
        try {
            Files.createDirectories(stagingRoot);
            project = Files.createTempDirectory(stagingRoot, "consumer-");
            Set<Path> staged = new HashSet<>();
            for (var artifact : result.artifacts()) stage(project, artifact.relativePath(), artifact.bytes(), staged);
            for (var input : extraInputs.entrySet()) stage(project, input.getKey(), input.getValue(), staged);
            Files.writeString(project.resolve("pom.xml"), result.consumerBuild().mavenPom("com.example", "generated-consumer"));
            compile = runner.run(project, "compile");
            if (!compile.passed()) return new BuildResult(compile, null);
            return new BuildResult(compile, runner.run(project, "test"));
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return failure(compile, "Build validation interrupted");
        } catch (IOException | IllegalArgumentException ex) {
            return failure(compile, "Build validation could not run: " + ex.getMessage());
        } finally {
            if (project != null) deleteTree(project);
        }
    }

    private static BuildResult failure(CommandResult compile, String message) {
        return compile == null ? new BuildResult(new CommandResult(null, message), null)
                : new BuildResult(compile, new CommandResult(null, message));
    }

    private static void stage(Path root, String relative, byte[] bytes, Set<Path> staged) throws IOException {
        if (relative == null || bytes == null || relative.indexOf('\\') >= 0 || relative.indexOf(':') >= 0)
            throw new IllegalArgumentException("Unsafe consumer input path");
        Path destination = root.resolve(relative).normalize();
        if (!destination.startsWith(root) || destination.equals(root) || destination.equals(root.resolve("pom.xml")))
            throw new IllegalArgumentException("Unsafe consumer input path: " + relative);
        if (!staged.add(destination)) throw new IllegalArgumentException("Duplicate consumer input path: " + relative);
        Files.createDirectories(destination.getParent());
        Files.write(destination, bytes);
    }

    private static CommandResult runMaven(Path project, String goal) throws IOException, InterruptedException {
        String configured = System.getenv("GSUIF_MAVEN_COMMAND");
        String executable = configured != null && !configured.isBlank() ? configured
                : Path.of(System.getProperty("user.dir"), System.getProperty("os.name").startsWith("Windows")
                ? "mvnw.cmd" : "mvnw").toString();
        List<String> command = new ArrayList<>(List.of(executable, "-B", "-f", project.resolve("pom.xml").toAbsolutePath().toString(), goal));
        if (Boolean.getBoolean("gsuif.build.maven.offline")) command.add("-o");
        String repository = System.getProperty("maven.repo.local");
        if (repository != null && !repository.isBlank())
            command.add("-Dmaven.repo.local=" + Path.of(repository).toAbsolutePath().normalize());
        Process process = new ProcessBuilder(command).directory(project.toFile()).redirectErrorStream(true).start();
        StringBuilder output = new StringBuilder();
        Thread reader = Thread.ofVirtual().start(() -> {
            try (var stream = new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8)) {
                char[] chunk = new char[2048];
                int count;
                while ((count = stream.read(chunk)) != -1) {
                    synchronized (output) {
                        int remaining = OUTPUT_LIMIT - output.length();
                        if (remaining > 0) output.append(chunk, 0, Math.min(count, remaining));
                    }
                }
            } catch (IOException ignored) { /* process termination closes the pipe */ }
        });
        boolean finished;
        try {
            finished = process.waitFor(COMMAND_LIMIT.toMillis(), TimeUnit.MILLISECONDS);
            if (!finished) {
                stop(process);
                process.waitFor();
            }
        } catch (InterruptedException ex) {
            stop(process);
            throw ex;
        }
        reader.join(5000);
        String captured;
        synchronized (output) { captured = output.toString(); }
        return new CommandResult(finished ? process.exitValue() : null,
                captured + (finished ? "" : "\nMaven " + goal + " timed out"));
    }

    private static void stop(Process process) {
        process.descendants().forEach(child -> child.destroyForcibly());
        process.destroyForcibly();
    }

    private static String bounded(String output) {
        String value = output == null ? "" : output;
        return value.length() <= OUTPUT_LIMIT ? value : value.substring(0, OUTPUT_LIMIT);
    }

    private static void deleteTree(Path root) {
        try (var paths = Files.walk(root)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
        } catch (IOException ignored) { /* build result remains available even if cleanup fails */ }
    }
}
