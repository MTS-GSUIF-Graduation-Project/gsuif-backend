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
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;

/**
 * Stages a standalone consumer and validates Java output with sequential Maven goals.
 * Abrupt JVM termination can leave a staging directory under target/generation-builds.
 * Each validation uses a new directory, so an orphan is never reused. Normal completion
 * removes its directory. An operator can remove leftovers when no validation is active;
 * no startup sweep runs because it could delete an active build.
 */
public final class ConsumerBuildValidator {
    public static final int OUTPUT_LIMIT = 16000;
    private static final Duration COMMAND_LIMIT = Duration.ofMinutes(5);
    private static final String TRUNCATION_MARKER = "\n[build output truncated]\n";
    private static final int OUTPUT_HEAD = 4000;
    private static final int OUTPUT_TAIL = OUTPUT_LIMIT - OUTPUT_HEAD - TRUNCATION_MARKER.length();

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
            for (var artifact : result.artifacts()) {
                Path path = safeRelativePath(artifact.relativePath());
                if (isConsumerInput(path)) stage(project, path, artifact.bytes(), staged);
                else if (!isAngularArtifact(path)) throw new IllegalArgumentException("Unsupported consumer input path: " + path);
            }
            for (var input : extraInputs.entrySet()) {
                Path path = safeRelativePath(input.getKey());
                if (!isConsumerInput(path)) throw new IllegalArgumentException("Unsupported consumer input path: " + path);
                stage(project, path, input.getValue(), staged);
            }
            if (!hasConsumerTestSource(project, staged))
                return failure(null, "Generated consumer behavioral tests are missing");
            Files.writeString(project.resolve("pom.xml"), result.consumerBuild().mavenPom("com.example", "generated-consumer"));
            compile = runner.run(project, "compile");
            if (!compile.passed()) return new BuildResult(compile, null);
            CommandResult test = runner.run(project, "test");
            if (!test.passed()) return new BuildResult(compile, test);
            if (executedTests(project) == 0)
                return failure(compile, "Generated consumer behavioral tests were not executed (no unskipped Surefire tests)");
            return new BuildResult(compile, test);
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

    private static Path safeRelativePath(String relative) {
        if (relative == null || relative.isBlank() || relative.indexOf('\\') >= 0 || relative.indexOf(':') >= 0)
            throw new IllegalArgumentException("Unsafe consumer input path");
        Path path = Path.of(relative);
        if (path.isAbsolute() || !path.normalize().equals(path) || path.toString().equals("."))
            throw new IllegalArgumentException("Unsafe consumer input path: " + relative);
        for (Path part : path)
            if (part.toString().equals(".") || part.toString().equals(".."))
                throw new IllegalArgumentException("Unsafe consumer input path: " + relative);
        return path;
    }

    private static boolean isConsumerInput(Path path) {
        String value = path.toString().replace('\\', '/');
        if (value.endsWith(".class") || value.endsWith(".jar")) return false;
        return ((value.startsWith("src/main/java/") || value.startsWith("src/test/java/")) && value.endsWith(".java"))
                || value.startsWith("src/main/resources/") || value.startsWith("src/test/resources/");
    }

    private static boolean isAngularArtifact(Path path) {
        String value = path.toString().replace('\\', '/');
        return value.startsWith("src/app/generated/") && (value.endsWith(".ts") || value.endsWith(".html"));
    }

    private static boolean hasConsumerTestSource(Path project, Set<Path> staged) {
        return staged.stream().map(project::relativize).map(path -> path.toString().replace('\\', '/'))
                .anyMatch(path -> path.startsWith("src/test/java/") && path.endsWith(".java"));
    }

    private static int executedTests(Path project) throws IOException {
        Path reports = project.resolve("target/surefire-reports");
        if (!Files.isDirectory(reports)) return 0;
        int executed = 0;
        try (var files = Files.list(reports)) {
            for (Path report : files.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().matches("TEST-.*\\.xml")).toList()) {
                XMLInputFactory factory = XMLInputFactory.newFactory();
                factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
                factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
                try (var input = Files.newInputStream(report)) {
                    XMLStreamReader xml = factory.createXMLStreamReader(input);
                    try {
                        while (xml.hasNext() && xml.next() != javax.xml.stream.XMLStreamConstants.START_ELEMENT) { }
                        if (!xml.isStartElement() || !"testsuite".equals(xml.getLocalName()))
                            throw new IOException("Invalid Surefire report: " + report.getFileName());
                        int tests = Integer.parseInt(xml.getAttributeValue(null, "tests"));
                        int skipped = Integer.parseInt(xml.getAttributeValue(null, "skipped"));
                        if (tests < 0 || skipped < 0 || skipped > tests)
                            throw new IOException("Invalid Surefire test counts: " + report.getFileName());
                        executed = Math.addExact(executed, tests - skipped);
                    } finally { xml.close(); }
                } catch (XMLStreamException | IllegalArgumentException | ArithmeticException ex) {
                    throw new IOException("Invalid Surefire report: " + report.getFileName(), ex);
                }
            }
        }
        return executed;
    }

    private static void stage(Path root, Path relative, byte[] bytes, Set<Path> staged) throws IOException {
        if (bytes == null) throw new IllegalArgumentException("Consumer input bytes are missing: " + relative);
        Path destination = root.resolve(relative).normalize();
        if (!destination.startsWith(root) || destination.equals(root))
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
        if ("test".equals(goal)) command.add("-DfailIfNoTests=true");
        if (Boolean.getBoolean("gsuif.build.maven.offline")) command.add("-o");
        String repository = System.getProperty("maven.repo.local");
        if (repository != null && !repository.isBlank())
            command.add("-Dmaven.repo.local=" + Path.of(repository).toAbsolutePath().normalize());
        Process process = new ProcessBuilder(command).directory(project.toFile()).redirectErrorStream(true).start();
        OutputCapture output = new OutputCapture();
        Thread reader = Thread.ofVirtual().start(() -> {
            try (var stream = new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8)) {
                char[] chunk = new char[2048];
                int count;
                while ((count = stream.read(chunk)) != -1) {
                    output.append(chunk, count);
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
        return new CommandResult(finished ? process.exitValue() : null,
                finished ? output.result() : output.resultWithSuffix("\nMaven " + goal + " timed out"));
    }

    private static void stop(Process process) {
        process.descendants().forEach(child -> child.destroyForcibly());
        process.destroyForcibly();
    }

    private static String bounded(String output) {
        String value = output == null ? "" : output;
        return value.length() <= OUTPUT_LIMIT ? value
                : value.substring(0, OUTPUT_HEAD) + TRUNCATION_MARKER + value.substring(value.length() - OUTPUT_TAIL);
    }

    static final class OutputCapture {
        private final StringBuilder output = new StringBuilder();
        private boolean truncated;

        synchronized void append(char[] chunk, int count) {
            output.append(chunk, 0, count);
            trim();
        }

        synchronized void append(String message) {
            output.append(message);
            trim();
        }

        private void trim() {
            if (output.length() > (truncated ? OUTPUT_LIMIT - TRUNCATION_MARKER.length() : OUTPUT_LIMIT)) {
                output.delete(OUTPUT_HEAD, output.length() - OUTPUT_TAIL);
                truncated = true;
            }
        }

        synchronized String result() {
            return truncated ? output.substring(0, OUTPUT_HEAD) + TRUNCATION_MARKER + output.substring(OUTPUT_HEAD)
                    : output.toString();
        }

        synchronized String resultWithSuffix(String suffix) {
            append(suffix);
            return result();
        }
    }

    private static void deleteTree(Path root) {
        try (var paths = Files.walk(root)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
        } catch (IOException ignored) { /* build result remains available even if cleanup fails */ }
    }
}
