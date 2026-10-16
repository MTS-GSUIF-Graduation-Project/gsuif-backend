package eg.mts.gsuif.generation;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** Resolves the trusted rendered POM before a provider request; Maven never sees model source. */
final class BackendMavenDependencyProvisioner {
    enum Reason { CONFIGURATION, TIMEOUT, RESOLUTION, MISSING_ARTIFACT, INVALID_ARTIFACT }
    static final class Failed extends RuntimeException {
        private final Reason reason;
        Failed(Reason reason) { super(reason.name()); this.reason = reason; }
        Reason reason() { return reason; }
    }
    enum Exit { SUCCESS, FAILURE, TIMEOUT }
    @FunctionalInterface interface Runner {
        Exit run(List<String> command, Path directory, Duration timeout) throws IOException, InterruptedException;
    }
    static final String DEPENDENCY_PLUGIN = "org.apache.maven.plugins:maven-dependency-plugin:3.9.0:build-classpath";
    static final Duration TIMEOUT = Duration.ofSeconds(90);
    private final Path executable;
    private final Path repository;
    private final Runner runner;

    BackendMavenDependencyProvisioner(Path executable, Path repository) {
        this(executable, repository, BackendMavenDependencyProvisioner::runMaven);
    }
    BackendMavenDependencyProvisioner(Path executable, Path repository, Runner runner) {
        if (executable == null || repository == null || runner == null) throw new Failed(Reason.CONFIGURATION);
        this.executable = executable.toAbsolutePath().normalize();
        this.repository = repository.toAbsolutePath().normalize();
        this.runner = runner;
    }

    static BackendMavenDependencyProvisioner local() {
        String command = System.getenv("GSUIF_MAVEN_COMMAND");
        String repository = System.getProperty("maven.repo.local", System.getenv("GSUIF_MAVEN_REPOSITORY"));
        if (command == null || command.isBlank() || repository == null || repository.isBlank())
            throw new Failed(Reason.CONFIGURATION);
        return new BackendMavenDependencyProvisioner(Path.of(command), Path.of(repository));
    }

    BackendCompileClasspath provision(BackendModelInput input) {
        if (input == null || !Files.isRegularFile(executable) || !Files.isDirectory(repository))
            throw new Failed(Reason.CONFIGURATION);
        Path staging = null;
        long deadline = System.nanoTime() + TIMEOUT.toNanos();
        try {
            staging = Files.createTempDirectory("gsuif-backend-dependencies-");
            Path pom = Files.write(staging.resolve("pom.xml"), input.pom());
            Path output = staging.resolve("classpath.txt");
            List<String> command = List.of(executable.toString(), "-B", "-ntp", "-f", pom.toString(),
                    DEPENDENCY_PLUGIN, "-DincludeScope=compile", "-Dmdep.outputFile=" + output,
                    "-Dmaven.repo.local=" + repository);
            Exit exit = runner.run(command, staging, remaining(deadline));
            if (exit == Exit.TIMEOUT) throw new Failed(Reason.TIMEOUT);
            remaining(deadline);
            if (exit != Exit.SUCCESS || !Files.isRegularFile(output) || Files.size(output) > 262144)
                throw new Failed(Reason.RESOLUTION);
            String classpath = Files.readString(output, StandardCharsets.UTF_8).trim();
            if (classpath.isBlank()) throw new Failed(Reason.RESOLUTION);
            List<Path> jars = new ArrayList<>();
            for (String item : classpath.split(java.util.regex.Pattern.quote(java.io.File.pathSeparator))) {
                if (item.isBlank()) throw new Failed(Reason.RESOLUTION);
                jars.add(Path.of(item));
            }
            return BackendCompileClasspath.fromRepository(input.pom(), repository, jars, deadline);
        } catch (BackendCompileClasspath.Failed ex) {
            if (ex.reason() == BackendCompileClasspath.Reason.TIMEOUT) throw new Failed(Reason.TIMEOUT);
            throw new Failed(ex.reason() == BackendCompileClasspath.Reason.MISSING_ARTIFACT
                    ? Reason.MISSING_ARTIFACT : Reason.INVALID_ARTIFACT);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new Failed(Reason.TIMEOUT);
        } catch (IOException | RuntimeException ex) {
            if (ex instanceof Failed failed) throw failed;
            throw new Failed(Reason.RESOLUTION);
        } finally {
            if (staging != null) try (var paths = Files.walk(staging)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
            } catch (IOException ignored) { }
        }
    }

    private static Duration remaining(long deadline) {
        long nanos = deadline - System.nanoTime();
        if (nanos <= 0) throw new Failed(Reason.TIMEOUT);
        return Duration.ofNanos(nanos);
    }

    private static Exit runMaven(List<String> command, Path directory, Duration timeout)
            throws IOException, InterruptedException {
        ProcessBuilder builder = new ProcessBuilder(command).directory(directory.toFile())
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD);
        removeProviderCredential(builder.environment());
        Process process = builder.start();
        try {
            if (!process.waitFor(timeout.toNanos(), TimeUnit.NANOSECONDS)) {
                stop(process);
                return Exit.TIMEOUT;
            }
            return process.exitValue() == 0 ? Exit.SUCCESS : Exit.FAILURE;
        } catch (InterruptedException ex) {
            stop(process);
            throw ex;
        }
    }

    static void removeProviderCredential(java.util.Map<String, String> environment) {
        environment.keySet().removeIf(key -> key.equalsIgnoreCase("OPENROUTER_API_KEY"));
    }

    private static void stop(Process process) {
        process.descendants().forEach(child -> child.destroyForcibly());
        process.destroyForcibly();
        try { process.waitFor(5, TimeUnit.SECONDS); }
        catch (InterruptedException ex) { Thread.currentThread().interrupt(); }
    }
}
