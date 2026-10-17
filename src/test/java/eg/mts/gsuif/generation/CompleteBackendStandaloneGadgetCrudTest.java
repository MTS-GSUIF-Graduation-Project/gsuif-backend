package eg.mts.gsuif.generation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import eg.mts.gsuif.generation.GenerationCapabilityPreflight.OutputMode;
import eg.mts.gsuif.generation.GenerationSpecification.*;
import eg.mts.gsuif.validator.MetadataSchemaValidator;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.sql.DriverManager;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** Fixture-based runtime CRUD acceptance; AI-produced service behavior is a separate acceptance step. */
class CompleteBackendStandaloneGadgetCrudTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String ROOT = "/api/v1/gadgets";
    private static final CompleteBackendPackageAssembler.Provenance FIXTURE =
            new CompleteBackendPackageAssembler.Provenance("handwritten-test-fixture", "1", "gadget-reference", true);
    @TempDir Path scratch;

    @Test void functionalGadgetJarRunsCrudThroughBootstrapLoginAndRestart() throws Exception {
        requireOptIn();
        StandaloneBackendTestSupport processes = new StandaloneBackendTestSupport(scratch);
        Path jar = buildFixtureJar(processes);
        String dbPassword = "disposable-" + UUID.randomUUID();
        String adminPassword = "admin-" + UUID.randomUUID();
        String secondAdminPassword = "admin-" + UUID.randomUUID();
        String userPassword = "user-" + UUID.randomUUID();
        String jwt = randomSecret();
        String ownershipLabel = "gsuif.scrum65.gadget-crud=" + UUID.randomUUID();
        Path envFile = scratch.resolve("gadget-crud-postgres.env");
        Files.writeString(envFile, "POSTGRES_PASSWORD=" + dbPassword + "\n", StandardCharsets.UTF_8);
        try {
            String name = "gsuif-scrum65-gadget-crud-" + UUID.randomUUID();
            var created = processes.docker(List.of("run", "-d", "--name", name, "--label", ownershipLabel,
                    "--env-file", envFile.toString(), "-e", "POSTGRES_USER=generated_test",
                    "-e", "POSTGRES_DB=generated_app", "-p", "127.0.0.1::5432",
                    System.getProperty("gsuif.test.postgres-image")));
            assertEquals(0, created.exit(), "Disposable PostgreSQL could not start");
            assertTrue(created.output().matches("[a-f0-9]{64}"));
            String container = created.output();
            String port = processes.docker(List.of("port", container, "5432/tcp")).output().trim()
                    .replaceFirst("^127\\.0\\.0\\.1:", "");
            assertTrue(port.matches("[0-9]{2,5}"));
            String url = "jdbc:postgresql://127.0.0.1:" + port
                    + "/generated_app?connectTimeout=5&socketTimeout=5";
            awaitPostgres(processes, container);

            Path bootstrapLog = scratch.resolve("gadget-crud-bootstrap.log");
            Process bootstrap = processes.bootstrapJar(jar, url, "generated_test", dbPassword,
                    "FirstAdmin", adminPassword, bootstrapLog);
            try {
                assertTrue(bootstrap.waitFor(100, TimeUnit.SECONDS), "Bootstrap command timed out");
                assertEquals(0, bootstrap.exitValue(), "Bootstrap command failed: "
                        + StandaloneBackendTestSupport.safeLines(bootstrapLog));
            } finally {
                StandaloneBackendTestSupport.stop(bootstrap);
                processes.preserveEvidence(bootstrapLog, dbPassword, adminPassword);
            }
            assertEquals(1, count(url, dbPassword, "SELECT count(*) FROM user_accounts"));
            assertEquals(1, count(url, dbPassword, "SELECT count(*) FROM user_account_roles WHERE role='ROLE_ADMIN'"));
            assertEquals(0, count(url, dbPassword, "SELECT count(*) FROM gadget_aud"));
            createAccount(url, dbPassword, "OrdinaryUser", userPassword, "ROLE_USER");
            createAccount(url, dbPassword, "SecondAdmin", secondAdminPassword, "ROLE_ADMIN");

            int webPort = StandaloneBackendTestSupport.freePort();
            Path firstLog = scratch.resolve("gadget-crud-startup-1.log");
            Process app = processes.startJar(jar, url, "generated_test", dbPassword, jwt, webPort, firstLog);
            UUID id;
            String adminToken;
            String secondToken;
            try {
                awaitHttp(app, webPort, firstLog);
                HttpResponse<String> unauthenticated = send(webPort, "GET", ROOT + "?status=OPEN", null, null);
                assertError(unauthenticated, 401, "Authentication required");
                adminToken = login(webPort, "FirstAdmin", adminPassword);
                secondToken = login(webPort, "SecondAdmin", secondAdminPassword);
                String userToken = login(webPort, "OrdinaryUser", userPassword);
                assertError(send(webPort, "GET", ROOT + "?status=OPEN", userToken, null),
                        403, "Access denied");
                assertEquals(0, count(url, dbPassword, "SELECT count(*) FROM gadget_aud"));

                assertError(send(webPort, "POST", ROOT, adminToken,
                        "{\"title\":\"alpha\",\"status\":\"OPEN\"}"), 400, "Invalid request");
                assertError(send(webPort, "POST", ROOT, adminToken,
                        "{\"title\":\"alpha\",\"status\":\"INVALID\",\"note\":null}"),
                        400, "Invalid request");
                assertEquals(0, count(url, dbPassword, "SELECT count(*) FROM gadget"));
                assertEquals(0, count(url, dbPassword, "SELECT count(*) FROM gadget_aud"));
                JsonNode createdBody = assertSuccess(send(webPort, "POST", ROOT, adminToken,
                        "{\"title\":\"alpha\",\"status\":\"OPEN\",\"note\":null}"), 201);
                id = UUID.fromString(createdBody.path("id").asText());
                assertEquals("alpha", createdBody.path("title").asText());
                assertEquals("OPEN", createdBody.path("status").asText());
                assertTrue(createdBody.path("note").isNull());
                assertRevision(url, dbPassword, id, 0, 0, "FirstAdmin", "FirstAdmin", "alpha", "OPEN");
                assertEquals("alpha", assertSuccess(send(webPort, "GET", ROOT + "/" + id, adminToken, null), 200)
                        .path("title").asText());
                JsonNode listed = assertSuccess(send(webPort, "GET", ROOT + "?status=OPEN",
                        adminToken, null), 200);
                assertEquals(1, listed.path("totalElements").asLong());
                assertEquals(id.toString(), listed.path("data").get(0).path("id").asText());
                assertEquals(0, assertSuccess(send(webPort, "GET", ROOT + "?status=CLOSED",
                        adminToken, null), 200).path("totalElements").asLong());
                assertError(send(webPort, "PUT", ROOT + "/" + id, secondToken,
                        "{\"title\":\"beta\",\"status\":\"CLOSED\"}"), 400, "Invalid request");
                assertError(send(webPort, "PUT", ROOT + "/" + id, secondToken,
                        "{\"title\":\"beta\",\"status\":\"INVALID\",\"note\":null}"),
                        400, "Invalid request");
                assertEquals("alpha", databaseText(url, dbPassword,
                        "SELECT title FROM gadget WHERE id='" + id + "'"));
                assertEquals(1, count(url, dbPassword, "SELECT count(*) FROM gadget_aud"));
                JsonNode updated = assertSuccess(send(webPort, "PUT", ROOT + "/" + id, secondToken,
                        "{\"title\":\"beta\",\"status\":\"CLOSED\",\"note\":\"memo\"}"), 200);
                assertEquals("beta", updated.path("title").asText());
                assertEquals("CLOSED", updated.path("status").asText());
                assertEquals("memo", updated.path("note").asText());
                assertEquals("FirstAdmin", databaseText(url, dbPassword,
                        "SELECT created_by FROM gadget WHERE id='" + id + "'"));
                assertEquals("SecondAdmin", databaseText(url, dbPassword,
                        "SELECT last_modified_by FROM gadget WHERE id='" + id + "'"));
                assertRevision(url, dbPassword, id, 1, 1, "FirstAdmin", "SecondAdmin", "beta", "CLOSED");
            } finally {
                StandaloneBackendTestSupport.stop(app);
                processes.preserveEvidence(firstLog, dbPassword, jwt);
            }

            int restartPort = StandaloneBackendTestSupport.freePort();
            Path secondLog = scratch.resolve("gadget-crud-startup-2.log");
            Process restarted = processes.startJar(jar, url, "generated_test", dbPassword, jwt, restartPort, secondLog);
            try {
                awaitHttp(restarted, restartPort, secondLog);
                JsonNode retained = assertSuccess(send(restartPort, "GET", ROOT + "/" + id, adminToken, null), 200);
                assertEquals("beta", retained.path("title").asText());
                assertEquals("CLOSED", retained.path("status").asText());
                assertEquals("memo", retained.path("note").asText());
                assertEquals(1, count(url, dbPassword, "SELECT count(*) FROM gadget"));
                assertEquals("FirstAdmin", databaseText(url, dbPassword,
                        "SELECT created_by FROM gadget WHERE id='" + id + "'"));
                assertEquals("SecondAdmin", databaseText(url, dbPassword,
                        "SELECT last_modified_by FROM gadget WHERE id='" + id + "'"));
                assertEquals(2, count(url, dbPassword, "SELECT count(*) FROM gadget_aud"));
                JsonNode cleared = assertSuccess(send(restartPort, "PUT", ROOT + "/" + id, secondToken,
                        "{\"title\":\"beta\",\"status\":\"CLOSED\",\"note\":null}"), 200);
                assertTrue(cleared.path("note").isNull());
                assertEquals(1, count(url, dbPassword,
                        "SELECT count(*) FROM gadget WHERE id='" + id + "' AND note IS NULL"));
                assertRevision(url, dbPassword, id, 2, 1, "FirstAdmin", "SecondAdmin", "beta", "CLOSED");
                assertTrue(assertSuccess(send(restartPort, "DELETE", ROOT + "/" + id, secondToken, null), 200).isNull());
                assertEquals(0, count(url, dbPassword, "SELECT count(*) FROM gadget"));
                assertRevision(url, dbPassword, id, 3, 2, null, null, null, null);
                assertError(send(restartPort, "GET", ROOT + "/" + id, adminToken, null), 404,
                        "Resource not found");
                assertError(send(restartPort, "PUT", ROOT + "/" + id, adminToken,
                        "{\"title\":\"beta\",\"status\":\"CLOSED\",\"note\":null}"),
                        404, "Resource not found");
                assertError(send(restartPort, "DELETE", ROOT + "/" + id, adminToken, null), 404,
                        "Resource not found");
                assertEquals(4, count(url, dbPassword, "SELECT count(*) FROM gadget_aud"));
            } finally {
                StandaloneBackendTestSupport.stop(restarted);
                processes.preserveEvidence(secondLog, dbPassword, jwt);
            }
        } finally {
            processes.cleanupOwned("gadget-crud", ownershipLabel, envFile, dbPassword);
        }
    }

    private Path buildFixtureJar(StandaloneBackendTestSupport processes) throws Exception {
        var fixture = new CompleteOutputTestFixture();
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
        var plan = GenerationFilePlan.forDescriptor(descriptor);
        var runtime = new CompleteBackendRuntimeRenderer(registry);
        var input = BackendModelInput.from(descriptor, plan,
                new CompleteBackendDefinitionRenderer().render(descriptor, plan), runtime);
        byte[] fixtureBytes;
        try (var resource = getClass().getResourceAsStream("/generation/fixtures/GadgetServiceImpl.java.txt")) {
            assertNotNull(resource);
            fixtureBytes = resource.readAllBytes();
        }
        var response = JSON.createObjectNode();
        response.putArray("files").addObject().put("path", input.writablePath())
                .put("content", new String(fixtureBytes, StandardCharsets.UTF_8));
        // The extracted standalone Maven build compiles the complete app; no model call or fallback occurs.
        var accepted = new BackendModelOutputValidator((chosen, bytes) -> true)
                .validate(input, JSON.writeValueAsBytes(response));
        assertArrayEquals(fixtureBytes, accepted.source());
        var files = new CompleteBackendPackageAssembler(runtime).assemble(input, accepted, FIXTURE).files();
        Path extracted = Files.createDirectory(scratch.resolve("gadget-crud-package"));
        for (var entry : files.entrySet()) {
            Path destination = extracted.resolve(entry.getKey()).normalize();
            assertTrue(destination.startsWith(extracted));
            Files.createDirectories(destination.getParent());
            Files.write(destination, entry.getValue());
        }
        assertArrayEquals(fixtureBytes, Files.readAllBytes(extracted.resolve(input.writablePath())));
        String contractPath = "contracts/" + descriptor.contractId() + "-" + descriptor.contractVersion() + ".yaml";
        assertArrayEquals(input.contract(), Files.readAllBytes(extracted.resolve(contractPath)));
        var manifest = JSON.readTree(Files.readAllBytes(extracted.resolve("generation-manifest.json")));
        assertTrue(manifest.path("provider").path("mocked").asBoolean());
        assertEquals("handwritten-test-fixture", manifest.path("provider").path("id").asText());
        assertEquals(files.size() - 1, manifest.path("files").size());
        return processes.buildJar("gadget-crud", extracted);
    }

    private static void requireOptIn() {
        Assumptions.assumeTrue(Boolean.getBoolean("gsuif.test.standalone-crud"),
                "Standalone functional CRUD requires explicit opt-in");
        for (String property : List.of("gsuif.test.maven-command", "maven.repo.local",
                "gsuif.test.postgres-image", "gsuif.test.startup-evidence-dir"))
            assertNotNull(System.getProperty(property), "Missing opt-in setting: " + property);
        assertEquals(21, Runtime.version().feature());
    }

    private static String randomSecret() {
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        return Base64.getEncoder().encodeToString(bytes);
    }

    private void awaitPostgres(StandaloneBackendTestSupport processes, String container) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(60).toNanos();
        while (System.nanoTime() < deadline) {
            if (processes.docker(List.of("exec", container, "pg_isready", "-U", "generated_test",
                    "-d", "generated_app")).exit() == 0) return;
            Thread.sleep(500);
        }
        fail("Disposable PostgreSQL did not become ready");
    }

    private void awaitHttp(Process app, int port, Path log) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(100).toNanos();
        while (System.nanoTime() < deadline && app.isAlive()) {
            try {
                if (send(port, "GET", "/v3/api-docs", null, null).statusCode() == 200) return;
            } catch (java.io.IOException ignored) { }
            Thread.sleep(500);
        }
        fail("Generated JAR did not reach HTTP readiness: " + StandaloneBackendTestSupport.safeLines(log));
    }

    private HttpResponse<String> send(int port, String method, String path, String bearer, String body) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .timeout(Duration.ofSeconds(5));
        if (bearer != null) request.header("Authorization", "Bearer " + bearer);
        if (body != null) request.header("Content-Type", "application/json");
        request.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(body));
        return HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build()
                .send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private String login(int port, String username, String password) throws Exception {
        String request = JSON.writeValueAsString(Map.of("username", username, "password", password));
        HttpResponse<String> response = send(port, "POST", "/api/auth/login", null, request);
        assertEquals(200, response.statusCode());
        JsonNode login = envelope(response);
        assertEquals("OK", login.path("status").asText());
        assertEquals("Authenticated", login.path("clientMessage").asText());
        assertEquals(200, login.path("statusCode").asInt());
        assertTrue(login.path("errors").isNull());
        JsonNode body = login.path("body");
        assertEquals("Bearer", body.path("tokenType").asText());
        assertEquals(900, body.path("expiresInSeconds").asLong());
        String token = body.path("accessToken").asText();
        assertFalse(token.isBlank());
        return token;
    }

    private JsonNode assertSuccess(HttpResponse<String> response, int status) throws Exception {
        assertEquals(status, response.statusCode());
        JsonNode envelope = envelope(response);
        assertEquals("OK", envelope.path("status").asText());
        assertEquals("Success", envelope.path("clientMessage").asText());
        assertEquals(status, envelope.path("statusCode").asInt());
        assertTrue(envelope.path("errors").isNull());
        return envelope.path("body");
    }

    private void assertError(HttpResponse<String> response, int status, String message) throws Exception {
        assertEquals(status, response.statusCode());
        JsonNode envelope = envelope(response);
        assertEquals(org.springframework.http.HttpStatus.valueOf(status).name(), envelope.path("status").asText());
        assertEquals(status, envelope.path("statusCode").asInt());
        assertEquals(message, envelope.path("clientMessage").asText());
        assertTrue(envelope.path("body").isNull());
        assertTrue(envelope.path("errors").isObject());
        assertTrue(envelope.path("errors").size() >= 1);
        envelope.path("errors").fieldNames().forEachRemaining(key ->
                assertTrue(Set.of("_global", "title", "status", "note").contains(key)));
        if (status != 400) {
            assertEquals(1, envelope.path("errors").size());
            assertEquals(message, envelope.path("errors").path("_global").asText());
        }
    }

    private JsonNode envelope(HttpResponse<String> response) throws Exception {
        JsonNode envelope = JSON.readTree(response.body());
        assertEquals(Set.of("status", "clientMessage", "statusCode", "body", "errors"),
                JSON.readValue(response.body(), Map.class).keySet());
        return envelope;
    }

    private void createAccount(String url, String dbPassword, String username, String password,
            String roleName) throws Exception {
        UUID id = UUID.randomUUID();
        String hash = new BCryptPasswordEncoder().encode(password);
        try (var connection = DriverManager.getConnection(url, "generated_test", dbPassword)) {
            try (var account = connection.prepareStatement(
                    "INSERT INTO user_accounts(id, username, password_hash, enabled) VALUES (?, ?, ?, true)")) {
                account.setObject(1, id); account.setString(2, username); account.setString(3, hash);
                assertEquals(1, account.executeUpdate());
            }
            try (var role = connection.prepareStatement(
                    "INSERT INTO user_account_roles(user_id, role) VALUES (?, ?)")) {
                role.setObject(1, id); role.setString(2, roleName);
                assertEquals(1, role.executeUpdate());
            }
        }
    }

    private void assertRevision(String url, String password, UUID id, int index, int revisionType,
            String creator, String modifier, String title, String status) throws Exception {
        try (var connection = DriverManager.getConnection(url, "generated_test", password);
             var statement = connection.prepareStatement("SELECT a.revtype, a.created_by, a.last_modified_by, "
                     + "a.title, a.status, r.rev FROM gadget_aud a JOIN app_revision r ON r.rev = a.rev "
                     + "WHERE a.id = ? ORDER BY a.rev")) {
            statement.setObject(1, id);
            try (var rows = statement.executeQuery()) {
                for (int i = 0; i <= index; i++) assertTrue(rows.next(), "Missing Envers revision " + index);
                assertEquals(revisionType, rows.getInt(1));
                assertTrue(rows.getLong(6) > 0);
                if (revisionType != 2) {
                    assertEquals(creator, rows.getString(2));
                    assertEquals(modifier, rows.getString(3));
                    assertEquals(title, rows.getString(4));
                    assertEquals(status, rows.getString(5));
                }
                assertFalse(rows.next(), "Unexpected extra Envers revision");
            }
        }
        assertEquals(index + 1, count(url, password, "SELECT count(*) FROM app_revision"));
    }

    private long count(String url, String password, String sql) throws Exception {
        try (var connection = DriverManager.getConnection(url, "generated_test", password);
             var statement = connection.createStatement(); var rows = statement.executeQuery(sql)) {
            assertTrue(rows.next()); return rows.getLong(1);
        }
    }

    private String databaseText(String url, String password, String sql) throws Exception {
        try (var connection = DriverManager.getConnection(url, "generated_test", password);
             var statement = connection.createStatement(); var rows = statement.executeQuery(sql)) {
            assertTrue(rows.next()); return rows.getString(1);
        }
    }
}
