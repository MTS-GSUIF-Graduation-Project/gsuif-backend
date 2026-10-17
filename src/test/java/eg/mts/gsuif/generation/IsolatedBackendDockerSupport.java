package eg.mts.gsuif.generation;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/** Disposable, test-only Docker mechanics for model-source acceptance. */
final class IsolatedBackendDockerSupport implements AutoCloseable {
    record Result(int exit, String output) { }
    @FunctionalInterface interface Runner { Result run(List<String> command, Duration limit) throws Exception; }

    private final Runner runner;
    private final String prefix = "gsuif-ai-" + UUID.randomUUID().toString().replace("-", "");
    private final String label = "gsuif.scrum65.ai=" + prefix;
    private final List<String> containers = new ArrayList<>();
    private final List<String> volumes = new ArrayList<>();
    private boolean networkCreated;

    IsolatedBackendDockerSupport(Runner runner) { this.runner = runner; }
    static IsolatedBackendDockerSupport local() { return new IsolatedBackendDockerSupport(IsolatedBackendDockerSupport::localRun); }

    String name(String suffix) { return prefix + "-" + suffix; }
    String network() { return name("network"); }
    String label() { return label; }

    void createInternalNetwork() throws Exception {
        networkCreated = true;
        requireSuccess(run(List.of("network", "create", "--internal", "--label", label, network()), Duration.ofSeconds(20)),
                "NETWORK_CREATE");
        if (!"true".equals(run(List.of("network", "inspect", "--format", "{{.Internal}}", network()),
                Duration.ofSeconds(10)).output().trim())) throw new IllegalStateException("NETWORK_NOT_INTERNAL");
    }

    String volume(String suffix) throws Exception {
        String value = name(suffix);
        volumes.add(value);
        requireSuccess(run(List.of("volume", "create", "--label", label, value), Duration.ofSeconds(20)),
                "VOLUME_CREATE");
        return value;
    }

    String create(List<String> arguments, String suffix) throws Exception {
        String value = name(suffix);
        List<String> command = new ArrayList<>(List.of("create", "--name", value, "--label", label));
        command.addAll(arguments);
        containers.add(value);
        requireSuccess(run(command, Duration.ofSeconds(20)), "CONTAINER_CREATE");
        return value;
    }

    String runDetached(List<String> arguments, String suffix) throws Exception {
        String value = name(suffix);
        List<String> command = new ArrayList<>(List.of("run", "-d", "--name", value, "--label", label));
        command.addAll(arguments);
        containers.add(value);
        requireSuccess(run(command, Duration.ofSeconds(30)), "CONTAINER_START");
        return value;
    }

    Result run(List<String> args, Duration limit) throws Exception {
        List<String> command = new ArrayList<>(List.of("docker"));
        command.addAll(args);
        return runner.run(List.copyOf(command), limit);
    }

    void copy(Path source, String container, String destination) throws Exception {
        requireSuccess(run(List.of("cp", source.toAbsolutePath().toString(), container + ":" + destination),
                Duration.ofMinutes(3)), "CONTAINER_COPY");
    }

    void start(String container) throws Exception {
        requireSuccess(run(List.of("start", container), Duration.ofSeconds(20)), "CONTAINER_START");
    }

    Result waitFor(String container, Duration limit) throws Exception {
        return run(List.of("wait", container), limit);
    }

    static List<String> restricted(String image, String network, String user, int memoryMb, String workVolume,
            String workTarget, boolean workReadOnly) {
        if (!image.matches("sha256:[0-9a-f]{64}")) throw new IllegalArgumentException("PINNED_IMAGE_REQUIRED");
        List<String> args = new ArrayList<>(List.of("--pull=never", "--network=" + network,
                "--read-only", "--ipc=none", "--cap-drop=ALL", "--security-opt=no-new-privileges",
                "--pids-limit=96", "--memory=" + memoryMb + "m", "--cpus=2", "--user=" + user,
                "--log-driver=none", "--tmpfs=/tmp:rw,nosuid,noexec,size=64m,mode=1777"));
        if (workVolume != null) args.add("--mount=type=volume,src=" + workVolume + ",dst=" + workTarget
                + (workReadOnly ? ",readonly" : ""));
        args.add(image);
        return args;
    }

    private static void requireSuccess(Result result, String stage) {
        if (result.exit() != 0) throw new IllegalStateException(stage);
    }

    static Result localRun(List<String> command, Duration limit) throws Exception {
        Path output = Files.createTempFile("gsuif-ai-docker-", ".log");
        ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(output.toFile());
        removeProviderCredentials(builder.environment());
        Process process = builder.start();
        try {
            if (!process.waitFor(limit.toMillis(), TimeUnit.MILLISECONDS)) {
                process.descendants().forEach(ProcessHandle::destroyForcibly);
                process.destroyForcibly();
                process.waitFor(5, TimeUnit.SECONDS);
                return new Result(-1, "TIMEOUT");
            }
            String text = Files.readString(output, StandardCharsets.UTF_8).trim();
            return new Result(process.exitValue(), text.length() > 4096 ? text.substring(0, 4096) : text);
        } finally {
            if (process.isAlive()) {
                process.descendants().forEach(ProcessHandle::destroyForcibly);
                process.destroyForcibly();
                process.waitFor(5, TimeUnit.SECONDS);
            }
            Files.deleteIfExists(output);
        }
    }

    static void removeProviderCredentials(Map<String, String> environment) {
        environment.keySet().removeIf(key -> key.equalsIgnoreCase("OPENROUTER_API_KEY")
                || key.equalsIgnoreCase("OPENROUTER_MODEL"));
    }

    @Override public void close() throws Exception {
        boolean failed = false;
        for (int i = containers.size() - 1; i >= 0; i--)
            try { run(List.of("rm", "-f", containers.get(i)), Duration.ofSeconds(20)); }
            catch (Exception ex) { failed = true; }
        for (int i = volumes.size() - 1; i >= 0; i--)
            try { run(List.of("volume", "rm", volumes.get(i)), Duration.ofSeconds(20)); }
            catch (Exception ex) { failed = true; }
        if (networkCreated) try { run(List.of("network", "rm", network()), Duration.ofSeconds(20)); }
        catch (Exception ex) { failed = true; }
        try {
            Result left = run(List.of("ps", "-a", "-q", "--filter", "label=" + label), Duration.ofSeconds(20));
            failed |= left.exit() != 0 || !left.output().isBlank();
            Result remainingVolumes = run(List.of("volume", "ls", "-q", "--filter", "label=" + label),
                    Duration.ofSeconds(20));
            failed |= remainingVolumes.exit() != 0 || !remainingVolumes.output().isBlank();
            Result remainingNetworks = run(List.of("network", "ls", "-q", "--filter", "label=" + label),
                    Duration.ofSeconds(20));
            failed |= remainingNetworks.exit() != 0 || !remainingNetworks.output().isBlank();
        } catch (Exception ex) { failed = true; }
        if (failed) throw new IllegalStateException("ISOLATED_CLEANUP_UNCONFIRMED");
    }
}
