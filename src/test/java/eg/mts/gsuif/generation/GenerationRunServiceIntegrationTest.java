package eg.mts.gsuif.generation;

import eg.mts.gsuif.entity.*;
import eg.mts.gsuif.repository.GenerationRunRepository;
import eg.mts.gsuif.repository.GeneratedArtifactRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class GenerationRunServiceIntegrationTest {
    @Autowired EntityManager entityManager;
    @Autowired GenerationRunRepository repository;
    @Autowired GeneratedArtifactRepository artifactRepository;

    @Test void recordsEachReturnedFileAndItsOwnVersionWithRunHistory() {
        var service = service((project, goal) -> new ConsumerBuildValidator.CommandResult(0, goal + " passed"));
        var fixture = fixture();
        var first = result();
        var second = new GenerationResult.Artifact("src/main/java/example/Other.java",
                "package example; class Other {}".getBytes(StandardCharsets.UTF_8), "other-hash", "2.0.0", "1.0.0");
        var multi = new GenerationResult(List.of(first.artifacts().getFirst(), second), List.of(), ConsumerBuildContract.phaseOne());
        long beforeRuns = repository.count();
        long beforeFiles = artifactRepository.count();
        UUID attempt = UUID.randomUUID();
        GenerationRun run = service.validateAndRecord(attempt, fixture.version(), fixture.user(), multi, inputs());
        entityManager.flush(); entityManager.clear();
        assertEquals(beforeRuns + 1, repository.count());
        assertEquals(beforeFiles + 2, artifactRepository.count());
        var file = service.findFile(run.getId(), second.relativePath()).orElseThrow();
        assertEquals("2.0.0", file.getTemplateVersion());
        assertArrayEquals(second.bytes(), service.readArtifact(run.getId(), second.relativePath()));
        assertEquals(fixture.version().getId(), file.getGenerationRun().getMetadataVersion().getId());
        assertTrue(service.findFile(run.getId(), "src/test/java/example/GeneratedTest.java").isEmpty());
        assertEquals(run.getId(), service.validateAndRecord(attempt, fixture.version(), fixture.user(), multi, inputs()).getId());
        entityManager.flush();
        assertEquals(beforeRuns + 1, repository.count());
        assertEquals(beforeFiles + 2, artifactRepository.count());

        UUID anotherRun = service.validateAndRecord(UUID.randomUUID(), fixture.version(), fixture.user(), multi, inputs()).getId();
        entityManager.flush(); entityManager.clear();
        assertNotEquals(run.getId(), anotherRun);
        assertEquals(2, service.findFileHistory(second.relativePath()).size());
    }

    @Test void failedBuildRetainsAllReturnedFilesAndPreResultFailureWritesNothing() {
        var service = service((project, goal) -> new ConsumerBuildValidator.CommandResult(1, "compile error"));
        var fixture = fixture();
        long beforeRuns = repository.count();
        long beforeFiles = artifactRepository.count();
        assertThrows(NullPointerException.class, () -> service.validateAndRecord(
                UUID.randomUUID(), fixture.version(), fixture.user(), null, inputs()));
        assertEquals(beforeRuns, repository.count());
        var original = result();
        var second = new GenerationResult.Artifact("src/main/java/example/Other.java",
                "package example; class Other {}".getBytes(StandardCharsets.UTF_8),
                "other-hash", "2.0.0", "1.0.0");
        var result = new GenerationResult(List.of(original.artifacts().getFirst(), second),
                List.of(), ConsumerBuildContract.phaseOne());
        UUID id = service.validateAndRecord(UUID.randomUUID(), fixture.version(), fixture.user(), result, inputs()).getId();
        entityManager.flush(); entityManager.clear();
        assertEquals(GenerationRunStatus.BUILD_FAILED, service.findById(id).orElseThrow().getStatus());
        assertEquals(beforeRuns + 1, repository.count());
        assertEquals(beforeFiles + result.artifacts().size(), artifactRepository.count());
        assertEquals("1.0.0", service.findFile(id, result.artifacts().getFirst().relativePath()).orElseThrow().getTemplateVersion());
        assertEquals("2.0.0", service.findFile(id, second.relativePath()).orElseThrow().getTemplateVersion());
        assertArrayEquals(second.bytes(), service.readArtifact(id, second.relativePath()));
    }

    @Test void realMavenCompileAndTestPersistSuccess() {
        var fixture = fixture();
        UUID id = realService().validateAndRecord(UUID.randomUUID(), fixture.version(), fixture.user(), result(), inputs()).getId();
        entityManager.flush(); entityManager.clear();
        GenerationRun saved = realService().findById(id).orElseThrow();
        assertEquals(GenerationRunStatus.SUCCESS, saved.getStatus(), saved.getCompileOutput() + "\n" + saved.getTestOutput());
        assertEquals(0, saved.getCompileExitCode());
        assertEquals(0, saved.getTestExitCode());
        assertArrayEquals(result().artifacts().getFirst().bytes(), realService().readArtifact(id,
                result().artifacts().getFirst().relativePath()));
        assertTrue(saved.getCompileOutput().contains("BUILD SUCCESS"));
        assertTrue(saved.getTestOutput().contains("BUILD SUCCESS"));
    }

    @Test void realMavenCompileFailurePersistsCompilerOutputAndSkipsTest() {
        var fixture = fixture();
        UUID id = realService().validateAndRecord(UUID.randomUUID(), fixture.version(), fixture.user(), brokenResult(), inputs()).getId();
        entityManager.flush(); entityManager.clear();
        GenerationRun saved = realService().findById(id).orElseThrow();
        assertEquals(GenerationRunStatus.BUILD_FAILED, saved.getStatus());
        assertNotNull(saved.getCompileExitCode(), saved.getCompileOutput());
        assertNotEquals(0, saved.getCompileExitCode());
        assertTrue(saved.getCompileOutput().contains("COMPILATION ERROR"), saved.getCompileOutput());
        assertTrue(saved.getCompileOutput().length() <= ConsumerBuildValidator.OUTPUT_LIMIT);
        assertNull(saved.getTestExitCode());
        assertNull(saved.getTestOutput());
        assertArrayEquals(brokenResult().artifacts().getFirst().bytes(), realService().readArtifact(id,
                brokenResult().artifacts().getFirst().relativePath()));
    }

    @Test void realMavenTestFailurePersistsBothExitCodes() {
        var fixture = fixture();
        var brokenTest = Map.of("src/test/java/example/GeneratedTest.java",
                "package example; BROKEN TEST".getBytes(StandardCharsets.UTF_8));
        UUID id = realService().validateAndRecord(UUID.randomUUID(), fixture.version(), fixture.user(), result(), brokenTest).getId();
        entityManager.flush(); entityManager.clear();
        GenerationRun saved = realService().findById(id).orElseThrow();
        assertEquals(GenerationRunStatus.BUILD_FAILED, saved.getStatus());
        assertEquals(0, saved.getCompileExitCode(), saved.getCompileOutput());
        assertNotNull(saved.getTestExitCode(), saved.getTestOutput());
        assertNotEquals(0, saved.getTestExitCode());
        assertTrue(saved.getTestOutput().contains("COMPILATION ERROR"), saved.getTestOutput());
    }

    @Test void mavenConfigCannotTurnBrokenJavaIntoSuccess() {
        var fixture = fixture();
        var bypass = Map.of(".mvn/maven.config",
                "-Dmaven.main.skip=true\n-Dmaven.test.skip=true\n".getBytes(StandardCharsets.UTF_8));
        UUID id = realService().validateAndRecord(UUID.randomUUID(), fixture.version(), fixture.user(), brokenResult(), bypass).getId();
        entityManager.flush(); entityManager.clear();
        GenerationRun saved = realService().findById(id).orElseThrow();
        assertEquals(GenerationRunStatus.BUILD_FAILED, saved.getStatus());
        assertNull(saved.getCompileExitCode(), saved.getCompileOutput());
        assertTrue(saved.getCompileOutput().contains("Unsupported consumer input path"), saved.getCompileOutput());
    }

    @Test void realExecutedAssertionFailurePersistsTestFailure() {
        var fixture = fixture();
        var failingTest = Map.of("src/test/java/example/GeneratedTest.java", """
                package example;
                import org.junit.jupiter.api.Test;
                import static org.junit.jupiter.api.Assertions.fail;
                class GeneratedTest {
                    @Test void actualAssertionFailure() { fail("ASSERTION_SENTINEL"); }
                }
                """.getBytes(StandardCharsets.UTF_8));
        UUID id = realService().validateAndRecord(UUID.randomUUID(), fixture.version(), fixture.user(), result(), failingTest).getId();
        entityManager.flush(); entityManager.clear();
        GenerationRun saved = realService().findById(id).orElseThrow();
        assertEquals(GenerationRunStatus.BUILD_FAILED, saved.getStatus());
        assertEquals(0, saved.getCompileExitCode(), saved.getCompileOutput());
        assertNotEquals(0, saved.getTestExitCode(), saved.getTestOutput());
        assertTrue(saved.getTestOutput().contains("ASSERTION_SENTINEL"), saved.getTestOutput());
    }

    @Test void successfulMavenTestGoalDoesNotRequireDiscoveredTests() {
        for (Map<String, byte[]> testInputs : List.of(
                Map.<String, byte[]>of(),
                Map.of("src/test/java/example/UndiscoveredCheck.java", """
                        package example;
                        import org.junit.jupiter.api.Test;
                        class UndiscoveredCheck { @Test void runsOnlyWhenSelected() { throw new AssertionError(); } }
                        """.getBytes(StandardCharsets.UTF_8)),
                Map.of("src/test/java/example/GeneratedTest.java", """
                        package example;
                        import org.junit.jupiter.api.Disabled;
                        import org.junit.jupiter.api.Test;
                        class GeneratedTest { @Disabled @Test void intentionallySkipped() { throw new AssertionError(); } }
                        """.getBytes(StandardCharsets.UTF_8)))) {
            var fixture = fixture();
            UUID id = realService().validateAndRecord(UUID.randomUUID(), fixture.version(), fixture.user(), result(), testInputs).getId();
            entityManager.flush(); entityManager.clear();
            GenerationRun saved = realService().findById(id).orElseThrow();
            assertEquals(GenerationRunStatus.SUCCESS, saved.getStatus(), saved.getCompileOutput() + "\n" + saved.getTestOutput());
            assertEquals(0, saved.getCompileExitCode());
            assertEquals(0, saved.getTestExitCode());
        }
    }

    @Test void passingCompileAndTestPersistSuccess() {
        var calls = new ArrayList<String>();
        var service = service((project, goal) -> {
            calls.add(goal);
            assertTrue(Files.exists(project.resolve("pom.xml")));
            assertTrue(Files.exists(project.resolve("src/main/java/example/Generated.java")));
            assertTrue(Files.exists(project.resolve("src/test/java/example/GeneratedTest.java")));
            return new ConsumerBuildValidator.CommandResult(0, goal + " passed");
        });
        var fixture = fixture();
        UUID id = service.validateAndRecord(UUID.randomUUID(), fixture.version(), fixture.user(), result(), inputs()).getId();
        entityManager.flush(); entityManager.clear();
        GenerationRun saved = service.findById(id).orElseThrow();
        assertEquals(List.of("compile", "test"), calls);
        assertEquals(GenerationRunStatus.SUCCESS, saved.getStatus());
        assertEquals(0, saved.getCompileExitCode());
        assertEquals(0, saved.getTestExitCode());
        assertEquals("compile passed", saved.getCompileOutput());
        assertEquals("test passed", saved.getTestOutput());
    }

    @Test void failedCompilePersistsBoundedOutputAndSkipsTest() {
        var calls = new ArrayList<String>();
        var service = service((project, goal) -> {
            calls.add(goal);
            assertTrue(Files.readString(project.resolve("src/main/java/example/Generated.java")).contains("BROKEN JAVA"));
            return new ConsumerBuildValidator.CommandResult(1, "compiler error\n" + "x".repeat(20000));
        });
        var fixture = fixture();
        UUID id = service.validateAndRecord(UUID.randomUUID(), fixture.version(), fixture.user(), brokenResult(), inputs()).getId();
        entityManager.flush(); entityManager.clear();
        GenerationRun saved = service.findById(id).orElseThrow();
        assertEquals(List.of("compile"), calls);
        assertEquals(GenerationRunStatus.BUILD_FAILED, saved.getStatus());
        assertEquals(1, saved.getCompileExitCode());
        assertNull(saved.getTestExitCode());
        assertNull(saved.getTestOutput());
        assertEquals(ConsumerBuildValidator.OUTPUT_LIMIT, saved.getCompileOutput().length());
        assertTrue(saved.getCompileOutput().startsWith("compiler error"));
        assertTrue(service.findById(UUID.randomUUID()).isEmpty());
    }

    @Test void failedTestDoesNotRecordSuccess() {
        var service = service((project, goal) -> new ConsumerBuildValidator.CommandResult(
                goal.equals("compile") ? 0 : 7, goal.equals("compile") ? "compiled" : "assertion failed"));
        var fixture = fixture();
        UUID id = service.validateAndRecord(UUID.randomUUID(), fixture.version(), fixture.user(), result(), inputs()).getId();
        entityManager.flush(); entityManager.clear();
        GenerationRun saved = service.findById(id).orElseThrow();
        assertEquals(GenerationRunStatus.BUILD_FAILED, saved.getStatus());
        assertEquals(0, saved.getCompileExitCode());
        assertEquals(7, saved.getTestExitCode());
        assertEquals("assertion failed", saved.getTestOutput());
    }

    private GenerationRunService service(ConsumerBuildValidator.CommandRunner runner) {
        return new GenerationRunService(repository, artifactRepository, new ConsumerBuildValidator(Path.of("target", "generation-build-tests"), runner));
    }

    private GenerationRunService realService() {
        return new GenerationRunService(repository, artifactRepository, new ConsumerBuildValidator(Path.of("target", "generation-build-tests")));
    }

    private GenerationResult result() {
        return new GenerationResult(List.of(new GenerationResult.Artifact(
                "src/main/java/example/Generated.java", "package example; public class Generated {}".getBytes(StandardCharsets.UTF_8),
                "hash", "1.0.0", "1.0.0")), List.of(), ConsumerBuildContract.phaseOne());
    }

    private GenerationResult brokenResult() {
        return new GenerationResult(List.of(new GenerationResult.Artifact(
                "src/main/java/example/Generated.java", "BROKEN JAVA".getBytes(StandardCharsets.UTF_8),
                "hash", "1.0.0", "1.0.0")), List.of(), ConsumerBuildContract.phaseOne());
    }

    private Map<String, byte[]> inputs() {
        return Map.of("src/test/java/example/GeneratedTest.java",
                """
                package example;
                import org.junit.jupiter.api.Test;
                import static org.junit.jupiter.api.Assertions.assertNotNull;
                class GeneratedTest {
                    @Test void generatedClassLoads() { assertNotNull(new Generated()); }
                }
                """.getBytes(StandardCharsets.UTF_8));
    }

    private Fixture fixture() {
        GsuifProject project = new GsuifProject(); project.setName("Build validation " + UUID.randomUUID());
        entityManager.persist(project);
        GsuifPage page = new GsuifPage(); page.setProject(project); page.setName("Page"); entityManager.persist(page);
        MetadataVersion version = new MetadataVersion(); version.setPage(page); version.setVersion(1);
        version.setSchemaVersion("1.0.0"); version.setSnapshot("{\"components\":[],\"apiBindings\":[]}");
        entityManager.persist(version);
        GsuifUser user = new GsuifUser(); user.setUsername("build-" + UUID.randomUUID()); user.setPasswordHash("hash");
        entityManager.persist(user);
        return new Fixture(version, user);
    }

    private record Fixture(MetadataVersion version, GsuifUser user) { }
}
