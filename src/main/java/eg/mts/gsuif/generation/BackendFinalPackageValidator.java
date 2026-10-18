package eg.mts.gsuif.generation;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/** Offline executable-JAR build of the exact assembled bytes in a bounded, non-root container. */
final class BackendFinalPackageValidator {
    enum Failure { CONFIG, INTEGRITY, BUILD, TIMEOUT, CLEANUP }
    static final class Failed extends RuntimeException {
        private final Failure reason;
        Failed(Failure reason) { super(reason.name()); this.reason = reason; }
        Failure reason() { return reason; }
    }
    enum Exit { SUCCESS, FAILURE, TIMEOUT }
    @FunctionalInterface interface Runner {
        Exit run(List<String> command, Duration timeout) throws IOException, InterruptedException;
    }
    private static final Pattern IMAGE = Pattern.compile("sha256:[0-9a-f]{64}");
    private static final Duration LIMIT = Duration.ofMinutes(4);
    private final String image;
    private final Path repository;
    private final Path mavenHome;
    private final Runner runner;

    BackendFinalPackageValidator(String image, Path repository, Path mavenHome) {
        this(image, repository, mavenHome, BackendFinalPackageValidator::process);
    }

    BackendFinalPackageValidator(String image, Path repository, Path mavenHome, Runner runner) {
        if (image == null || !IMAGE.matcher(image).matches() || repository == null || mavenHome == null || runner == null)
            throw new Failed(Failure.CONFIG);
        this.image = image;
        this.repository = repository.toAbsolutePath().normalize();
        this.mavenHome = mavenHome.toAbsolutePath().normalize();
        this.runner = runner;
    }

    static BackendFinalPackageValidator local() {
        String image = System.getenv("GSUIF_BACKEND_BUILD_IMAGE_ID");
        String repository = System.getProperty("maven.repo.local", System.getenv("GSUIF_MAVEN_REPOSITORY"));
        String command = System.getenv("GSUIF_MAVEN_COMMAND");
        if (repository == null || repository.isBlank() || command == null || command.isBlank())
            throw new Failed(Failure.CONFIG);
        Path executable = Path.of(command).toAbsolutePath().normalize();
        if (executable.getParent() == null || executable.getParent().getParent() == null)
            throw new Failed(Failure.CONFIG);
        return new BackendFinalPackageValidator(image, Path.of(repository), executable.getParent().getParent());
    }

    void validate(CompleteBackendPackageAssembler.Assembled assembled) {
        if (assembled == null || !Files.isDirectory(repository)
                || !Files.isRegularFile(mavenHome.resolve("bin").resolve("mvn")))
            throw new Failed(Failure.CONFIG);
        Path staged = null;
        String container = "gsuif-backend-build-" + UUID.randomUUID().toString().replace("-", "");
        boolean started = false;
        long deadline = System.nanoTime() + LIMIT.toNanos();
        try {
            staged = Files.createTempDirectory("gsuif-backend-final-");
            for (Map.Entry<String, byte[]> entry : assembled.files().entrySet()) {
                Path relative = GenerationArtifactStore.safeRelative(entry.getKey());
                Path file = staged.resolve(relative).normalize();
                if (!file.startsWith(staged)) throw new Failed(Failure.INTEGRITY);
                Files.createDirectories(file.getParent());
                Files.write(file, entry.getValue());
                if (!java.util.Arrays.equals(Files.readAllBytes(file), entry.getValue()))
                    throw new Failed(Failure.INTEGRITY);
            }
            PosixCompileStaging.makeContainerReadable(staged);
            List<String> command = command(image, container, staged, repository, mavenHome);
            started = true;
            Exit exit = runner.run(command, remaining(deadline));
            if (exit == Exit.TIMEOUT) throw new Failed(Failure.TIMEOUT);
            if (exit != Exit.SUCCESS) throw new Failed(Failure.BUILD);
        } catch (IOException ex) {
            throw new Failed(Failure.BUILD);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new Failed(Failure.TIMEOUT);
        } finally {
            boolean interrupted = Thread.interrupted(); // Allow the bounded cleanup wait to run.
            boolean cleaned = true;
            try {
                if (started) try {
                    cleaned = runner.run(List.of("docker", "rm", "-f", container), Duration.ofSeconds(10)) == Exit.SUCCESS;
                } catch (InterruptedException ex) {
                    interrupted = true;
                    cleaned = false;
                } catch (IOException | RuntimeException ex) {
                    cleaned = false;
                }
            } finally {
                if (!deleteStaged(staged)) cleaned = false;
                if (interrupted) Thread.currentThread().interrupt();
            }
            if (!cleaned) throw new Failed(Failure.CLEANUP);
        }
    }

    private static boolean deleteStaged(Path staged) {
        if (staged == null || Files.notExists(staged)) return true;
        boolean deleted = true;
        try (var files = Files.walk(staged)) {
            for (Path file : files.sorted(Comparator.reverseOrder()).toList()) try {
                Files.deleteIfExists(file);
            } catch (IOException | RuntimeException ex) {
                deleted = false;
            }
        } catch (IOException | RuntimeException ex) {
            return false;
        }
        return deleted && Files.notExists(staged);
    }

    static List<String> command(String image, String container, Path staged, Path repository, Path mavenHome) {
        String script = "cp -R /input/backend /work/backend && cd /work/backend && "
                + "/maven/bin/mvn -B -ntp -o -q -Dmaven.repo.local=/repo -Dmaven.compiler.proc=none "
                + "-DskipTests package >/tmp/build.log 2>&1 && "
                + "find target -maxdepth 1 -name '*.jar' ! -name '*.jar.original' -print -quit | grep -q .";
        List<String> args = new ArrayList<>(List.of("docker", "run", "--pull=never", "--name", container,
                "--network=none", "--read-only", "--ipc=none", "--cap-drop=ALL",
                "--security-opt=no-new-privileges", "--pids-limit=128", "--memory=1536m", "--cpus=2",
                "--user=65532:65532", "--log-driver=none",
                "--tmpfs=/work:rw,exec,nosuid,size=768m,mode=1777",
                "--tmpfs=/tmp:rw,noexec,nosuid,size=64m,mode=1777",
                "--mount=type=bind,src=" + staged.toAbsolutePath() + ",dst=/input,readonly",
                "--mount=type=bind,src=" + repository + ",dst=/repo,readonly",
                "--mount=type=bind,src=" + mavenHome + ",dst=/maven,readonly",
                "--workdir=/work", "--env=HOME=/tmp", "--entrypoint=/bin/sh", image, "-c", script));
        return List.copyOf(args);
    }

    private static Duration remaining(long deadline) {
        long nanos = deadline - System.nanoTime();
        if (nanos <= 0) throw new Failed(Failure.TIMEOUT);
        return Duration.ofNanos(nanos);
    }

    static Exit process(List<String> command, Duration timeout) throws IOException, InterruptedException {
        ProcessBuilder builder = new ProcessBuilder(command)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD);
        builder.environment().clear();
        for (String key : List.of("PATH", "Path", "SystemRoot", "WINDIR", "COMSPEC", "PATHEXT", "TEMP", "TMP"))
            if (System.getenv(key) != null) builder.environment().put(key, System.getenv(key));
        Process process = builder.start();
        try {
            if (!process.waitFor(Math.max(1, timeout.toNanos()), TimeUnit.NANOSECONDS)) {
                if (!terminate(process)) throw new Failed(Failure.CLEANUP);
                return Exit.TIMEOUT;
            }
            return process.exitValue() == 0 ? Exit.SUCCESS : Exit.FAILURE;
        } catch (InterruptedException ex) {
            boolean stopped;
            try { stopped = terminate(process); }
            finally { Thread.currentThread().interrupt(); }
            if (!stopped) throw new Failed(Failure.CLEANUP);
            throw ex;
        }
    }

    private static boolean terminate(Process process) {
        boolean interrupted = Thread.interrupted();
        try {
            boolean confirmed = true;
            List<ProcessHandle> descendants;
            try { descendants = process.descendants().toList(); }
            catch (RuntimeException ex) { descendants = List.of(); confirmed = false; }
            for (ProcessHandle descendant : descendants) try { descendant.destroyForcibly(); }
            catch (RuntimeException ex) { confirmed = false; }
            try { process.destroyForcibly(); }
            catch (RuntimeException ex) { confirmed = false; }
            try { process.waitFor(5, TimeUnit.SECONDS); }
            catch (InterruptedException ex) { interrupted = true; }
            return confirmed && !process.isAlive() && descendants.stream().noneMatch(ProcessHandle::isAlive);
        } finally {
            if (interrupted) Thread.currentThread().interrupt();
        }
    }
}
