package eg.mts.gsuif.generation;

import com.fasterxml.jackson.databind.ObjectMapper;
import eg.mts.gsuif.generation.GenerationCapabilityPreflight.OutputMode;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class BackendFinalPackageValidatorTest {
    @TempDir Path scratch;

    @Test void commandUsesPinnedOfflineNonRootBuildWithOnlyBoundedReadOnlyInputs() {
        List<String> command = BackendFinalPackageValidator.command("sha256:" + "a".repeat(64),
                "gsuif-backend-build-test", scratch.resolve("input"), scratch.resolve("repo"), scratch.resolve("maven"));
        assertTrue(command.contains("--network=none"));
        assertTrue(command.contains("--read-only"));
        assertTrue(command.contains("--user=65532:65532"));
        assertTrue(command.contains("--security-opt=no-new-privileges"));
        assertTrue(command.stream().anyMatch(s -> s.contains("/input,readonly")));
        assertTrue(command.stream().anyMatch(s -> s.contains("/repo,readonly")));
        assertTrue(command.stream().anyMatch(s -> s.contains("/maven,readonly")));
        String script = command.getLast();
        assertTrue(script.contains("-o -q"));
        assertTrue(script.contains("-Dmaven.compiler.proc=none"));
        assertFalse(script.contains("OPENROUTER"));
    }

    @Test void mockedRunnerSeesBuildThenContainerCleanup() throws Exception {
        var assembled = assembled();
        Path repo = Files.createDirectory(scratch.resolve("repo"));
        Path maven = Files.createDirectories(scratch.resolve("maven/bin"));
        Files.writeString(maven.resolve("mvn"), "test-only");
        List<List<String>> calls = new ArrayList<>();
        var validator = new BackendFinalPackageValidator("sha256:" + "a".repeat(64), repo,
                maven.getParent(), (command, timeout) -> {
                    calls.add(command);
                    return BackendFinalPackageValidator.Exit.SUCCESS;
                });
        validator.validate(assembled);
        assertEquals(2, calls.size());
        assertEquals("run", calls.getFirst().get(1));
        assertEquals(List.of("docker", "rm", "-f"), calls.getLast().subList(0, 3));
    }

    @Test void buildFailureRemainsBoundedAndStillCleansContainer() throws Exception {
        var assembled = assembled();
        Path repo = Files.createDirectory(scratch.resolve("repo"));
        Path maven = Files.createDirectories(scratch.resolve("maven/bin"));
        Files.writeString(maven.resolve("mvn"), "test-only");
        List<List<String>> calls = new ArrayList<>();
        var validator = new BackendFinalPackageValidator("sha256:" + "a".repeat(64), repo,
                maven.getParent(), (command, timeout) -> {
                    calls.add(command);
                    return calls.size() == 1 ? BackendFinalPackageValidator.Exit.FAILURE
                            : BackendFinalPackageValidator.Exit.SUCCESS;
                });
        var failure = assertThrows(BackendFinalPackageValidator.Failed.class, () -> validator.validate(assembled));
        assertEquals(BackendFinalPackageValidator.Failure.BUILD, failure.reason());
        assertEquals(2, calls.size());
    }

    @Test void timeoutStillRemovesContainerAndStagedFiles() throws Exception {
        var assembled = assembled();
        Path repo = Files.createDirectory(scratch.resolve("repo"));
        Path maven = Files.createDirectories(scratch.resolve("maven/bin"));
        Files.writeString(maven.resolve("mvn"), "test-only");
        List<List<String>> calls = new ArrayList<>();
        var validator = new BackendFinalPackageValidator("sha256:" + "a".repeat(64), repo,
                maven.getParent(), (command, timeout) -> {
                    calls.add(command);
                    return calls.size() == 1 ? BackendFinalPackageValidator.Exit.TIMEOUT
                            : BackendFinalPackageValidator.Exit.SUCCESS;
                });
        var failure = assertThrows(BackendFinalPackageValidator.Failed.class, () -> validator.validate(assembled));
        assertEquals(BackendFinalPackageValidator.Failure.TIMEOUT, failure.reason());
        assertEquals(2, calls.size());
        assertFalse(Files.exists(stagedInput(calls.getFirst())));
    }

    @Test void interruptionClearsFlagForCleanupAndRestoresItForCaller() throws Exception {
        var assembled = assembled();
        Path repo = Files.createDirectory(scratch.resolve("repo"));
        Path maven = Files.createDirectories(scratch.resolve("maven/bin"));
        Files.writeString(maven.resolve("mvn"), "test-only");
        List<List<String>> calls = new ArrayList<>();
        var validator = new BackendFinalPackageValidator("sha256:" + "a".repeat(64), repo,
                maven.getParent(), (command, timeout) -> {
                    calls.add(command);
                    if (calls.size() == 1) throw new InterruptedException("test interruption");
                    assertFalse(Thread.currentThread().isInterrupted(), "Cleanup must have a usable wait");
                    return BackendFinalPackageValidator.Exit.SUCCESS;
                });
        try {
            var failure = assertThrows(BackendFinalPackageValidator.Failed.class, () -> validator.validate(assembled));
            assertEquals(BackendFinalPackageValidator.Failure.TIMEOUT, failure.reason());
            assertTrue(Thread.currentThread().isInterrupted());
            assertEquals(2, calls.size());
            assertFalse(Files.exists(stagedInput(calls.getFirst())));
        } finally {
            Thread.interrupted();
        }
    }

    @Test void failedDockerCleanupStillAttemptsStagedCleanupAndFailsClosed() throws Exception {
        var assembled = assembled();
        Path repo = Files.createDirectory(scratch.resolve("repo"));
        Path maven = Files.createDirectories(scratch.resolve("maven/bin"));
        Files.writeString(maven.resolve("mvn"), "test-only");
        List<List<String>> calls = new ArrayList<>();
        var validator = new BackendFinalPackageValidator("sha256:" + "a".repeat(64), repo,
                maven.getParent(), (command, timeout) -> {
                    calls.add(command);
                    return calls.size() == 1 ? BackendFinalPackageValidator.Exit.SUCCESS
                            : BackendFinalPackageValidator.Exit.FAILURE;
                });
        var failure = assertThrows(BackendFinalPackageValidator.Failed.class, () -> validator.validate(assembled));
        assertEquals(BackendFinalPackageValidator.Failure.CLEANUP, failure.reason());
        assertEquals(2, calls.size());
        assertFalse(Files.exists(stagedInput(calls.getFirst())));
    }

    @Test void interruptedRealChildAndDescendantAreTerminated() throws Exception {
        Path parentPid = scratch.resolve("parent.pid"), childPid = scratch.resolve("child.pid");
        AtomicReference<Throwable> outcome = new AtomicReference<>();
        Thread worker = new Thread(() -> {
            try { BackendFinalPackageValidator.process(sleeper("parent", parentPid, childPid), Duration.ofSeconds(30)); }
            catch (Throwable ex) { outcome.set(ex); }
        });
        worker.start();
        try {
            waitFor(parentPid, childPid);
            worker.interrupt();
            worker.join(8_000);
            assertFalse(worker.isAlive(), "Interrupted process wait did not finish within its cleanup bound");
            assertInstanceOf(InterruptedException.class, outcome.get());
            assertFalse(alive(parentPid), "Child process survived interruption");
            assertFalse(alive(childPid), "Descendant process survived interruption");
        } finally {
            worker.interrupt();
            terminateTestProcess(parentPid);
            terminateTestProcess(childPid);
        }
    }

    @Test void timedOutRealChildIsTerminated() throws Exception {
        Path pid = scratch.resolve("timed-out.pid");
        try {
            assertEquals(BackendFinalPackageValidator.Exit.TIMEOUT,
                    BackendFinalPackageValidator.process(sleeper("leaf", pid), Duration.ofSeconds(3)));
            assertTrue(Files.exists(pid));
            assertFalse(alive(pid), "Child process survived timeout");
        } finally { terminateTestProcess(pid); }
    }

    private static Path stagedInput(List<String> command) {
        String mount = command.stream().filter(value -> value.startsWith("--mount=type=bind,src=")
                && value.endsWith(",dst=/input,readonly")).findFirst().orElseThrow();
        return Path.of(mount.substring("--mount=type=bind,src=".length(),
                mount.length() - ",dst=/input,readonly".length()));
    }

    private static List<String> sleeper(String mode, Path... pidFiles) {
        String java = Path.of(System.getProperty("java.home"), "bin",
                System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java").toString();
        String classpath = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
        List<String> command = new ArrayList<>(List.of(java, "-cp", classpath, HarmlessWait.class.getName(), mode, java));
        for (Path file : pidFiles) command.add(file.toString());
        return command;
    }

    private static void waitFor(Path... files) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            if (java.util.Arrays.stream(files).allMatch(Files::exists)) return;
            Thread.sleep(20);
        }
        fail("Harmless child did not publish its process IDs");
    }

    private static boolean alive(Path file) throws Exception {
        long pid = Long.parseLong(Files.readString(file).trim());
        return ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false);
    }

    private static void terminateTestProcess(Path file) throws Exception {
        if (Files.exists(file)) ProcessHandle.of(Long.parseLong(Files.readString(file).trim()))
                .ifPresent(ProcessHandle::destroyForcibly);
    }

    public static final class HarmlessWait {
        public static void main(String[] args) throws Exception {
            if ("parent".equals(args[0])) {
                new ProcessBuilder(args[1], "-cp", System.getProperty("java.class.path"),
                        HarmlessWait.class.getName(), "leaf", args[1], args[3]).start();
                Files.writeString(Path.of(args[2]), Long.toString(ProcessHandle.current().pid()));
            } else Files.writeString(Path.of(args[2]), Long.toString(ProcessHandle.current().pid()));
            Thread.sleep(60_000);
        }
    }

    @Test void selectedPackageBuildsInPinnedOfflineDockerWhenOptedIn() throws Exception {
        String image = System.getProperty("gsuif.test.backend-build-image-id");
        String home = System.getProperty("gsuif.test.maven-home");
        String repository = System.getProperty("maven.repo.local");
        Assumptions.assumeTrue(image != null && home != null && repository != null,
                "Pinned image, Maven distribution and cache must be supplied explicitly");
        new BackendFinalPackageValidator(image, Path.of(repository), Path.of(home)).validate(assembled());
    }

    /** The StockUnit 3.0.0 (paged) contract, built offline in the pinned image without any provider call. */
    @Test void pagedV3PackageBuildsInPinnedOfflineDockerWhenOptedIn() throws Exception {
        String image = System.getProperty("gsuif.test.backend-build-image-id");
        String home = System.getProperty("gsuif.test.maven-home");
        String repository = System.getProperty("maven.repo.local");
        Assumptions.assumeTrue(image != null && home != null && repository != null,
                "Pinned image, Maven distribution and cache must be supplied explicitly");
        var fixture = new CompleteOutputTestFixture();
        assertEquals("3.0.0", fixture.pagedSpecification().openApi().contractVersion());
        var assembled = assembled(fixture, fixture.pagedSnapshot(), fixture.pagedSpecification(),
                "/generation/fixtures/StockUnitPagedServiceImpl.java.txt");
        new BackendFinalPackageValidator(image, Path.of(repository), Path.of(home)).validate(assembled);
    }

    private CompleteBackendPackageAssembler.Assembled assembled() throws Exception {
        var fixture = new CompleteOutputTestFixture();
        return assembled(fixture, fixture.snapshot("condition"), fixture.specification(),
                "/generation/fixtures/StockUnitServiceImpl.java.txt");
    }

    private CompleteBackendPackageAssembler.Assembled assembled(CompleteOutputTestFixture fixture,
            com.fasterxml.jackson.databind.node.ObjectNode snapshot, GenerationSpecification specification,
            String serviceResource) throws Exception {
        var descriptor = fixture.preflight().describe(fixture.version(snapshot), specification, OutputMode.BACKEND);
        var plan = GenerationFilePlan.forDescriptor(descriptor);
        var runtime = new CompleteBackendRuntimeRenderer(
                new PackagedContractRegistry("/generation/stock-unit-contracts.yaml"));
        var input = BackendModelInput.from(descriptor, plan,
                new CompleteBackendDefinitionRenderer().render(descriptor, plan), runtime);
        String source;
        try (var resource = getClass().getResourceAsStream(serviceResource)) {
            source = new String(resource.readAllBytes(), StandardCharsets.UTF_8);
        }
        var response = new ObjectMapper().createObjectNode();
        response.putArray("files").addObject().put("path", input.writablePath()).put("content", source);
        var accepted = new BackendModelOutputValidator((selected, bytes) -> true)
                .validate(input, new ObjectMapper().writeValueAsBytes(response));
        return new CompleteBackendPackageAssembler(runtime).assemble(input, accepted,
                new CompleteBackendPackageAssembler.Provenance("mock-provider", "test-1", "mock-model", true));
    }
}
