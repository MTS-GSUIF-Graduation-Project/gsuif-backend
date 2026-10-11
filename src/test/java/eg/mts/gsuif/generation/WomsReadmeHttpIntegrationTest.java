package eg.mts.gsuif.generation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.zip.ZipFile;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/** Executes the README's eleven HTTP hops with real services and a real consumer build. */
@SpringBootTest(properties = "gsuif.generation.export-root=target/woms-http-exports")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class WomsReadmeHttpIntegrationTest {
    @Autowired MockMvc mvc;
    private final ObjectMapper mapper = new ObjectMapper();

    @Test void elevenHopsProduceOneTraceableSavedRun() throws Exception {
        String token = body(request(post("/api/auth/login"), null,
                "{\"username\":\"admin\",\"password\":\"password123\"}"))
                .path("token").asText();
        assertFalse(token.isBlank());
        String auth = "Bearer " + token;

        String project = body(request(post("/api/v1/projects"), auth,
                "{\"name\":\"WOMS " + UUID.randomUUID() + "\",\"description\":\"SCRUM-31\"}"))
                .path("id").asText();
        String page = body(request(post("/api/v1/projects/" + project + "/pages"), auth,
                "{\"name\":\"Asset tickets\",\"route\":\"/asset-tickets\"}"))
                .path("id").asText();
        String metadata = Files.readString(Path.of("demo/woms-sample-metadata.json"));
        String version = body(request(post("/api/v1/pages/" + page + "/metadata"), auth, metadata))
                .path("id").asText();
        JsonNode current = body(request(get("/api/v1/pages/" + page + "/metadata/current"), auth, null));
        assertEquals(version, current.path("id").asText());
        assertEquals(5, current.path("snapshot").path("apiBindings").size());

        JsonNode generationRequest = mapper.readTree(Files.readString(Path.of("demo/woms-generation-request.json")));
        ((com.fasterxml.jackson.databind.node.ObjectNode) generationRequest).put("pageId", page);
        JsonNode generated = body(request(post("/api/v1/generation/generate"), auth,
                mapper.writeValueAsString(generationRequest)));
        String run = generated.path("runId").asText();
        assertEquals("SUCCESS", generated.path("status").asText());
        assertEquals(version, generated.path("metadataVersionId").asText());

        JsonNode details = body(request(get("/api/v1/generation/runs/" + run), auth, null));
        assertEquals("SUCCESS", details.path("status").asText(), details.path("compileOutput").asText());
        assertEquals(0, details.path("compileExitCode").asInt(-1));
        assertEquals(0, details.path("testExitCode").asInt(-1));
        assertEquals(version, details.path("metadataVersionId").asText());
        assertTrue(details.path("artifacts").size() > 0);
        String file = details.path("artifacts").get(0).path("relativePath").asText();
        JsonNode history = body(request(get("/api/v1/generation/artifacts/history").param("path", file), auth, null));
        assertTrue(java.util.stream.StreamSupport.stream(history.spliterator(), false).anyMatch(trace ->
                run.equals(trace.path("runId").asText()) && version.equals(trace.path("metadataVersionId").asText())));
        byte[] downloaded = mvc.perform(get("/api/v1/generation/runs/" + run + "/artifacts/download")
                .header("Authorization", auth).param("path", file)).andReturn().getResponse().getContentAsByteArray();
        assertTrue(downloaded.length > 0);

        JsonNode folderConfig = body(request(put("/api/v1/generation/projects/" + project + "/destinations/local"),
                auth, "{\"type\":\"LOCAL_FOLDER\",\"path\":\"delivery/folder\"}"));
        assertEquals("delivery/folder", folderConfig.path("path").asText());
        Path folder = Path.of(URI.create(body(request(post("/api/v1/generation/runs/" + run + "/exports/local"),
                auth, null)).path("location").asText()));
        assertArrayEquals(downloaded, Files.readAllBytes(folder.resolve(file)));

        JsonNode zipConfig = body(request(put("/api/v1/generation/projects/" + project + "/destinations/zip"),
                auth, "{\"type\":\"ZIP\",\"path\":\"delivery/archive\"}"));
        assertEquals("delivery/archive", zipConfig.path("path").asText());
        Path zip = Path.of(URI.create(body(request(post("/api/v1/generation/runs/" + run + "/exports/zip"),
                auth, null)).path("location").asText()));
        try (var archive = new ZipFile(zip.toFile())) {
            assertArrayEquals(downloaded, archive.getInputStream(archive.getEntry(file)).readAllBytes());
        }
    }

    private JsonNode request(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder builder,
            String auth, String json) throws Exception {
        if (auth != null) builder.header("Authorization", auth);
        if (json != null) builder.contentType(MediaType.APPLICATION_JSON).content(json);
        var response = mvc.perform(builder).andReturn().getResponse();
        assertTrue(response.getStatus() >= 200 && response.getStatus() < 300,
                response.getStatus() + ": " + response.getContentAsString());
        return mapper.readTree(response.getContentAsString());
    }

    private static JsonNode body(JsonNode response) { return response.path("body"); }
}
