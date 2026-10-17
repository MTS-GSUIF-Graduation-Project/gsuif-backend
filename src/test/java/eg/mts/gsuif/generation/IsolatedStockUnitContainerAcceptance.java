package eg.mts.gsuif.generation;

import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.jar.JarFile;

import static org.junit.jupiter.api.Assertions.*;

/** Runs only explicitly mocked model output in disposable containers; no host JVM starts the app. */
final class IsolatedStockUnitContainerAcceptance {
    private record DriverDependency(String relativePath, String sha256) { }

    // Trusted probe-only libraries. They are never copied into /workspace, /app, or the exported package.
    private static final List<DriverDependency> DRIVER_DEPENDENCIES = List.of(
            new DriverDependency("com/fasterxml/jackson/core/jackson-annotations/2.18.2/jackson-annotations-2.18.2.jar",
                    "581bd61000ef7648943f781ca05689e56d03f6052748365a8e2b3a9b5d3fa32f"),
            new DriverDependency("com/fasterxml/jackson/core/jackson-core/2.18.2/jackson-core-2.18.2.jar",
                    "d8054ae7c0d1c2d2f55d28e46026ebe5892881f3fab5f439233184381c3b4a1f"),
            new DriverDependency("com/fasterxml/jackson/core/jackson-databind/2.18.2/jackson-databind-2.18.2.jar",
                    "4b364e6850dc89172fcf1d4dd26b8ff5488eda44ff4657e22dd265203dd5ab3c"),
            new DriverDependency("org/postgresql/postgresql/42.7.11/postgresql-42.7.11.jar",
                    "1981b31d3993c58702783c1cddf10a34e48c1f413d70ff1cb6def0a143484647"));
    private final Path scratch;

    IsolatedStockUnitContainerAcceptance(Path scratch) { this.scratch = scratch; }

    void verify(BackendModelInput input, BackendModelOutputValidator.Accepted accepted,
            CompleteBackendPackageAssembler.Assembled assembled, Path extracted,
            BackendCompileClasspath dependencies) throws Exception {
        Path evidenceRoot = Path.of(required("gsuif.test.ai-evidence-dir")).toAbsolutePath().normalize();
        assertFalse(evidenceRoot.startsWith(scratch.toAbsolutePath().normalize()));
        String jdk = requiredImage("gsuif.test.ai-jdk-image");
        String postgres = requiredImage("gsuif.test.ai-postgres-image");
        Path mavenHome = Path.of(required("gsuif.test.maven-command")).toAbsolutePath().getParent().getParent();
        Path repository = Path.of(required("maven.repo.local")).toAbsolutePath();
        assertTrue(Files.isRegularFile(mavenHome.resolve("bin/mvn")));
        assertTrue(Files.isDirectory(repository));
        Path isolatedRepository = provisionOfflineRepository(extracted.resolve("backend/pom.xml"),
                Path.of(required("gsuif.test.maven-command")), repository);
        String dbPassword = "disposable-" + UUID.randomUUID();
        String adminPassword = "disposable-" + UUID.randomUUID();
        String userPassword = "disposable-" + UUID.randomUUID();
        byte[] secret = new byte[32]; new SecureRandom().nextBytes(secret);
        String jwt = Base64.getEncoder().encodeToString(secret);
        Path dbEnv = scratch.resolve("db.env");
        Path bootstrapEnv = scratch.resolve("bootstrap.env");
        Path appEnv = scratch.resolve("app.env");
        Path probeEnv = scratch.resolve("probe.env");
        Path offlineSettings = scratch.resolve("container-offline-settings.xml");
        Files.writeString(offlineSettings,
                "<settings xmlns=\"http://maven.apache.org/SETTINGS/1.2.0\">"
                        + "<mirrors><mirror><id>reviewed-local-cache</id><mirrorOf>*</mirrorOf>"
                        + "<url>file:///repo/isolated-repository</url></mirror></mirrors></settings>",
                StandardCharsets.UTF_8);
        Files.writeString(dbEnv, "POSTGRES_USER=generated_test\nPOSTGRES_DB=generated_app\nPOSTGRES_PASSWORD="
                + dbPassword + "\n", StandardCharsets.UTF_8);
        String common = "GENERATED_APP_DB_URL=jdbc:postgresql://db:5432/generated_app\n"
                + "GENERATED_APP_DB_USER=generated_test\nGENERATED_APP_DB_PASSWORD=" + dbPassword + "\n";
        Files.writeString(bootstrapEnv, common + "GENERATED_APP_BOOTSTRAP_USERNAME=FirstAdmin\n"
                + "GENERATED_APP_BOOTSTRAP_PASSWORD=" + adminPassword + "\n", StandardCharsets.UTF_8);
        Files.writeString(appEnv, common + "GENERATED_APP_JWT_SECRET=" + jwt + "\n", StandardCharsets.UTF_8);
        Files.writeString(probeEnv, "PROBE_ADMIN_PASSWORD=" + adminPassword + "\nPROBE_USER_PASSWORD="
                + userPassword + "\nPROBE_DB_URL=jdbc:postgresql://db:5432/generated_app?connectTimeout=2&socketTimeout=5\n"
                + "PROBE_DB_USER=generated_test\nPROBE_DB_PASSWORD=" + dbPassword + "\n", StandardCharsets.UTF_8);
        String jarSha256;
        try (var docker = IsolatedBackendDockerSupport.local()) {
            docker.createInternalNetwork();
            String workspace = docker.volume("workspace");
            String cache = docker.volume("cache");
            String maven = docker.volume("maven");
            String app = docker.volume("app");
            String probe = docker.volume("probe");
            String data = docker.volume("db-data");
            // Trusted staging performs only copy/chown. Model source is compiled and run later as UID 65532.
            String stage = docker.create(List.of("--pull=never", "--network=none",
                    mount(workspace, "/workspace"), mount(cache, "/repo"), mount(maven, "/maven"),
                    mount(app, "/app"), mount(probe, "/probe"), "--entrypoint=sleep", jdk, "1200"), "stage");
            docker.start(stage);
            docker.copy(extracted.resolve("backend"), stage, "/workspace/");
            docker.copy(isolatedRepository, stage, "/repo/");
            docker.copy(mavenHome, stage, "/maven/");
            docker.copy(offlineSettings, stage, "/maven/offline-settings.xml");
            assertEquals(sha256(input.pom()), dependencies.pomSha256());
            for (var artifact : dependencies.artifacts()) {
                String relative = repository.relativize(artifact.file()).toString().replace('\\', '/');
                var copied = docker.run(List.of("exec", stage, "sha256sum", "/repo/isolated-repository/" + relative),
                        Duration.ofSeconds(10));
                assertEquals(0, copied.exit(), "DEPENDENCY_COPY");
                assertTrue(copied.output().startsWith(artifact.sha256() + "  "),
                        "DEPENDENCY_HASH_MISMATCH");
            }
            Path driver = scratch.resolve("StockUnitIsolatedProbe.java");
            try (var resource = getClass().getResourceAsStream("/generation/fixtures/StockUnitIsolatedProbe.java.txt")) {
                assertNotNull(resource);
                Files.write(driver, resource.readAllBytes());
            }
            docker.copy(driver, stage, "/probe/");
            assertEquals(0, docker.run(List.of("exec", stage, "mkdir", "-p", "/probe/lib"),
                    Duration.ofSeconds(10)).exit(), "DRIVER_LIBRARY_DIRECTORY");
            for (DriverDependency dependency : DRIVER_DEPENDENCIES) {
                Path jar = repository.resolve(dependency.relativePath());
                assertTrue(Files.isRegularFile(jar), "MISSING_TRUSTED_DRIVER_DEPENDENCY");
                assertEquals(dependency.sha256(), sha256(Files.readAllBytes(jar)), "DRIVER_DEPENDENCY_HASH");
                docker.copy(jar, stage, "/probe/lib/");
                var copied = docker.run(List.of("exec", stage, "sha256sum", "/probe/lib/" + jar.getFileName()),
                        Duration.ofSeconds(10));
                assertEquals(0, copied.exit(), "DRIVER_DEPENDENCY_COPY");
                assertTrue(copied.output().startsWith(dependency.sha256() + "  "),
                        "DRIVER_DEPENDENCY_STAGED_HASH");
            }
            assertEquals(0, docker.run(List.of("exec", "-u", "0", stage, "sh", "-c",
                    "chown -R 65532:65532 /workspace /repo /probe && chmod -R a+rX /maven && "
                            + "chmod +x /maven/" + mavenHome.getFileName() + "/bin/mvn"),
                    Duration.ofMinutes(2)).exit(), "STAGING_OWNERSHIP");
            assertArrayEquals(accepted.source(), Files.readAllBytes(extracted.resolve(input.writablePath())));
            build(docker, stage, jdk, workspace, cache, maven, mavenHome.getFileName().toString());
            Path jar = copyJar(docker, stage, scratch.resolve("generated-backend.jar"));
            jarSha256 = sha256(Files.readAllBytes(jar));
            assertEquals(64, jarSha256.length());
            try (var opened = new JarFile(jar.toFile())) {
                assertNotNull(opened.getEntry("BOOT-INF/classes/com/example/inventory/service/StockUnitServiceImpl.class"));
            }
            docker.copy(jar, stage, "/app/backend.jar");
            String db = docker.runDetached(List.of("--pull=never", "--network=" + docker.network(),
                    "--network-alias=db", "--pids-limit=128", "--memory=512m", "--cpus=1",
                    "--env-file=" + dbEnv, mount(data, "/var/lib/postgresql/data"), postgres), "db");
            awaitDatabase(docker, db);
            var bootstrap = restricted(jdk, docker.network(), app, true, 768);
            bootstrap.add(bootstrap.size() - 1, "--env-file=" + bootstrapEnv);
            bootstrap.addAll(List.of("java", "-jar", "/app/backend.jar", "bootstrap-admin"));
            String firstAdmin = docker.runDetached(bootstrap, "bootstrap");
            assertExitedZero(docker, firstAdmin, Duration.ofMinutes(2), "BOOTSTRAP");
            assertEquals("1", sql(docker, db, "SELECT count(*) FROM user_accounts"));
            Path roleSql = scratch.resolve("role-user.sql");
            UUID userId = UUID.randomUUID();
            Files.writeString(roleSql, "INSERT INTO user_accounts(id,username,password_hash,enabled) VALUES ('"
                    + userId + "','OrdinaryUser','" + new BCryptPasswordEncoder().encode(userPassword)
                    + "',true);\nINSERT INTO user_account_roles(user_id,role) VALUES ('"
                    + userId + "','ROLE_USER');\n", StandardCharsets.UTF_8);
            docker.copy(roleSql, db, "/tmp/role-user.sql");
            assertEquals(0, docker.run(List.of("exec", db, "psql", "-X", "-q", "-v", "ON_ERROR_STOP=1",
                    "-U", "generated_test", "-d", "generated_app", "-f", "/tmp/role-user.sql"),
                    Duration.ofSeconds(20)).exit(), "ROLE_USER_SETUP");
            runAppAndProbe(docker, jdk, app, probe, appEnv, probeEnv, "first");
            assertEquals("1", sql(docker, db, "SELECT count(*) FROM stock_unit"));
            assertEquals("beta", sql(docker, db, "SELECT label FROM stock_unit"));
            assertEquals("FirstAdmin", sql(docker, db, "SELECT created_by FROM stock_unit"));
            assertEquals("FirstAdmin", sql(docker, db, "SELECT last_modified_by FROM stock_unit"));
            runAppAndProbe(docker, jdk, app, probe, appEnv, probeEnv, "second");
            assertEquals("0", sql(docker, db, "SELECT count(*) FROM stock_unit"));
            assertEquals("true", docker.run(List.of("network", "inspect", "--format", "{{.Internal}}",
                    docker.network()), Duration.ofSeconds(10)).output().trim());
            assertArrayEquals(accepted.source(), assembled.file(accepted.path()));
            assertEquals(sha256(accepted.source()), sha256(assembled.file(accepted.path())));
        } finally {
            for (Path path : List.of(dbEnv, bootstrapEnv, appEnv, probeEnv, offlineSettings,
                    scratch.resolve("role-user.sql")))
                Files.deleteIfExists(path);
        }
        Path attempt = Files.createDirectories(evidenceRoot).resolve("mock-attempt-" + UUID.randomUUID());
        Files.createDirectory(attempt);
        Files.writeString(attempt.resolve("result.txt"),
                "provider=mocked\nsourceSha256=" + sha256(accepted.source())
                        + "\njarSha256=" + jarSha256 + "\ncontainerCrud=passed\ncleanup=confirmed\n",
                StandardCharsets.UTF_8);
        Files.copy(extracted.resolve("generation-manifest.json"), attempt.resolve("generation-manifest.json"));
    }

    private static void build(IsolatedBackendDockerSupport docker, String stage, String image, String workspace,
            String cache, String maven, String mavenFolder) throws Exception {
        assertTrue(mavenFolder.matches("[A-Za-z0-9._-]+"));
        var args = restricted(image, "none", workspace, false, 1024);
        int imageIndex = args.size() - 1;
        args.add(imageIndex, mount(cache, "/repo"));
        args.add(imageIndex + 1, mount(maven, "/maven", true));
        args.add(args.size() - 1, "--workdir=/workspace/backend");
        args.add(args.size() - 1, "--env=HOME=/tmp");
        args.addAll(List.of("/bin/sh", "-c", "/maven/" + mavenFolder
                + "/bin/mvn -o -B -ntp -q -s /maven/offline-settings.xml -f /workspace/backend/pom.xml "
                + "-Dmaven.repo.local=/repo/isolated-repository -Dmaven.compiler.proc=none "
                + "-DskipTests package > /workspace/backend-build.log 2>&1"));
        String container = docker.runDetached(args, "offline-build");
        assertIsolation(docker, container, "none");
        var waited = docker.waitFor(container, Duration.ofMinutes(5));
        assertEquals(0, waited.exit(), "OFFLINE_BUILD_TIMEOUT");
        if (!"0".equals(waited.output().trim())) {
            var diagnostic = docker.run(List.of("exec", stage, "sh", "-c",
                    "tail -n 30 /workspace/backend-build.log"), Duration.ofSeconds(10));
            String log = diagnostic.output();
            String reason = log.contains("Could not find artifact") || log.contains("not been downloaded")
                    ? "MISSING_OFFLINE_ARTIFACT" : log.contains("COMPILATION ERROR")
                    ? "COMPILATION" : log.contains("Permission denied")
                    ? "PERMISSION" : log.contains("No such file")
                    ? "MISSING_PATH" : "OTHER";
            if (reason.equals("MISSING_OFFLINE_ARTIFACT")) {
                var coordinate = java.util.regex.Pattern.compile(
                        "([A-Za-z0-9_.-]+):([A-Za-z0-9_.-]+):(jar|pom):([A-Za-z0-9_.-]+)")
                        .matcher(log);
                if (coordinate.find()) reason += "_" + coordinate.group(1) + "_" + coordinate.group(2)
                        + "_" + coordinate.group(4);
            }
            fail("OFFLINE_BUILD_" + reason);
        }
    }

    private static Path copyJar(IsolatedBackendDockerSupport docker, String stage, Path target) throws Exception {
        var found = docker.run(List.of("exec", stage, "sh", "-c",
                "find /workspace/backend/target -maxdepth 1 -type f -name '*.jar' ! -name '*.jar.original'"),
                Duration.ofSeconds(10));
        assertEquals(0, found.exit(), "JAR_LOOKUP");
        assertTrue(found.output().matches("/workspace/backend/target/[A-Za-z0-9._-]+\\.jar"), "ONE_JAR_REQUIRED");
        assertEquals(0, docker.run(List.of("cp", stage + ":" + found.output(), target.toString()),
                Duration.ofSeconds(30)).exit(), "JAR_COPY");
        return target;
    }

    private static void runAppAndProbe(IsolatedBackendDockerSupport docker, String image, String app, String probe,
            Path appEnv, Path probeEnv, String phase) throws Exception {
        var appArgs = restricted(image, docker.network(), app, true, 768);
        appArgs.add(appArgs.size() - 1, "--network-alias=app");
        appArgs.add(appArgs.size() - 1, "--env-file=" + appEnv);
        appArgs.addAll(List.of("java", "-jar", "/app/backend.jar", "--spring.profiles.active=test",
                "--server.address=0.0.0.0", "--spring.main.banner-mode=off"));
        String running = docker.runDetached(appArgs, "app-" + phase);
        assertIsolation(docker, running, docker.network());
        var probeArgs = restricted(image, docker.network(), probe, false, 256);
        probeArgs.add(probeArgs.size() - 1, "--env-file=" + probeEnv);
        probeArgs.add(probeArgs.size() - 1, "--env=PROBE_PHASE=" + phase);
        probeArgs.addAll(List.of("java", "--class-path", "/probe/lib/*", "/probe/StockUnitIsolatedProbe.java"));
        String check = docker.runDetached(probeArgs, "probe-" + phase);
        assertIsolation(docker, check, docker.network());
        var waited = docker.waitFor(check, Duration.ofMinutes(3));
        assertEquals(0, waited.exit(), "CRUD_" + phase.toUpperCase() + "_TIMEOUT");
        if (!"0".equals(waited.output().trim())) {
            var failure = docker.run(List.of("exec", docker.name("stage"), "cat", "/probe/failure-code"),
                    Duration.ofSeconds(10));
            String reason = failure.exit() == 0 && failure.output().matches("PROBE_FAILURE_[A-Z0-9_]{1,80}")
                    ? failure.output() : "PROBE_FAILURE_UNCLASSIFIED";
            fail("CRUD_" + phase.toUpperCase() + "_" + reason);
        }
        var marker = docker.run(List.of("exec", docker.name("stage"), "cat", "/probe/completed-" + phase),
                Duration.ofSeconds(10));
        assertEquals(0, marker.exit(), "PROBE_MARKER_MISSING");
        assertEquals("COMPLETE", marker.output().trim());
        assertEquals(0, docker.run(List.of("rm", "-f", running), Duration.ofSeconds(20)).exit());
    }

    private static List<String> restricted(String image, String network, String volume,
            boolean readonly, int memoryMb) {
        return new ArrayList<>(IsolatedBackendDockerSupport.restricted(image, network, "65532:65532",
                memoryMb, volume, network.equals("none") ? "/workspace" : volume.endsWith("-probe") ? "/probe" : "/app",
                readonly));
    }

    private static void awaitDatabase(IsolatedBackendDockerSupport docker, String db) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(60).toNanos();
        while (System.nanoTime() < deadline) {
            if (docker.run(List.of("exec", db, "pg_isready", "-U", "generated_test", "-d", "generated_app"),
                    Duration.ofSeconds(5)).exit() == 0) return;
            Thread.sleep(500);
        }
        fail("DATABASE_NOT_READY");
    }

    private static String sql(IsolatedBackendDockerSupport docker, String db, String statement) throws Exception {
        var result = docker.run(List.of("exec", db, "psql", "-X", "-A", "-t", "-v", "ON_ERROR_STOP=1",
                "-U", "generated_test", "-d", "generated_app", "-c", statement), Duration.ofSeconds(10));
        assertEquals(0, result.exit(), "DATABASE_QUERY");
        return result.output().trim();
    }

    private static void assertExitedZero(IsolatedBackendDockerSupport docker, String container,
            Duration limit, String stage) throws Exception {
        var waited = docker.waitFor(container, limit);
        assertEquals(0, waited.exit(), stage + "_TIMEOUT");
        assertEquals("0", waited.output().trim(), stage + "_FAILED");
    }

    private static void assertIsolation(IsolatedBackendDockerSupport docker, String container,
            String expectedNetwork) throws Exception {
        var inspected = docker.run(List.of("inspect", "--format",
                "{{.HostConfig.NetworkMode}}|{{.HostConfig.ReadonlyRootfs}}|{{.Config.User}}|"
                        + "{{len .NetworkSettings.Networks}}|{{range .Mounts}}{{.Type}}:{{.Destination}};{{end}}",
                container), Duration.ofSeconds(10));
        assertEquals(0, inspected.exit(), "ISOLATION_INSPECT");
        String[] parts = inspected.output().split("\\|", 5);
        assertEquals(5, parts.length, "ISOLATION_FIELDS");
        assertEquals(expectedNetwork, parts[0]);
        assertEquals("true", parts[1]);
        assertEquals("65532:65532", parts[2]);
        assertEquals("1", parts[3]);
        assertFalse(parts[4].contains("bind:"));
        assertFalse(parts[4].contains("docker.sock"));
    }

    private static String mount(String volume, String target) { return mount(volume, target, false); }
    private static String mount(String volume, String target, boolean readonly) {
        return "--mount=type=volume,src=" + volume + ",dst=" + target + (readonly ? ",readonly" : "");
    }

    private static String required(String name) {
        String value = System.getProperty(name);
        if (value == null || value.isBlank()) throw new IllegalArgumentException("MISSING_" + name);
        return value;
    }
    private static String requiredImage(String name) {
        String value = required(name);
        if (!value.matches("sha256:[0-9a-f]{64}")) throw new IllegalArgumentException("PINNED_IMAGE_REQUIRED");
        return value;
    }
    private static String sha256(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    private Path provisionOfflineRepository(Path pom, Path executable, Path cachedRepository) throws Exception {
        Path target = Files.createDirectory(scratch.resolve("isolated-repository"));
        Path trustedProject = Files.createDirectory(scratch.resolve("trusted-empty-pom"));
        Path trustedPom = trustedProject.resolve("pom.xml");
        Files.copy(pom, trustedPom);
        assertEquals(sha256(Files.readAllBytes(pom)), sha256(Files.readAllBytes(trustedPom)));
        Path settings = scratch.resolve("file-only-maven-settings.xml");
        String source = cachedRepository.toUri().toASCIIString();
        Files.writeString(settings, "<settings xmlns=\"http://maven.apache.org/SETTINGS/1.2.0\">"
                + "<mirrors><mirror><id>reviewed-local-cache</id><mirrorOf>*</mirrorOf><url>"
                + source + "</url></mirror></mirrors></settings>", StandardCharsets.UTF_8);
        var command = List.of(executable.toAbsolutePath().toString(), "-B", "-ntp", "-q",
                "-s", settings.toString(), "-f", trustedPom.toString(),
                "-Dmaven.repo.local=" + target, "-DskipTests", "-Dmaven.compiler.proc=none",
                "-Dspring-boot.repackage.skip=true", "package");
        ProcessBuilder builder = new ProcessBuilder(command).directory(trustedProject.toFile())
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD);
        builder.environment().keySet().removeIf(key -> key.equalsIgnoreCase("OPENROUTER_API_KEY")
                || key.equalsIgnoreCase("OPENROUTER_MODEL"));
        Process process = builder.start();
        try {
            if (!process.waitFor(120, TimeUnit.SECONDS)) {
                process.descendants().forEach(ProcessHandle::destroyForcibly);
                process.destroyForcibly();
                process.waitFor(5, TimeUnit.SECONDS);
                throw new IllegalStateException("OFFLINE_PROVISION_TIMEOUT");
            }
            if (process.exitValue() != 0) throw new IllegalStateException("OFFLINE_PROVISION_FAILED");
        } finally {
            Files.deleteIfExists(settings);
        }
        return target;
    }
}
