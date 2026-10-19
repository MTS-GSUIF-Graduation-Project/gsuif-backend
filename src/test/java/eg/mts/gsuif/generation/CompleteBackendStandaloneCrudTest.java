package eg.mts.gsuif.generation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import eg.mts.gsuif.generation.GenerationCapabilityPreflight.OutputMode;
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
class CompleteBackendStandaloneCrudTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String ROOT = "/api/v1/stock-units";
    private static final CompleteBackendPackageAssembler.Provenance FIXTURE =
            new CompleteBackendPackageAssembler.Provenance("handwritten-test-fixture", "1", "stock-unit-reference", true);
    @TempDir Path scratch;

    @Test void functionalStockUnitJarRunsCrudThroughBootstrapLoginAndRestart() throws Exception {
        requireOptIn();
        StandaloneBackendTestSupport processes = new StandaloneBackendTestSupport(scratch);
        Path jar = buildFixtureJar(processes);
        String dbPassword = "disposable-" + UUID.randomUUID();
        String adminPassword = "admin-" + UUID.randomUUID();
        String userPassword = "user-" + UUID.randomUUID();
        String jwt = randomSecret();
        String ownershipLabel = "gsuif.scrum65.crud=" + UUID.randomUUID();
        Path envFile = scratch.resolve("stock-unit-crud-postgres.env");
        Files.writeString(envFile, "POSTGRES_PASSWORD=" + dbPassword + "\n", StandardCharsets.UTF_8);
        try {
            String name = "gsuif-scrum65-crud-" + UUID.randomUUID();
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

            Path bootstrapLog = scratch.resolve("stock-unit-crud-bootstrap.log");
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
            createRoleUser(url, dbPassword, userPassword);

            int webPort = StandaloneBackendTestSupport.freePort();
            Path firstLog = scratch.resolve("stock-unit-crud-startup-1.log");
            Process app = processes.startJar(jar, url, "generated_test", dbPassword, jwt, webPort, firstLog);
            UUID id;
            String adminToken;
            try {
                awaitHttp(app, webPort, firstLog);
                HttpResponse<String> unauthenticated = send(webPort, "GET", ROOT + "?condition=OPEN", null, null);
                assertError(unauthenticated, 401, "Authentication required");
                adminToken = login(webPort, "FirstAdmin", adminPassword);
                String userToken = login(webPort, "OrdinaryUser", userPassword);
                assertError(send(webPort, "GET", ROOT + "?condition=OPEN", userToken, null),
                        403, "Access denied");

                assertError(send(webPort, "POST", ROOT, adminToken,
                        "{\"label\":\"alpha\",\"condition\":\"OPEN\"}"), 400, "Invalid request");
                assertEquals(0, count(url, dbPassword, "SELECT count(*) FROM stock_unit"));
                JsonNode createdBody = assertSuccess(send(webPort, "POST", ROOT, adminToken,
                        "{\"label\":\"alpha\",\"condition\":\"OPEN\",\"retiredOn\":null}"), 201);
                id = UUID.fromString(createdBody.path("id").asText());
                assertEquals("alpha", createdBody.path("label").asText());
                assertEquals("OPEN", createdBody.path("condition").asText());
                assertTrue(createdBody.path("retiredOn").isNull());
                assertEquals("alpha", assertSuccess(send(webPort, "GET", ROOT + "/" + id, adminToken, null), 200)
                        .path("label").asText());
                JsonNode listed = assertSuccess(send(webPort, "GET", ROOT + "?condition=OPEN&label=alpha",
                        adminToken, null), 200);
                assertEquals(1, listed.path("totalElements").asLong());
                assertEquals(id.toString(), listed.path("data").get(0).path("id").asText());
                assertEquals(0, assertSuccess(send(webPort, "GET", ROOT + "?condition=CLOSED&label=alpha",
                        adminToken, null), 200).path("totalElements").asLong());
                assertError(send(webPort, "PUT", ROOT + "/" + id, adminToken,
                        "{\"label\":\"beta\",\"condition\":\"CLOSED\"}"), 400, "Invalid request");
                assertEquals("alpha", databaseText(url, dbPassword,
                        "SELECT label FROM stock_unit WHERE id='" + id + "'"));
                JsonNode updated = assertSuccess(send(webPort, "PUT", ROOT + "/" + id, adminToken,
                        "{\"label\":\"beta\",\"condition\":\"CLOSED\",\"retiredOn\":\"2026-10-17\"}"), 200);
                assertEquals("beta", updated.path("label").asText());
                assertEquals("CLOSED", updated.path("condition").asText());
                assertEquals("2026-10-17", updated.path("retiredOn").asText());
                assertEquals("FirstAdmin", databaseText(url, dbPassword,
                        "SELECT created_by FROM stock_unit WHERE id='" + id + "'"));
                assertEquals("FirstAdmin", databaseText(url, dbPassword,
                        "SELECT last_modified_by FROM stock_unit WHERE id='" + id + "'"));
            } finally {
                StandaloneBackendTestSupport.stop(app);
                processes.preserveEvidence(firstLog, dbPassword, jwt);
            }

            int restartPort = StandaloneBackendTestSupport.freePort();
            Path secondLog = scratch.resolve("stock-unit-crud-startup-2.log");
            Process restarted = processes.startJar(jar, url, "generated_test", dbPassword, jwt, restartPort, secondLog);
            try {
                awaitHttp(restarted, restartPort, secondLog);
                JsonNode retained = assertSuccess(send(restartPort, "GET", ROOT + "/" + id, adminToken, null), 200);
                assertEquals("beta", retained.path("label").asText());
                assertEquals("CLOSED", retained.path("condition").asText());
                assertEquals("2026-10-17", retained.path("retiredOn").asText());
                assertEquals(1, count(url, dbPassword, "SELECT count(*) FROM stock_unit"));
                JsonNode cleared = assertSuccess(send(restartPort, "PUT", ROOT + "/" + id, adminToken,
                        "{\"label\":\"beta\",\"condition\":\"CLOSED\",\"retiredOn\":null}"), 200);
                assertTrue(cleared.path("retiredOn").isNull());
                assertEquals(1, count(url, dbPassword,
                        "SELECT count(*) FROM stock_unit WHERE id='" + id + "' AND retired_on IS NULL"));
                assertTrue(assertSuccess(send(restartPort, "DELETE", ROOT + "/" + id, adminToken, null), 200).isNull());
                assertEquals(0, count(url, dbPassword, "SELECT count(*) FROM stock_unit"));
                assertError(send(restartPort, "GET", ROOT + "/" + id, adminToken, null), 404,
                        "Resource not found");
                assertError(send(restartPort, "PUT", ROOT + "/" + id, adminToken,
                        "{\"label\":\"beta\",\"condition\":\"CLOSED\",\"retiredOn\":null}"),
                        404, "Resource not found");
                assertError(send(restartPort, "DELETE", ROOT + "/" + id, adminToken, null), 404,
                        "Resource not found");
            } finally {
                StandaloneBackendTestSupport.stop(restarted);
                processes.preserveEvidence(secondLog, dbPassword, jwt);
            }
        } finally {
            processes.cleanupOwned("stock-unit-crud", ownershipLabel, envFile, dbPassword);
        }
    }

    @Test void pagedStockUnitJarFiltersAndOrdersMoreThanTwentyMatches() throws Exception {
        requireOptIn();
        StandaloneBackendTestSupport processes = new StandaloneBackendTestSupport(scratch);
        Path jar = buildFixtureJar(processes, true);
        String dbPassword = "disposable-" + UUID.randomUUID();
        String adminPassword = "admin-" + UUID.randomUUID();
        String jwt = randomSecret();
        String ownershipLabel = "gsuif.scrum65.pagination=" + UUID.randomUUID();
        Path envFile = scratch.resolve("stock-unit-pagination-postgres.env");
        Files.writeString(envFile, "POSTGRES_PASSWORD=" + dbPassword + "\n", StandardCharsets.UTF_8);
        try {
            var created = processes.docker(List.of("run", "-d", "--name", "gsuif-scrum65-pagination-" + UUID.randomUUID(),
                    "--label", ownershipLabel, "--env-file", envFile.toString(),
                    "-e", "POSTGRES_USER=generated_test", "-e", "POSTGRES_DB=generated_app",
                    "-p", "127.0.0.1::5432", System.getProperty("gsuif.test.postgres-image")));
            assertEquals(0, created.exit());
            assertTrue(created.output().matches("[a-f0-9]{64}"));
            String container = created.output();
            String port = processes.docker(List.of("port", container, "5432/tcp")).output().trim()
                    .replaceFirst("^127\\.0\\.0\\.1:", "");
            assertTrue(port.matches("[0-9]{2,5}"));
            String url = "jdbc:postgresql://127.0.0.1:" + port
                    + "/generated_app?connectTimeout=5&socketTimeout=5";
            awaitPostgres(processes, container);
            Path bootstrapLog = scratch.resolve("stock-unit-pagination-bootstrap.log");
            Process bootstrap = processes.bootstrapJar(jar, url, "generated_test", dbPassword,
                    "FirstAdmin", adminPassword, bootstrapLog);
            try {
                assertTrue(bootstrap.waitFor(100, TimeUnit.SECONDS));
                assertEquals(0, bootstrap.exitValue(), StandaloneBackendTestSupport.safeLines(bootstrapLog));
            } finally {
                StandaloneBackendTestSupport.stop(bootstrap);
                processes.preserveEvidence(bootstrapLog, dbPassword, adminPassword);
            }
            int webPort = StandaloneBackendTestSupport.freePort();
            Path appLog = scratch.resolve("stock-unit-pagination-startup.log");
            Process app = processes.startJar(jar, url, "generated_test", dbPassword, jwt, webPort, appLog);
            try {
                awaitHttp(app, webPort, appLog);
                String token = login(webPort, "FirstAdmin", adminPassword);
                List<String> matchingIds = new java.util.ArrayList<>();
                for (int i = 0; i < 27; i++) {
                    JsonNode createdBody = assertSuccess(send(webPort, "POST", ROOT, token,
                            "{\"label\":\"group\",\"condition\":\"OPEN\",\"retiredOn\":null}"), 201);
                    matchingIds.add(createdBody.path("id").asText());
                }
                for (int i = 0; i < 3; i++) assertSuccess(send(webPort, "POST", ROOT, token,
                        "{\"label\":\"other\",\"condition\":\"CLOSED\",\"retiredOn\":null}"), 201);
                matchingIds.sort(String::compareTo);
                JsonNode defaults = assertSuccess(send(webPort, "GET", ROOT + "?condition=OPEN", token, null), 200);
                assertPage(defaults, 0, 20, 27, matchingIds.subList(0, 20));
                JsonNode next = assertSuccess(send(webPort, "GET", ROOT + "?condition=OPEN&page=1", token, null), 200);
                assertPage(next, 1, 20, 27, matchingIds.subList(20, 27));
                JsonNode beyond = assertSuccess(send(webPort, "GET", ROOT + "?condition=OPEN&page=3", token, null), 200);
                assertPage(beyond, 3, 20, 27, List.of());
                List<String> collected = new java.util.ArrayList<>();
                for (int page = 0; page < 4; page++) {
                    JsonNode body = assertSuccess(send(webPort, "GET",
                            ROOT + "?condition=OPEN&label=group&page=" + page + "&size=7", token, null), 200);
                    List<String> expected = matchingIds.subList(page * 7, Math.min((page + 1) * 7, 27));
                    assertPage(body, page, 7, 27, expected);
                    body.path("data").forEach(item -> collected.add(item.path("id").asText()));
                }
                assertEquals(matchingIds, collected, "Pages must have no gaps or duplicates");
                assertPage(assertSuccess(send(webPort, "GET", ROOT + "?condition=CLOSED&size=100", token, null),
                        200), 0, 100, 3, null);
                for (String query : List.of("page=-1", "size=0", "size=101", "page=oops", "size=oops"))
                    assertError(send(webPort, "GET", ROOT + "?condition=OPEN&" + query, token, null),
                            400, "Invalid request");
                assertEquals(30, count(url, dbPassword, "SELECT count(*) FROM stock_unit"));
            } finally {
                StandaloneBackendTestSupport.stop(app);
                processes.preserveEvidence(appLog, dbPassword, jwt);
            }
        } finally {
            processes.cleanupOwned("stock-unit-pagination", ownershipLabel, envFile, dbPassword);
        }
    }

    private static void assertPage(JsonNode page, int number, int size, long total, List<String> ids) {
        assertEquals(number, page.path("number").asInt());
        assertEquals(size, page.path("size").asInt());
        assertEquals(total, page.path("totalElements").asLong());
        assertEquals((int) Math.ceil((double) total / size), page.path("totalPages").asInt());
        if (ids != null) {
            assertEquals(ids.size(), page.path("data").size());
            for (int i = 0; i < ids.size(); i++) assertEquals(ids.get(i), page.path("data").get(i).path("id").asText());
        }
    }

    private Path buildFixtureJar(StandaloneBackendTestSupport processes) throws Exception {
        return buildFixtureJar(processes, false);
    }

    private Path buildFixtureJar(StandaloneBackendTestSupport processes, boolean paged) throws Exception {
        var fixture = new CompleteOutputTestFixture();
        var descriptor = fixture.preflight().describe(fixture.version(paged ? fixture.pagedSnapshot()
                : fixture.snapshot("condition")), paged ? fixture.pagedSpecification()
                : fixture.specification(), OutputMode.BACKEND);
        var registry = new PackagedContractRegistry("/generation/stock-unit-contracts.yaml");
        var plan = GenerationFilePlan.forDescriptor(descriptor);
        var runtime = new CompleteBackendRuntimeRenderer(registry);
        var input = BackendModelInput.from(descriptor, plan,
                new CompleteBackendDefinitionRenderer().render(descriptor, plan), runtime);
        byte[] fixtureBytes;
        try (var resource = getClass().getResourceAsStream(paged
                ? "/generation/fixtures/StockUnitPagedServiceImpl.java.txt"
                : "/generation/fixtures/StockUnitServiceImpl.java.txt")) {
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
        Path extracted = Files.createDirectory(scratch.resolve(paged ? "stock-unit-pagination-package"
                : "stock-unit-crud-package"));
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
        return processes.buildJar(paged ? "stock-unit-pagination" : "stock-unit-crud", extracted);
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
                assertTrue(Set.of("_global", "label", "condition", "retiredOn", "page", "size").contains(key)));
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

    private void createRoleUser(String url, String dbPassword, String password) throws Exception {
        UUID id = UUID.randomUUID();
        String hash = new BCryptPasswordEncoder().encode(password);
        try (var connection = DriverManager.getConnection(url, "generated_test", dbPassword)) {
            try (var account = connection.prepareStatement(
                    "INSERT INTO user_accounts(id, username, password_hash, enabled) VALUES (?, ?, ?, true)")) {
                account.setObject(1, id); account.setString(2, "OrdinaryUser"); account.setString(3, hash);
                assertEquals(1, account.executeUpdate());
            }
            try (var role = connection.prepareStatement(
                    "INSERT INTO user_account_roles(user_id, role) VALUES (?, 'ROLE_USER')")) {
                role.setObject(1, id); assertEquals(1, role.executeUpdate());
            }
        }
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
