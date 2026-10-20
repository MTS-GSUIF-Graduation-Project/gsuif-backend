package eg.mts.gsuif.generation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import eg.mts.gsuif.generation.GenerationCapabilityPreflight.OutputMode;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

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
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * SCRUM-50 end-to-end acceptance of a package NEWLY ASSEMBLED by the current generator for
 * stock-unit-api 3.0.0. The model-owned StockUnitServiceImpl is the saved real model response from
 * the historical run 6b1f398c (no provider call). The package is built with Maven, started against a
 * disposable labelled PostgreSQL container, and exercised over HTTP with valid and contract-invalid
 * requests; persistence is checked directly in the database. Explicit opt-in, like the other
 * standalone acceptance tests.
 */
class GeneratedPackageEndToEndTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String ROOT = "/api/v1/stock-units";
    private static final CompleteBackendPackageAssembler.Provenance SAVED_RESPONSE =
            new CompleteBackendPackageAssembler.Provenance("saved-openrouter-response-6b1f398c", "api-v1",
                    "qwen/qwen3-coder", true);
    @TempDir Path scratch;
    private final List<String> transcript = new ArrayList<>();
    private final Map<String, String> secrets = new java.util.LinkedHashMap<>();

    @Test
    void newlyAssembledStockUnitPackageServesItsContractAgainstPostgres() throws Exception {
        Assumptions.assumeTrue(Boolean.getBoolean("gsuif.test.standalone-crud"), "End-to-end requires explicit opt-in");
        for (String property : List.of("gsuif.test.maven-command", "maven.repo.local", "gsuif.test.postgres-image",
                "gsuif.test.startup-evidence-dir"))
            assertNotNull(System.getProperty(property), "Missing opt-in setting: " + property);
        var processes = new StandaloneBackendTestSupport(scratch);
        Path evidence = Path.of(System.getProperty("gsuif.test.startup-evidence-dir")).toAbsolutePath().normalize();
        Files.createDirectories(evidence);
        Path jar = buildPackage(processes, evidence);

        String dbPassword = "disposable-" + UUID.randomUUID();
        String adminPassword = "admin-" + UUID.randomUUID();
        String jwt = randomSecret();
        secrets.put(dbPassword, "[db-password]");
        secrets.put(adminPassword, "[admin-password]");
        secrets.put(jwt, "[jwt-secret]");
        String ownership = "gsuif.scrum50.e2e=" + UUID.randomUUID();
        Path envFile = scratch.resolve("e2e-postgres.env");
        Files.writeString(envFile, "POSTGRES_PASSWORD=" + dbPassword + "\n", StandardCharsets.UTF_8);
        try {
            var created = processes.docker(List.of("run", "-d", "--name", "gsuif-scrum50-e2e-" + UUID.randomUUID(),
                    "--label", ownership, "--env-file", envFile.toString(), "-e", "POSTGRES_USER=generated_test",
                    "-e", "POSTGRES_DB=generated_app", "-p", "127.0.0.1::5432", System.getProperty("gsuif.test.postgres-image")));
            assertEquals(0, created.exit(), "Disposable PostgreSQL could not start: " + created.output());
            String container = created.output();
            String port = processes.docker(List.of("port", container, "5432/tcp")).output().trim().replaceFirst("^127\\.0\\.0\\.1:", "");
            String url = "jdbc:postgresql://127.0.0.1:" + port + "/generated_app?connectTimeout=5&socketTimeout=5";
            note("Disposable PostgreSQL container " + container.substring(0, 12) + " (label " + ownership
                    + ", image " + System.getProperty("gsuif.test.postgres-image") + ", host port " + port + ")");
            awaitPostgres(processes, container);

            Path bootstrapLog = scratch.resolve("e2e-bootstrap.log");
            Process bootstrap = processes.bootstrapJar(jar, url, "generated_test", dbPassword, "FirstAdmin", adminPassword, bootstrapLog);
            try {
                assertTrue(bootstrap.waitFor(100, TimeUnit.SECONDS));
                assertEquals(0, bootstrap.exitValue(), StandaloneBackendTestSupport.safeLines(bootstrapLog));
            } finally {
                StandaloneBackendTestSupport.stop(bootstrap);
                processes.preserveEvidence(bootstrapLog, dbPassword, adminPassword);
            }
            note("bootstrap-admin exit 0; Flyway migrated; admin accounts = "
                    + count(url, dbPassword, "SELECT count(*) FROM user_accounts"));

            int web = StandaloneBackendTestSupport.freePort();
            Path appLog = scratch.resolve("e2e-application.log");
            Process app = processes.startJar(jar, url, "generated_test", dbPassword, jwt, web, appLog);
            UUID kept;
            String token;
            try {
                awaitHttp(app, web, appLog);
                note("Application started (profile test) on 127.0.0.1:" + web);
                expectError(send(web, "GET", ROOT + "?condition=OPEN", null, null), 401, "_global");
                token = login(web, adminPassword);

                section("CREATE / READ (valid)");
                JsonNode created1 = success(send(web, "POST", ROOT, token, "{\"label\":\"Laptop\",\"condition\":\"OPEN\",\"retiredOn\":null}"), 201);
                kept = UUID.fromString(created1.path("id").asText());
                assertEquals("Laptop", created1.path("label").asText());
                assertEquals("Laptop", db(url, dbPassword, "SELECT label FROM stock_unit WHERE id='" + kept + "'"));
                assertEquals("OPEN", db(url, dbPassword, "SELECT condition FROM stock_unit WHERE id='" + kept + "'"));
                assertEquals("FirstAdmin", db(url, dbPassword, "SELECT created_by FROM stock_unit WHERE id='" + kept + "'"));
                note("DB row " + kept + ": label=Laptop condition=OPEN retired_on=NULL created_by=FirstAdmin");
                assertEquals("Laptop", success(send(web, "GET", ROOT + "/" + kept, token, null), 200).path("label").asText());
                String hundred = "L".repeat(100);
                success(send(web, "POST", ROOT, token, "{\"label\":\"" + hundred + "\",\"condition\":\"CLOSED\",\"retiredOn\":\"2026-01-31\"}"), 201);
                long rows = count(url, dbPassword, "SELECT count(*) FROM stock_unit");
                assertEquals(2, rows);

                section("CONTRACT-INVALID REQUESTS (each must be 400 keyed by the field, nothing persisted)");
                Map<String, String> invalidCreate = new java.util.LinkedHashMap<>();
                invalidCreate.put("{\"condition\":\"OPEN\",\"retiredOn\":null}", "label");
                invalidCreate.put("{\"label\":null,\"condition\":\"OPEN\",\"retiredOn\":null}", "label");
                invalidCreate.put("{\"label\":42,\"condition\":\"OPEN\",\"retiredOn\":null}", "label");
                invalidCreate.put("{\"label\":\"" + "L".repeat(101) + "\",\"condition\":\"OPEN\",\"retiredOn\":null}", "label");
                invalidCreate.put("{\"label\":\"a\",\"label\":\"b\",\"condition\":\"OPEN\",\"retiredOn\":null}", "label");
                invalidCreate.put("{\"label\":\"Laptop\",\"condition\":1,\"retiredOn\":null}", "condition");
                invalidCreate.put("{\"label\":\"Laptop\",\"condition\":\"BROKEN\",\"retiredOn\":null}", "condition");
                invalidCreate.put("{\"label\":\"Laptop\",\"condition\":\"open\",\"retiredOn\":null}", "condition");
                invalidCreate.put("{\"label\":\"Laptop\",\"condition\":\"OPEN\"}", "retiredOn");
                invalidCreate.put("{\"label\":\"Laptop\",\"condition\":\"OPEN\",\"retiredOn\":\"yesterday\"}", "retiredOn");
                invalidCreate.put("{\"label\":\"Laptop\",\"condition\":\"OPEN\",\"retiredOn\":20260131}", "retiredOn");
                for (var item : invalidCreate.entrySet()) expectError(send(web, "POST", ROOT, token, item.getKey()), 400, item.getValue());
                expectError(send(web, "PUT", ROOT + "/" + kept, token, "{\"label\":\"Changed\",\"condition\":\"CLOSED\"}"), 400, "retiredOn");
                expectError(send(web, "PUT", ROOT + "/" + kept, token, "{\"label\":true,\"condition\":\"CLOSED\",\"retiredOn\":null}"), 400, "label");
                expectError(send(web, "POST", ROOT, token, "{bad-json"), 400, "_global");
                assertEquals(rows, count(url, dbPassword, "SELECT count(*) FROM stock_unit"), "Invalid requests must not persist");
                assertEquals("Laptop", db(url, dbPassword, "SELECT label FROM stock_unit WHERE id='" + kept + "'"));
                note("DB after invalid requests: rows=" + rows + " (unchanged), kept row label=Laptop (unchanged)");

                section("UPDATE (valid)");
                JsonNode updated = success(send(web, "PUT", ROOT + "/" + kept, token,
                        "{\"label\":\"Laptop-2\",\"condition\":\"CLOSED\",\"retiredOn\":\"2026-10-20\"}"), 200);
                assertEquals("CLOSED", updated.path("condition").asText());
                assertEquals("2026-10-20", db(url, dbPassword, "SELECT retired_on FROM stock_unit WHERE id='" + kept + "'"));
                assertEquals("FirstAdmin", db(url, dbPassword, "SELECT last_modified_by FROM stock_unit WHERE id='" + kept + "'"));
                note("DB row " + kept + ": label=Laptop-2 condition=CLOSED retired_on=2026-10-20 last_modified_by=FirstAdmin");

                section("FILTERING AND PAGINATION");
                for (int i = 0; i < 23; i++) success(send(web, "POST", ROOT, token, "{\"label\":\"group-" + i + "\",\"condition\":\"OPEN\",\"retiredOn\":null}"), 201);
                JsonNode open = success(send(web, "GET", ROOT + "?condition=OPEN", token, null), 200);
                assertEquals(23, open.path("totalElements").asLong());
                assertEquals(20, open.path("data").size());
                assertEquals(0, open.path("number").asInt());
                assertEquals(2, open.path("totalPages").asInt());
                assertAscending(open);
                JsonNode second = success(send(web, "GET", ROOT + "?condition=OPEN&page=1&size=20", token, null), 200);
                assertEquals(3, second.path("data").size());
                JsonNode sized = success(send(web, "GET", ROOT + "?condition=OPEN&page=2&size=7", token, null), 200);
                assertEquals(7, sized.path("data").size());
                assertEquals(4, sized.path("totalPages").asInt());
                assertEquals(0, success(send(web, "GET", ROOT + "?condition=OPEN&page=9", token, null), 200).path("data").size());
                assertEquals(2, success(send(web, "GET", ROOT + "?condition=CLOSED", token, null), 200).path("totalElements").asLong());
                JsonNode exact = success(send(web, "GET", ROOT + "?condition=CLOSED&label=Laptop-2", token, null), 200);
                assertEquals(1, exact.path("totalElements").asLong());
                assertEquals(kept.toString(), exact.path("data").get(0).path("id").asText());
                long partial = success(send(web, "GET", ROOT + "?condition=OPEN&label=group-1", token, null), 200).path("totalElements").asLong();
                long wildcard = success(send(web, "GET", ROOT + "?condition=OPEN&label=%25", token, null), 200).path("totalElements").asLong();
                note("OBSERVED (contract does not define label matching): label=group-1 matched " + partial
                        + " OPEN rows (substring), label=% matched " + wildcard + " OPEN rows (unescaped LIKE wildcard)");
                for (String query : List.of("page=-1", "size=0", "size=101", "page=oops", "size=oops"))
                    expectError(send(web, "GET", ROOT + "?condition=OPEN&" + query, token, null), 400, query.substring(0, query.indexOf('=')));
                expectError(send(web, "GET", ROOT, token, null), 400, "condition");
                expectError(send(web, "GET", ROOT + "?condition=BROKEN", token, null), 400, "condition");

                section("DELETE");
                UUID doomed = UUID.fromString(open.path("data").get(0).path("id").asText());
                assertTrue(success(send(web, "DELETE", ROOT + "/" + doomed, token, null), 200).isNull());
                assertEquals(0, count(url, dbPassword, "SELECT count(*) FROM stock_unit WHERE id='" + doomed + "'"));
                expectError(send(web, "GET", ROOT + "/" + doomed, token, null), 404, "_global");
                expectError(send(web, "PUT", ROOT + "/" + doomed, token, "{\"label\":\"x\",\"condition\":\"OPEN\",\"retiredOn\":null}"), 404, "_global");
                expectError(send(web, "DELETE", ROOT + "/" + doomed, token, null), 404, "_global");
                expectError(send(web, "GET", ROOT + "/not-a-uuid", token, null), 400, "_global");
                note("DB rows after delete: " + count(url, dbPassword, "SELECT count(*) FROM stock_unit"));
            } finally {
                StandaloneBackendTestSupport.stop(app);
                processes.preserveEvidence(appLog, dbPassword, jwt);
            }

            section("RESTART (persistence survives a new process)");
            int web2 = StandaloneBackendTestSupport.freePort();
            Path restartLog = scratch.resolve("e2e-application-restart.log");
            Process restarted = processes.startJar(jar, url, "generated_test", dbPassword, jwt, web2, restartLog);
            try {
                awaitHttp(restarted, web2, restartLog);
                String again = login(web2, adminPassword);
                JsonNode retained = success(send(web2, "GET", ROOT + "/" + kept, again, null), 200);
                assertEquals("Laptop-2", retained.path("label").asText());
                assertEquals("2026-10-20", retained.path("retiredOn").asText());
            } finally {
                StandaloneBackendTestSupport.stop(restarted);
                processes.preserveEvidence(restartLog, dbPassword, jwt);
            }
            note("END-TO-END RESULT: PASS");
        } finally {
            processes.cleanupOwned("e2e", ownership, envFile, dbPassword);
            Files.writeString(evidence.resolve("e2e-http-transcript.txt"), redact(String.join("\n", transcript)) + "\n");
        }
    }

    private Path buildPackage(StandaloneBackendTestSupport processes, Path evidence) throws Exception {
        var fixture = new CompleteOutputTestFixture();
        var historical = FinalGeneratedOutput.load();
        assertEquals(FinalGeneratedOutput.ZIP_SHA256, historical.zipSha256());
        var descriptor = fixture.preflight().describe(fixture.version(fixture.pagedSnapshot()), fixture.pagedSpecification(), OutputMode.BACKEND);
        var registry = new PackagedContractRegistry("/generation/stock-unit-contracts.yaml");
        var plan = GenerationFilePlan.forDescriptor(descriptor);
        var runtime = new CompleteBackendRuntimeRenderer(registry);
        var input = BackendModelInput.from(descriptor, plan, new CompleteBackendDefinitionRenderer().render(descriptor, plan), runtime);
        String savedModelResponse = historical.text(FinalGeneratedOutput.SERVICE_IMPL_PATH);
        var response = JSON.createObjectNode();
        response.putArray("files").addObject().put("path", input.writablePath()).put("content", savedModelResponse);
        var accepted = new BackendModelOutputValidator((chosen, bytes) -> true).validate(input, JSON.writeValueAsBytes(response));
        var files = new CompleteBackendPackageAssembler(runtime).assemble(input, accepted, SAVED_RESPONSE).files();
        Path extracted = Files.createDirectory(scratch.resolve("newly-assembled-package"));
        for (var entry : files.entrySet()) {
            Path destination = extracted.resolve(entry.getKey()).normalize();
            assertTrue(destination.startsWith(extracted));
            Files.createDirectories(destination.getParent());
            Files.write(destination, entry.getValue());
        }
        // Keep the exact newly assembled package as evidence (it contains no secrets).
        Path kept = evidence.resolve("newly-assembled-package");
        for (var entry : files.entrySet()) {
            Path destination = kept.resolve(entry.getKey()).normalize();
            Files.createDirectories(destination.getParent());
            Files.write(destination, entry.getValue());
        }
        note("Package: NEWLY ASSEMBLED by the current generator (stock-unit-api 3.0.0, " + files.size() + " files); "
                + "model-owned StockUnitServiceImpl = saved real model response from historical run 6b1f398c, byte-identical: "
                + savedModelResponse.equals(new String(files.get(FinalGeneratedOutput.SERVICE_IMPL_PATH), StandardCharsets.UTF_8))
                + "; historical ZIP unchanged (SHA-256 " + historical.zipSha256() + ")");
        Path jar = processes.buildJar("e2e", extracted);
        note("Maven package (offline): " + jar.getFileName());
        return jar;
    }

    private String login(int port, String password) throws Exception {
        var response = send(port, "POST", "/api/auth/login", null, JSON.writeValueAsString(Map.of("username", "FirstAdmin", "password", password)));
        assertEquals(200, response.statusCode());
        String token = JSON.readTree(response.body()).path("body").path("accessToken").asText();
        assertFalse(token.isBlank());
        secrets.put(token, "[access-token]");
        return token;
    }

    private JsonNode success(HttpResponse<String> response, int status) throws Exception {
        assertEquals(status, response.statusCode(), response.body());
        JsonNode envelope = JSON.readTree(response.body());
        assertEquals(Set.of("status", "clientMessage", "statusCode", "body", "errors"), JSON.readValue(response.body(), Map.class).keySet());
        assertEquals(status, envelope.path("statusCode").asInt());
        assertTrue(envelope.path("errors").isNull());
        return envelope.path("body");
    }

    private void expectError(HttpResponse<String> response, int status, String key) throws Exception {
        assertEquals(status, response.statusCode(), response.body());
        JsonNode envelope = JSON.readTree(response.body());
        assertEquals(Set.of("status", "clientMessage", "statusCode", "body", "errors"), JSON.readValue(response.body(), Map.class).keySet());
        assertEquals(status, envelope.path("statusCode").asInt());
        assertTrue(envelope.path("body").isNull());
        assertTrue(envelope.path("errors").has(key), "expected error key " + key + " in " + response.body());
    }

    private HttpResponse<String> send(int port, String method, String path, String bearer, String body) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).timeout(Duration.ofSeconds(10));
        if (bearer != null) request.header("Authorization", "Bearer " + bearer);
        if (body != null) request.header("Content-Type", "application/json");
        request.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        var response = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build()
                .send(request.build(), HttpResponse.BodyHandlers.ofString());
        String shownBody = body == null ? "" : " " + (body.length() > 140 ? body.substring(0, 140) + "...(" + body.length() + " chars)" : body);
        String redacted = redact(response.body());
        String shownResponse = redacted.length() > 260 ? redacted.substring(0, 260) + "..." : redacted;
        transcript.add(method + " " + path + (bearer == null ? "" : " [bearer]") + shownBody + "\n    -> " + response.statusCode() + " " + shownResponse);
        return response;
    }

    private void assertAscending(JsonNode page) {
        List<String> ids = new ArrayList<>();
        page.path("data").forEach(item -> ids.add(item.path("id").asText()));
        // PostgreSQL orders uuid as unsigned bytes, which equals lowercase-hex string order.
        // (java.util.UUID.compareTo is signed and would disagree for ids starting 8-f.)
        assertEquals(ids.stream().sorted().toList(), ids, "Contract orders the list by id ASC");
    }

    private void section(String title) { transcript.add("\n### " + title); }

    private void note(String text) { transcript.add("# " + text); }

    private String redact(String text) {
        for (var secret : secrets.entrySet()) text = text.replace(secret.getKey(), secret.getValue());
        return text.replaceAll("\"accessToken\":\"[^\"\\n]+\"", "\"accessToken\":\"[access-token]\"");
    }

    private void awaitPostgres(StandaloneBackendTestSupport processes, String container) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(60).toNanos();
        while (System.nanoTime() < deadline) {
            if (processes.docker(List.of("exec", container, "pg_isready", "-U", "generated_test", "-d", "generated_app")).exit() == 0) return;
            Thread.sleep(500);
        }
        fail("Disposable PostgreSQL did not become ready");
    }

    private void awaitHttp(Process app, int port, Path log) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(100).toNanos();
        while (System.nanoTime() < deadline && app.isAlive()) {
            try {
                var probe = HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/v3/api-docs"))
                        .timeout(Duration.ofSeconds(2)).build(), HttpResponse.BodyHandlers.discarding());
                if (probe.statusCode() == 200) return;
            } catch (java.io.IOException ignored) { }
            Thread.sleep(500);
        }
        fail("Generated JAR did not reach HTTP readiness: " + StandaloneBackendTestSupport.safeLines(log));
    }

    private static long count(String url, String password, String sql) throws Exception {
        try (var connection = DriverManager.getConnection(url, "generated_test", password);
             var statement = connection.createStatement(); var rows = statement.executeQuery(sql)) {
            assertTrue(rows.next()); return rows.getLong(1);
        }
    }

    private static String db(String url, String password, String sql) throws Exception {
        try (var connection = DriverManager.getConnection(url, "generated_test", password);
             var statement = connection.createStatement(); var rows = statement.executeQuery(sql)) {
            assertTrue(rows.next()); return rows.getString(1);
        }
    }

    private static String randomSecret() {
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        return Base64.getEncoder().encodeToString(bytes);
    }
}
