package eg.mts.gsuif.generation;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;

/** Compiles untrusted source in a pinned, offline, non-root JDK container; never runs it. */
final class DockerBackendJavaCompiler {
    private static final Pattern IMAGE = Pattern.compile("sha256:[0-9a-f]{64}");
    private final String image;

    DockerBackendJavaCompiler(String image) {
        if (image == null || !IMAGE.matcher(image).matches())
            throw new IllegalArgumentException("Pinned JDK image ID required");
        this.image = image;
    }

    boolean compile(BackendModelInput input, byte[] source, Duration limit) {
        if (limit == null || limit.isNegative() || limit.isZero()) return false;
        long deadline = System.nanoTime() + limit.toNanos();
        Path staged = null;
        boolean dockerInvoked = false;
        String container = "gsuif-model-compile-" + UUID.randomUUID().toString().replace("-", "");
        try {
            staged = Files.createTempDirectory("gsuif-model-compile-");
            Path sources = Files.createDirectories(staged.resolve("sources"));
            Path libraries = Files.createDirectories(staged.resolve("lib"));
            List<String> names = new ArrayList<>();
            for (RenderedModelHandoff.Definition definition : input.handoff().definitions()) {
                if (System.nanoTime() >= deadline) return false;
                String name = "Source" + names.size() + "/" + Path.of(definition.path()).getFileName();
                // Java package paths matter to javac only for output; filenames remain authoritative.
                Path file = sources.resolve(name);
                Files.createDirectories(file.getParent());
                Files.write(file, definition.bytes());
                names.add("/input/sources/" + name.replace('\\', '/'));
            }
            String service = "Source" + names.size() + "/" + Path.of(input.writablePath()).getFileName();
            Path serviceFile = sources.resolve(service);
            Files.createDirectories(serviceFile.getParent());
            Files.write(serviceFile, source);
            names.add("/input/sources/" + service.replace('\\', '/'));
            String[] classpath = System.getProperty("surefire.test.class.path",
                    System.getProperty("java.class.path", "")).split(Pattern.quote(java.io.File.pathSeparator));
            List<String> jars = new ArrayList<>();
            for (String item : classpath) {
                if (System.nanoTime() >= deadline) return false;
                Path path = Path.of(item);
                if (Files.isRegularFile(path) && item.endsWith(".jar")) {
                    String name = "lib" + jars.size() + ".jar";
                    Files.copy(path, libraries.resolve(name));
                    jars.add("/input/lib/" + name);
                }
            }
            PosixCompileStaging.makeContainerReadable(staged);
            StringBuilder command = new StringBuilder("mkdir -p /work/classes && javac -proc:none -encoding UTF-8 -d /work/classes");
            if (!jars.isEmpty()) command.append(" -cp '").append(String.join(":", jars)).append("'");
            for (String name : names) command.append(" '").append(name).append("'");
            List<String> docker = command(image, container, staged, command.toString());
            if (System.nanoTime() >= deadline) return false;
            Process process = start(docker, staged);
            dockerInvoked = true;
            boolean completed = process.waitFor(Math.max(1, deadline - System.nanoTime()),
                    java.util.concurrent.TimeUnit.NANOSECONDS);
            if (!completed) process.destroyForcibly();
            if (!completed) process.waitFor();
            return completed && process.exitValue() == 0;
        } catch (IOException | InterruptedException | RuntimeException ex) {
            if (ex instanceof InterruptedException) Thread.currentThread().interrupt();
            return false;
        } finally {
            if (staged != null) {
                boolean removed = !dockerInvoked;
                if (dockerInvoked) try {
                    Process cleanup = start(List.of("docker", "rm", "-f", container), staged);
                    if (!cleanup.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)) {
                        cleanup.destroyForcibly();
                        cleanup.waitFor();
                    } else removed = cleanup.exitValue() == 0;
                } catch (InterruptedException ex) { Thread.currentThread().interrupt(); }
                catch (IOException ignored) { }
                try (var paths = Files.walk(staged)) {
                    for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
                } catch (IOException ignored) { }
                if (!removed) throw new IllegalStateException("Compiler sandbox cleanup could not be confirmed");
            }
        }
    }

    static List<String> command(String image, String container, Path staged, String script) {
        return List.of("docker", "run", "--pull=never", "--name", container,
                "--network=none", "--read-only", "--ipc=none", "--cap-drop=ALL",
                "--security-opt=no-new-privileges", "--pids-limit=64", "--memory=512m", "--cpus=1",
                "--user=65532:65532", "--log-driver=none", "--tmpfs=/work:rw,exec,nosuid,size=128m,mode=1777",
                "--tmpfs=/tmp:rw,noexec,nosuid,size=16m,mode=1777",
                "--mount=type=bind,src=" + staged.toAbsolutePath() + ",dst=/input,readonly",
                "--workdir=/work", "--env=HOME=/tmp", "--entrypoint=/bin/sh", image, "-c", script);
    }

    private static Process start(List<String> command, Path staged) throws IOException {
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.environment().clear();
        for (String key : List.of("PATH", "Path", "SystemRoot", "WINDIR", "COMSPEC", "PATHEXT", "TEMP", "TMP"))
            if (System.getenv(key) != null) builder.environment().put(key, System.getenv(key));
        builder.environment().put("DOCKER_CONFIG", staged.resolve("docker-config").toString());
        Files.createDirectories(staged.resolve("docker-config"));
        if (Files.getFileStore(staged).supportsFileAttributeView("posix"))
            Files.setPosixFilePermissions(staged.resolve("docker-config"),
                    java.nio.file.attribute.PosixFilePermissions.fromString("rwx------"));
        builder.redirectOutput(ProcessBuilder.Redirect.DISCARD);
        builder.redirectError(ProcessBuilder.Redirect.DISCARD);
        return builder.start();
    }
}
