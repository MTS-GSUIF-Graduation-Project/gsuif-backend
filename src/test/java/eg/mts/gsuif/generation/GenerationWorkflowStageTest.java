package eg.mts.gsuif.generation;

import eg.mts.gsuif.entity.GenerationRun;
import eg.mts.gsuif.entity.GsuifUser;
import eg.mts.gsuif.entity.MetadataVersion;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class GenerationWorkflowStageTest {
    private final MetadataVersion metadata = mock(MetadataVersion.class);
    private final GsuifUser user = mock(GsuifUser.class);
    private final GenerationEngine engine = mock(GenerationEngine.class);
    private final GenerationRunService runs = mock(GenerationRunService.class);

    GenerationWorkflowStageTest() {
        when(metadata.getId()).thenReturn(new UUID(0, 41));
        when(metadata.getSchemaVersion()).thenReturn("1.0.0");
        when(metadata.getSnapshot()).thenReturn("{\"components\":[],\"apiBindings\":[]}");
        when(user.getId()).thenReturn(new UUID(0, 42));
    }

    @Test void explicitStagesPersistOnlySafeStageAndCode() {
        for (GenerationFailureStage stage : new GenerationFailureStage[] {
                GenerationFailureStage.CONFIG, GenerationFailureStage.AI,
                GenerationFailureStage.ASSEMBLY }) {
            GenerationFailureCode code = switch (stage) {
                case CONFIG -> GenerationFailureCode.CONFIG_MISSING;
                case AI -> GenerationFailureCode.MODEL_UNAVAILABLE;
                case ASSEMBLY -> GenerationFailureCode.INVALID_SOURCE_MAP;
                default -> throw new AssertionError(stage);
            };
            reset(engine, runs);
            when(runs.findByAttemptId(any())).thenReturn(Optional.empty());
            when(engine.generate(any(), any(), anySet(), anyString()))
                    .thenThrow(new GenerationStageException(stage, code));
            var error = assertThrows(GenerationStageException.class, this::generate);
            assertEquals(stage, error.stage());
            verify(runs).recordFailure(any(), anyString(), eq(stage), eq(code));
            verify(runs, never()).completeReservedAttempt(any(), any(), any(), any(), anyMap(), anyString());
        }
    }

    @Test void unexpectedAssemblyAndFinalValidationErrorsAreRedacted() {
        when(runs.findByAttemptId(any())).thenReturn(Optional.empty());
        when(engine.generate(any(), any(), anySet(), anyString()))
                .thenThrow(new IllegalStateException("sensitive provider response"));
        var assembly = assertThrows(GenerationStageException.class, this::generate);
        assertEquals(GenerationFailureStage.ASSEMBLY, assembly.stage());
        assertFalse(assembly.getMessage().contains("sensitive"));
        verify(runs).recordFailure(any(), anyString(), eq(GenerationFailureStage.ASSEMBLY), eq(GenerationFailureCode.UNEXPECTED_FAILURE));

        reset(engine, runs);
        when(runs.findByAttemptId(any())).thenReturn(Optional.empty());
        when(engine.generate(any(), any(), anySet(), anyString()))
                .thenReturn(new GenerationResult(java.util.List.of(), java.util.List.of()));
        when(runs.completeReservedAttempt(any(), any(), any(), any(), anyMap(), anyString()))
                .thenThrow(new IllegalStateException("sensitive validation details"));
        var finalError = assertThrows(GenerationStageException.class, this::generate);
        assertEquals(GenerationFailureStage.FINAL_VALIDATION, finalError.stage());
        assertFalse(finalError.getMessage().contains("sensitive"));
        verify(runs).recordFailure(any(), anyString(), eq(GenerationFailureStage.FINAL_VALIDATION), eq(GenerationFailureCode.RESULT_VALIDATION_FAILED));
    }

    @Test void unavailablePackagedInputHasStableFingerprintAndIsRecognizedBeforeProviderWork() {
        Map<String, byte[]> overrides = new java.util.HashMap<>();
        overrides.put("components.yaml", null);
        var prepared = GenerationAttemptFingerprint.prepare(metadata, user, null,
                Set.of(GenerationContext.Target.ENTITY), "spring-angular", Map.of(), overrides);
        assertTrue(prepared.resourceUnavailable());
        assertEquals(prepared.value(), GenerationAttemptFingerprint.prepare(metadata, user, null,
                Set.of(GenerationContext.Target.ENTITY), "spring-angular", Map.of(), overrides).value());
        assertNotEquals(prepared.value(), GenerationAttemptFingerprint.request(metadata, user, null,
                Set.of(GenerationContext.Target.ENTITY), "spring-angular", Map.of()));
    }

    @Test void internalPreparationFailureIsReservedAndRecordedWithoutCallingProvider() {
        when(runs.findByAttemptId(any())).thenReturn(Optional.empty());
        var failed = mock(GenerationRun.class);
        when(failed.getId()).thenReturn(new UUID(0, 43));
        when(runs.recordFailure(any(), anyString(), eq(GenerationFailureStage.CONFIG),
                eq(GenerationFailureCode.CONFIG_MISSING))).thenReturn(failed);
        var error = assertThrows(GenerationStageException.class, () ->
                new GenerationWorkflow(engine, runs).recordPreparationFailure(UUID.randomUUID(), metadata, user,
                        null, Set.of(GenerationContext.Target.ENTITY), "spring-angular"));
        assertEquals(failed.getId(), error.runId());
        verify(runs).reserveAttempt(any(), same(metadata), same(user), anyString());
        verifyNoInteractions(engine);
    }

    private void generate() {
        new GenerationWorkflow(engine, runs).generateAndRecord(UUID.randomUUID(), metadata, user, null,
                Set.of(GenerationContext.Target.ENTITY), "spring-angular", Map.of());
    }
}
