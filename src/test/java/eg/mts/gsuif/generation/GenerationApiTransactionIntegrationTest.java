package eg.mts.gsuif.generation;

import eg.mts.gsuif.dto.GenerationApiDtos;
import eg.mts.gsuif.entity.GenerationRun;
import eg.mts.gsuif.entity.GenerationRunStatus;
import eg.mts.gsuif.entity.GsuifPage;
import eg.mts.gsuif.entity.GsuifProject;
import eg.mts.gsuif.entity.GsuifUser;
import eg.mts.gsuif.entity.MetadataVersion;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@SpringBootTest
@ActiveProfiles("test")
@WithMockUser(username = "esraa")
class GenerationApiTransactionIntegrationTest {
    @Autowired EntityManager entities;
    @Autowired PlatformTransactionManager transactionManager;
    @Autowired GenerationApiService service;
    @MockitoBean GenerationWorkflow workflow;

    @Test void generationReadsSelectedVersionAndProjectWithOpenInViewDisabled() {
        String username = "api-" + UUID.randomUUID();
        TransactionTemplate transactions = new TransactionTemplate(transactionManager);
        UUID[] ids = transactions.execute(ignored -> {
            GsuifProject project = new GsuifProject();
            project.setName("Generation API " + UUID.randomUUID());
            entities.persist(project);
            GsuifPage page = new GsuifPage();
            page.setProject(project);
            page.setName("Page");
            entities.persist(page);
            MetadataVersion version = new MetadataVersion();
            version.setPage(page);
            version.setVersion(1);
            version.setSchemaVersion("1.0.0");
            version.setSnapshot("{\"components\":[],\"apiBindings\":[]}");
            entities.persist(version);
            page.setCurrentMetadataVersionId(version.getId());
            GsuifUser user = new GsuifUser();
            user.setUsername(username);
            user.setPasswordHash("hash");
            entities.persist(user);
            entities.flush();
            return new UUID[]{page.getId(), version.getId()};
        });
        UUID runId = UUID.randomUUID();
        GenerationRun run = mock(GenerationRun.class);
        when(run.getId()).thenReturn(runId);
        when(run.getStatus()).thenReturn(GenerationRunStatus.SUCCESS);
        when(workflow.generateAndRecord(any(), any(), any(), any(), any(), any(), any()))
                .thenAnswer(invocation -> {
                    MetadataVersion selected = invocation.getArgument(1);
                    assertEquals(ids[1], selected.getId());
                    assertEquals("Page", selected.getPage().getName());
                    assertEquals(ids[0], selected.getPage().getId());
                    // This lazy relationship must be readable in the service transaction.
                    org.junit.jupiter.api.Assertions.assertNotNull(selected.getPage().getProject().getName());
                    return new GenerationWorkflow.CompletedAttempt(new GenerationResult(List.of(), List.of()), run);
                });
        var response = service.generate(new GenerationApiDtos.GenerateRequest(ids[0], "ENTITY",
                new GenerationSpecification("1.0.0", "com.example.fixture", null, null, null, null)), username);
        assertEquals(runId, response.runId());
        assertEquals(ids[1], response.metadataVersionId());
    }
}
