package eg.mts.gsuif.generation;

import eg.mts.gsuif.entity.GenerationRun;
import eg.mts.gsuif.entity.GenerationRunStatus;
import eg.mts.gsuif.entity.GsuifUser;
import eg.mts.gsuif.repository.GeneratedArtifactRepository;
import eg.mts.gsuif.repository.GenerationRunRepository;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class GenerationFailureReferenceTest {
    private final GenerationRunRepository repository = mock(GenerationRunRepository.class);
    private final GenerationRunService service = new GenerationRunService(repository,
            mock(GeneratedArtifactRepository.class), mock(ConsumerBuildValidator.class),
            mock(GenerationArtifactStore.class));

    @Test void onlyOwnedPersistedFailedRunWithBoundedValuesCanSupplyHeaders() {
        UUID id = UUID.randomUUID();
        GenerationRun run = mock(GenerationRun.class);
        GsuifUser owner = mock(GsuifUser.class);
        when(repository.findById(id)).thenReturn(Optional.of(run));
        when(run.getId()).thenReturn(id);
        when(run.getTriggeringUser()).thenReturn(owner);
        when(owner.getUsername()).thenReturn("owner");
        when(run.getStatus()).thenReturn(GenerationRunStatus.FAILED);
        when(run.getFailureStage()).thenReturn("CONFIG");
        when(run.getFailureCode()).thenReturn("CONFIG_MISSING");

        var found = service.persistedFailure(id, "owner").orElseThrow();
        assertEquals(id, found.runId());
        assertEquals(GenerationFailureStage.CONFIG, found.stage());
        assertEquals(GenerationFailureCode.CONFIG_MISSING, found.code());
        assertTrue(service.persistedFailure(id, "other").isEmpty());
        assertTrue(service.persistedFailure(id, null).isEmpty());
        assertTrue(service.persistedFailure(null, "owner").isEmpty());

        when(run.getStatus()).thenReturn(GenerationRunStatus.IN_PROGRESS);
        assertTrue(service.persistedFailure(id, "owner").isEmpty());
        when(run.getStatus()).thenReturn(GenerationRunStatus.SUCCESS);
        assertTrue(service.persistedFailure(id, "owner").isEmpty());
        when(run.getStatus()).thenReturn(GenerationRunStatus.FAILED);
        when(run.getFailureCode()).thenReturn("provider response or credential");
        assertTrue(service.persistedFailure(id, "owner").isEmpty());
        when(repository.findById(id)).thenReturn(Optional.empty());
        assertTrue(service.persistedFailure(id, "owner").isEmpty());
    }
}
