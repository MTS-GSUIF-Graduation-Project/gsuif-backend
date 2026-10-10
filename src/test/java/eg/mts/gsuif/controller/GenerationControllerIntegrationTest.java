package eg.mts.gsuif.controller;

import eg.mts.gsuif.dto.GenerationApiDtos;
import eg.mts.gsuif.exception.ResourceNotFoundException;
import eg.mts.gsuif.generation.GenerationApiService;
import eg.mts.gsuif.generation.GenerationExportService;
import eg.mts.gsuif.generation.GenerationValidationException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@WithMockUser(username = "esraa.abdelrazek")
class GenerationControllerIntegrationTest {
    @Autowired MockMvc mvc;
    @MockitoBean GenerationApiService service;
    @MockitoBean GenerationExportService exports;

    @Test void generateReturnsCreatedEnvelopeAndArtifactText() throws Exception {
        UUID page = UUID.randomUUID(), run = UUID.randomUUID(), version = UUID.randomUUID();
        when(service.generate(any(), eq("esraa.abdelrazek"))).thenReturn(new GenerationApiDtos.CreatedRun(
                run, "SUCCESS", version, List.of(new GenerationApiDtos.TextArtifact(
                "src/main/java/example/Thing.java", "class Thing {}", "abc", "1.0.0", "1.0.0")),
                new GenerationApiDtos.ValidationScope(List.of("JAVA_COMPILE", "JAVA_CONSUMER_TESTS"), List.of("ANGULAR"))));
        mvc.perform(post("/api/v1/generation/generate").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"pageId\":\"" + page + "\",\"generationType\":\"ENTITY\",\"specification\":{\"specVersion\":\"1.0.0\"}}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("OK"))
                .andExpect(jsonPath("$.statusCode").value(201))
                .andExpect(jsonPath("$.body.runId").value(run.toString()))
                .andExpect(jsonPath("$.body.status").value("SUCCESS"))
                .andExpect(jsonPath("$.body.artifacts[0].content").value("class Thing {}"))
                .andExpect(jsonPath("$.body.artifacts[0].sha256").value("abc"))
                .andExpect(jsonPath("$.body.validationScope.passedTargets[0]").value("JAVA_COMPILE"))
                .andExpect(jsonPath("$.body.validationScope.notValidatedTargets[0]").value("ANGULAR"));
    }

    @Test void missingSpecificationIsRejectedBeforeService() throws Exception {
        mvc.perform(post("/api/v1/generation/generate").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"pageId\":\"" + UUID.randomUUID() + "\",\"generationType\":\"ENTITY\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.specification").exists());
        verifyNoInteractions(service);
    }

    @Test void invalidTypeUsesStandardErrorEnvelope() throws Exception {
        when(service.generate(any(), eq("esraa.abdelrazek")))
                .thenThrow(new GenerationValidationException(List.of("generationType: unsupported value")));
        mvc.perform(post("/api/v1/generation/generate").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"pageId\":\"" + UUID.randomUUID() + "\",\"generationType\":\"OTHER\",\"specification\":{\"specVersion\":\"1.0.0\"}}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.statusCode").value(400))
                .andExpect(jsonPath("$.errors._global").value("generationType: unsupported value"));
    }

    @Test void providersAndPersistedRunDetailsAreWrapped() throws Exception {
        UUID id = UUID.randomUUID(), version = UUID.randomUUID(), artifact = UUID.randomUUID();
        when(service.providers()).thenReturn(List.of(new GenerationApiDtos.Provider("TemplateOnlyProvider", true)));
        when(service.run(id)).thenReturn(new GenerationApiDtos.RunDetails(id, "BUILD_FAILED",
                Instant.parse("2026-10-01T00:00:00Z"), Instant.parse("2026-10-01T00:00:01Z"), version,
                List.of(new GenerationApiDtos.LinkedArtifact(artifact, "Thing.java", "java-source", "Thing.java", "1.0.0")),
                1, "compile error", null, null, new GenerationApiDtos.ValidationScope(List.of(), List.of("ANGULAR"))));
        mvc.perform(get("/api/v1/generation/providers"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.body[0].name").value("TemplateOnlyProvider"))
                .andExpect(jsonPath("$.body[0].available").value(true));
        mvc.perform(get("/api/v1/generation/runs/{id}", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.body.status").value("BUILD_FAILED"))
                .andExpect(jsonPath("$.body.metadataVersionId").value(version.toString()))
                .andExpect(jsonPath("$.body.artifacts[0].id").value(artifact.toString()))
                .andExpect(jsonPath("$.body.compileExitCode").value(1))
                .andExpect(jsonPath("$.body.validationScope.passedTargets").isEmpty())
                .andExpect(jsonPath("$.body.validationScope.notValidatedTargets[0]").value("ANGULAR"));
    }

    @Test void unknownRunReturns404Envelope() throws Exception {
        UUID id = UUID.randomUUID();
        when(service.run(id)).thenThrow(new ResourceNotFoundException("Generation run not found"));
        mvc.perform(get("/api/v1/generation/runs/{id}", id))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.statusCode").value(404));
    }

    @Test void omittedDestinationTypeReturnsValidationEnvelope() throws Exception {
        UUID project = UUID.randomUUID();
        when(exports.configure(eq(project), eq("missing-type"), any(GenerationExportService.DestinationConfig.class)))
                .thenThrow(new GenerationValidationException(List.of("destination.type: unsupported value")));
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put(
                        "/api/v1/generation/projects/{projectId}/destinations/missing-type", project)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"path\":\"safe/path\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.statusCode").value(400))
                .andExpect(jsonPath("$.errors._global").value("destination.type: unsupported value"));
    }

    @Test void savedArtifactDownloadsAsBytesAndExportReturnsLocation() throws Exception {
        UUID run = UUID.randomUUID(), project = UUID.randomUUID();
        when(service.download(run, "src/main/java/Thing.java"))
                .thenReturn(new GenerationApiService.DownloadedArtifact("Thing.java", "class Thing {}".getBytes()));
        mvc.perform(get("/api/v1/generation/runs/{id}/artifacts/download", run)
                        .param("path", "src/main/java/Thing.java"))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content().string("class Thing {}"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header()
                        .string("Content-Disposition", "attachment; filename=\"Thing.java\""));

        when(exports.configure(eq(project), eq("local"), any()))
                .thenReturn(new GenerationExportService.DestinationConfig("LOCAL_FOLDER", "delivery/folder"));
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put(
                        "/api/v1/generation/projects/{projectId}/destinations/local", project)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"type\":\"LOCAL_FOLDER\",\"path\":\"delivery/folder\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.body.type").value("LOCAL_FOLDER"))
                .andExpect(jsonPath("$.body.path").value("delivery/folder"));

        when(exports.export(run, "local")).thenReturn(new GenerationExportService.ExportLocation(
                run, project, "local", "file:/example/run"));
        mvc.perform(post("/api/v1/generation/runs/{id}/exports/local", run))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.body.runId").value(run.toString()))
                .andExpect(jsonPath("$.body.location").value("file:/example/run"));
    }
}
