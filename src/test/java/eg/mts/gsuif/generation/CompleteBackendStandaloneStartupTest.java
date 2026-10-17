package eg.mts.gsuif.generation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import eg.mts.gsuif.generation.GenerationCapabilityPreflight.OutputMode;
import eg.mts.gsuif.generation.GenerationSpecification.*;
import eg.mts.gsuif.validator.MetadataSchemaValidator;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.sql.DriverManager;
import java.time.Duration;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** Opt-in executable-JAR startup, separate from mocked service behavior. */
class CompleteBackendStandaloneStartupTest {
    private static final Duration BUILD_LIMIT = Duration.ofMinutes(4);
    private static final Duration START_LIMIT = Duration.ofSeconds(100);
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final CompleteBackendPackageAssembler.Provenance MOCK =
            new CompleteBackendPackageAssembler.Provenance("mock-provider", "test-1", "mock-model", true);
    private final CompleteOutputTestFixture fixture = new CompleteOutputTestFixture();
    @TempDir Path scratch;

    private record Selected(BackendModelInput input, CompleteBackendPackageAssembler assembler, String source) { }
    private record CommandResult(int exit, String output) { }

    @Test void unauditedStockUnitStartsAndRestartsAgainstDisposablePostgres() throws Exception {
        requireOptIn();
        var descriptor = fixture.preflight().describe(fixture.version(fixture.snapshot("condition")),
                fixture.specification(), OutputMode.BACKEND);
        Selected selected = selected(descriptor, new PackagedContractRegistry("/generation/stock-unit-contracts.yaml"),
                """
                package com.example.inventory.service;
                @org.springframework.stereotype.Service
                public class StockUnitServiceImpl implements StockUnitService {
                  public com.example.inventory.dto.StockUnitDto create(com.example.inventory.dto.CreateStockUnitRequest request) { return null; }
                  public com.example.inventory.dto.StockUnitDto getById(java.util.UUID id) { return null; }
                  public com.example.inventory.dto.StockUnitPage list(com.example.inventory.dto.StockUnitCondition condition, String label) { return null; }
                  public com.example.inventory.dto.StockUnitDto update(java.util.UUID id, com.example.inventory.dto.UpdateStockUnitRequest request) { return null; }
                  public void delete(java.util.UUID id) { }
                }
                """);
        accept("stock-unit", selected, false, "/api/v1/stock-units");
    }

    @Test void auditedRenamedGadgetStartsAndRestartsAgainstDisposablePostgres() throws Exception {
        requireOptIn();
        var registry = new PackagedContractRegistry("/generation/gadget-contracts.yaml");
        var preflight = new GenerationCapabilityPreflight(
                new GenerationContextBuilder(fixture.mapper, new MetadataSchemaValidator(), registry), registry);
        List<String> operations = List.of("fetchGadgets", "submitGadget", "findGadget", "replaceGadget", "removeGadget");
        Map<UUID, String> bindings = new java.util.LinkedHashMap<>();
        Map<String, Set<SupportedRole>> roles = new java.util.LinkedHashMap<>();
        for (int i = 0; i < operations.size(); i++) {
            bindings.put(new UUID(0, i + 1), operations.get(i));
            roles.put(operations.get(i), Set.of(SupportedRole.ROLE_ADMIN));
        }
        var specification = new GenerationSpecification("1.0.0", "com.example.fixture",
                new EntitySpec("Gadget", "gadget", List.of(
                        new FieldSpec("title", "title", "String", false, 100, null),
                        new FieldSpec("status", "status", "GadgetStatus", false, null, "GadgetStatus"),
                        new FieldSpec("note", "note", "String", true, 100, null)), true),
                new OpenApiSpec("gadget-api", "2.0.0", bindings), roles, new AngularSpec("Gadget", Map.of()));
        ObjectNode snapshot = fixture.snapshot("condition");
        for (int i = 0; i < 5; i++) {
            ObjectNode binding = (ObjectNode) snapshot.withArray("apiBindings").get(i);
            binding.put("endpointUrl", binding.path("endpointUrl").asText().replace("stock-units", "gadgets"));
            if (i == 0) {
                ObjectNode query = (ObjectNode) binding.path("requestMapping").path("query");
                query.removeAll(); query.put("status", "status");
            }
            if (i == 1 || i == 3) {
                ObjectNode body = (ObjectNode) binding.path("requestMapping").path("body");
                body.removeAll(); body.put("title", "title").put("status", "status").put("note", "note");
            }
        }
        var descriptor = preflight.describe(fixture.version(snapshot), specification, OutputMode.BACKEND);
        Selected selected = selected(descriptor, registry, """
                package com.example.fixture.service;
                @org.springframework.stereotype.Service
                public class GadgetServiceImpl implements GadgetService {
                  public com.example.fixture.dto.GadgetDto create(com.example.fixture.dto.CreateGadgetRequest request) { return null; }
                  public com.example.fixture.dto.GadgetDto getById(java.util.UUID id) { return null; }
                  public com.example.fixture.dto.GadgetPage list(com.example.fixture.dto.GadgetStatus status) { return null; }
                  public com.example.fixture.dto.GadgetDto update(java.util.UUID id, com.example.fixture.dto.UpdateGadgetRequest request) { return null; }
                  public void delete(java.util.UUID id) { }
                }
                """);
        accept("gadget", selected, true, "/api/v1/gadgets");
    }

    private Selected selected(CompleteOutputDescriptor descriptor, PackagedContractRegistry registry, String source) {
        var plan = GenerationFilePlan.forDescriptor(descriptor);
        var runtime = new CompleteBackendRuntimeRenderer(registry);
        return new Selected(BackendModelInput.from(descriptor, plan,
                new CompleteBackendDefinitionRenderer().render(descriptor, plan), runtime),
                new CompleteBackendPackageAssembler(runtime), source);
    }

    private void accept(String label, Selected selected, boolean audited, String protectedPath) throws Exception {
        BackendModelInput input = selected.input();
        ObjectNode response = JSON.createObjectNode();
        response.putArray("files").addObject().put("path", input.writablePath()).put("content", selected.source());
        var model = new BackendModelOutputValidator((chosen, bytes) -> true)
                .validate(input, JSON.writeValueAsBytes(response));
        var assembled = selected.assembler().assemble(input, model, MOCK).files();
        Path extracted = Files.createDirectory(scratch.resolve(label + "-package"));
        for (var file : assembled.entrySet()) {
            Path destination = extracted.resolve(file.getKey()).normalize();
            assertTrue(destination.startsWith(extracted));
            Files.createDirectories(destination.getParent());
            Files.write(destination, file.getValue());
        }
        verifyManifest(input, extracted, assembled.keySet());
        assertTrue(Files.readString(extracted.resolve("backend/src/main/resources/application.yml"),
                StandardCharsets.UTF_8).contains("ddl-auto: validate"));
        Path jar = buildJar(label, extracted);
        String password = "disposable-" + UUID.randomUUID();
        String username = "generated_test";
        String database = "generated_app";
        Path envFile = scratch.resolve(label + "-postgres.env");
        Files.writeString(envFile, "POSTGRES_PASSWORD=" + password + "\n", StandardCharsets.UTF_8);
        String container = null;
        String ownershipLabel = "gsuif.scrum65.startup=" + UUID.randomUUID();
        try {
            String name = "gsuif-scrum65-startup-" + UUID.randomUUID();
            CommandResult created = docker(List.of("run", "-d", "--name", name, "--label", ownershipLabel,
                    "--env-file", envFile.toString(),
                    "-e", "POSTGRES_USER=" + username, "-e", "POSTGRES_DB=" + database,
                    "-p", "127.0.0.1::5432", System.getProperty("gsuif.test.postgres-image")));
            assertEquals(0, created.exit(), "Disposable PostgreSQL container could not start");
            container = created.output().trim();
            assertTrue(container.matches("[a-f0-9]{64}"), "Docker did not return a container ID");
            String port = docker(List.of("port", container, "5432/tcp")).output().trim()
                    .replaceFirst("^127\\.0\\.0\\.1:", "");
            assertTrue(port.matches("[0-9]{2,5}"), "Docker did not assign a loopback port");
            String url = "jdbc:postgresql://127.0.0.1:" + port + "/" + database;
            awaitPostgres(container, username, database);
            byte[] secret = new byte[32]; new SecureRandom().nextBytes(secret);
            String jwt = Base64.getEncoder().encodeToString(secret);
            for (int launch = 1; launch <= 2; launch++) {
                int webPort = freePort();
                Path log = scratch.resolve(label + "-startup-" + launch + ".log");
                Process app = startJar(jar, url, username, password, jwt, webPort, log);
                try {
                    awaitHttp(app, webPort, log);
                    verifyHttp(webPort, input, protectedPath);
                    verifyDatabase(url, username, password, audited);
                } finally {
                    stop(app);
                    preserveEvidence(log, password, jwt);
                }
            }
        } finally {
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
    }

    private void verifyManifest(BackendModelInput input, Path extracted, Set<String> paths) throws Exception {
        var manifest = JSON.readTree(Files.readAllBytes(extracted.resolve("generation-manifest.json")));
        assertTrue(manifest.path("provider").path("mocked").asBoolean());
        assertEquals(paths.size() - 1, manifest.path("files").size());
        Set<String> listed = new TreeSet<>();
        for (var entry : manifest.path("files")) {
            String path = entry.path("path").asText();
            assertTrue(listed.add(path));
            byte[] bytes = Files.readAllBytes(extracted.resolve(path));
            assertEquals(bytes.length, entry.path("bytes").asInt());
            assertEquals(sha(bytes), entry.path("sha256").asText());
        }
        Set<String> expected = new TreeSet<>(paths); expected.remove("generation-manifest.json");
        assertEquals(expected, listed);
        String contractPath = "contracts/" + input.descriptor().contractId() + "-"
                + input.descriptor().contractVersion() + ".yaml";
        assertArrayEquals(input.contract(), Files.readAllBytes(extracted.resolve(contractPath)));
        assertEquals(input.descriptor().contractResourceSha256(), sha(input.contract()));
    }

    private Path buildJar(String label, Path extracted) throws Exception {
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
            assertTrue(process.waitFor(BUILD_LIMIT.toSeconds(), TimeUnit.SECONDS),
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

    private Process startJar(Path jar, String url, String username, String password, String jwt,
            int port, Path log) throws Exception {
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        ProcessBuilder builder = new ProcessBuilder(java, "-jar", jar.toString(),
                "--spring.profiles.active=test", "--server.address=127.0.0.1", "--server.port=" + port,
                "--spring.main.banner-mode=off").redirectErrorStream(true).redirectOutput(log.toFile());
        Map<String, String> environment = builder.environment();
        String systemRoot = environment.get("SystemRoot");
        String temp = environment.get("TEMP");
        String tmp = environment.get("TMP");
        environment.clear();
        if (systemRoot != null) environment.put("SystemRoot", systemRoot);
        if (temp != null) environment.put("TEMP", temp);
        if (tmp != null) environment.put("TMP", tmp);
        environment.put("GENERATED_APP_DB_URL", url);
        environment.put("GENERATED_APP_DB_USER", username);
        environment.put("GENERATED_APP_DB_PASSWORD", password);
        environment.put("GENERATED_APP_JWT_SECRET", jwt);
        assertFalse(environment.containsKey("OPENROUTER_API_KEY"));
        return builder.start();
    }

    private void awaitHttp(Process app, int port, Path log) throws Exception {
        long deadline = System.nanoTime() + START_LIMIT.toNanos();
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
        while (System.nanoTime() < deadline && app.isAlive()) {
            try {
                var response = client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/v3/api-docs"))
                        .timeout(Duration.ofSeconds(3)).GET().build(), HttpResponse.BodyHandlers.discarding());
                if (response.statusCode() == 200) return;
            } catch (java.io.IOException ignored) { }
            Thread.sleep(500);
        }
        fail("Generated JAR did not reach HTTP readiness; exit="
                + (app.isAlive() ? "running" : app.exitValue()) + "; " + safeLines(log));
    }

    private void verifyHttp(int port, BackendModelInput input, String protectedPath) throws Exception {
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
        URI root = URI.create("http://127.0.0.1:" + port);
        var docs = client.send(HttpRequest.newBuilder(root.resolve("/v3/api-docs"))
                        .timeout(Duration.ofSeconds(5)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, docs.statusCode());
        var openapi = JSON.readTree(docs.body());
        assertTrue(openapi.path("paths").has(protectedPath));
        assertTrue(openapi.path("paths").has("/api/auth/login"));
        var denied = client.send(HttpRequest.newBuilder(root.resolve(protectedPath))
                        .timeout(Duration.ofSeconds(5)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(401, denied.statusCode());
        var body = JSON.readTree(denied.body());
        assertEquals(Set.of("status", "clientMessage", "statusCode", "body", "errors"),
                JSON.readValue(denied.body(), Map.class).keySet());
        assertEquals("UNAUTHORIZED", body.path("status").asText());
        assertEquals("Authentication required", body.path("clientMessage").asText());
        assertEquals(401, body.path("statusCode").asInt());
        assertTrue(body.path("body").isNull());
        assertEquals("Authentication required", body.path("errors").path("_global").asText());
        assertEquals(1, body.path("errors").size());
    }

    private void verifyDatabase(String url, String username, String password, boolean audited) throws Exception {
        try (var connection = DriverManager.getConnection(url, username, password);
             var statement = connection.createStatement()) {
            try (var rows = statement.executeQuery("SELECT count(*) FROM flyway_schema_history WHERE version = '1' AND success")) {
                assertTrue(rows.next()); assertEquals(1, rows.getLong(1));
            }
            try (var rows = statement.executeQuery("SELECT count(*) FROM user_accounts")) {
                assertTrue(rows.next()); assertEquals(0, rows.getLong(1));
            }
            try (var rows = statement.executeQuery("SELECT to_regclass('app_revision')")) {
                assertTrue(rows.next()); assertEquals(audited, rows.getString(1) != null);
            }
        }
    }

    private void awaitPostgres(String container, String username, String database) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(60).toNanos();
        while (System.nanoTime() < deadline) {
            if (docker(List.of("exec", container, "pg_isready", "-U", username, "-d", database)).exit() == 0)
                return;
            Thread.sleep(500);
        }
        fail("Disposable PostgreSQL did not become ready");
    }

    private CommandResult docker(List<String> arguments) throws Exception {
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
            String output = Files.readString(log, StandardCharsets.UTF_8).trim();
            return new CommandResult(process.exitValue(), output);
        } finally { stop(process); Files.deleteIfExists(log); }
    }

    private static void removeProviderCredentials(Map<String, String> environment) {
        environment.keySet().removeIf(key -> key.equalsIgnoreCase("OPENROUTER_API_KEY")
                || key.equalsIgnoreCase("OPENROUTER_MODEL"));
    }

    private static void stop(Process process) throws InterruptedException {
        if (process == null || !process.isAlive()) return;
        process.descendants().forEach(ProcessHandle::destroyForcibly);
        process.destroyForcibly();
        process.waitFor(5, TimeUnit.SECONDS);
    }

    private static int freePort() throws Exception {
        try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            return socket.getLocalPort();
        }
    }

    private static String sha(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    private static String safeLines(Path log) throws Exception {
        if (!Files.isRegularFile(log)) return "no child log";
        return Files.readAllLines(log, StandardCharsets.UTF_8).stream()
                .filter(line -> line.contains("ERROR") || line.contains("APPLICATION FAILED")
                        || line.contains("Description:"))
                .limit(8)
                .map(line -> line.replaceAll("(?i)(password|token|authorization|secret|api.key)[=: ]+\\S+", "$1=[redacted]"))
                .map(line -> line.length() > 180 ? line.substring(0, 180) : line)
                .reduce("", (a, b) -> a + "\n" + b);
    }

    private void preserveEvidence(Path source, String password, String jwt) throws Exception {
        preserveEvidence(source.getFileName().toString(), Files.readString(source, StandardCharsets.UTF_8),
                password, jwt);
    }

    private void preserveEvidence(String name, String content, String password, String jwt) throws Exception {
        Path destination = Path.of(System.getProperty("gsuif.test.startup-evidence-dir"))
                .toAbsolutePath().normalize();
        assertFalse(destination.startsWith(scratch.toAbsolutePath().normalize()),
                "Startup evidence must outlive JUnit temporary files");
        Files.createDirectories(destination);
        String safe = content.replace(password, "[redacted]");
        if (jwt != null) safe = safe.replace(jwt, "[redacted]");
        Files.writeString(destination.resolve(name), safe, StandardCharsets.UTF_8);
    }

    private static void requireOptIn() {
        Assumptions.assumeTrue(Boolean.getBoolean("gsuif.test.standalone-startup"),
                "Executable-JAR PostgreSQL startup is an explicit opt-in");
        for (String property : List.of("gsuif.test.maven-command", "maven.repo.local",
                "gsuif.test.postgres-image", "gsuif.test.startup-evidence-dir"))
            assertNotNull(System.getProperty(property), "Missing opt-in setting: " + property);
        assertEquals(21, Runtime.version().feature());
        assertTrue(Files.isRegularFile(Path.of(System.getProperty("gsuif.test.maven-command"))));
        assertTrue(Files.isDirectory(Path.of(System.getProperty("maven.repo.local"))));
    }
}
