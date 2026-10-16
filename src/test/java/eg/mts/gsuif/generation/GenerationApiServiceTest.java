package eg.mts.gsuif.generation;

import eg.mts.gsuif.entity.GeneratedArtifact;
import eg.mts.gsuif.entity.GenerationRun;
import eg.mts.gsuif.entity.GenerationRunStatus;
import eg.mts.gsuif.entity.GsuifPage;
import eg.mts.gsuif.entity.GsuifUser;
import eg.mts.gsuif.entity.MetadataVersion;
import eg.mts.gsuif.exception.ResourceNotFoundException;
import eg.mts.gsuif.generation.GenerationContext.Target;
import eg.mts.gsuif.repository.GeneratedArtifactRepository;
import eg.mts.gsuif.repository.GsuifPageRepository;
import eg.mts.gsuif.repository.GsuifUserRepository;
import eg.mts.gsuif.repository.MetadataVersionRepository;
import eg.mts.gsuif.security.EntityPermissionChecker;
import eg.mts.gsuif.validator.MetadataSchemaValidator;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class GenerationApiServiceTest {
    GsuifPageRepository pages = mock(GsuifPageRepository.class);
    MetadataVersionRepository versions = mock(MetadataVersionRepository.class);
    GsuifUserRepository users = mock(GsuifUserRepository.class);
    GeneratedArtifactRepository artifacts = mock(GeneratedArtifactRepository.class);
    GenerationWorkflow workflow = mock(GenerationWorkflow.class);
    GenerationRunService runs = mock(GenerationRunService.class);
    EntityPermissionChecker permissionChecker = mock(EntityPermissionChecker.class);
    GenerationApiService service = new GenerationApiService(pages, versions, users, artifacts, workflow,
            runs, mock(MetadataSchemaValidator.class), permissionChecker);

    @Test void downloadRequiresSavedRunRegisteredSafePathAndVerifiedStorage() {
        UUID id = UUID.randomUUID();
        String path = "src/main/java/Thing.java";
        assertThrows(ResourceNotFoundException.class, () -> service.download(id, path));
        when(runs.findById(id)).thenReturn(Optional.of(mock(GenerationRun.class)));
        assertThrows(GenerationValidationException.class, () -> service.download(id, "../outside"));
        assertThrows(ResourceNotFoundException.class, () -> service.download(id, path));
        when(runs.findFile(id, path)).thenReturn(Optional.of(mock(GeneratedArtifact.class)));
        when(runs.readArtifact(id, path)).thenReturn("saved".getBytes(StandardCharsets.UTF_8));
        assertArrayEquals("saved".getBytes(StandardCharsets.UTF_8), service.download(id, path).bytes());
        when(runs.readArtifact(id, path)).thenThrow(new IllegalStateException("Stored artifact content changed"));
        assertThrows(IllegalStateException.class, () -> service.download(id, path));
    }

    @Test void artifactHistoryTracesFileThroughRunToVersion() {
        String path = "src/main/java/Thing.java";
        UUID artifactId = UUID.randomUUID(), runId = UUID.randomUUID(), versionId = UUID.randomUUID();
        var artifact = mock(GeneratedArtifact.class);
        var run = mock(GenerationRun.class);
        var version = mock(MetadataVersion.class);
        when(runs.findFileHistory(path)).thenReturn(List.of(artifact));
        when(artifact.getId()).thenReturn(artifactId);
        when(artifact.getRelativePath()).thenReturn(path);
        when(artifact.getGenerationRun()).thenReturn(run);
        when(run.getId()).thenReturn(runId);
        when(run.getMetadataVersion()).thenReturn(version);
        when(version.getId()).thenReturn(versionId);
        var trace = service.artifactHistory(path).getFirst();
        assertEquals(artifactId, trace.artifactId());
        assertEquals(runId, trace.runId());
        assertEquals(versionId, trace.metadataVersionId());
    }

    @Test void supportedTargetsMatchApprovedTeamMapping() {
        assertEquals(Set.of(Target.ENTITY), GenerationApiService.targets("ENTITY"));
        assertEquals(Set.of(Target.CONTROLLER), GenerationApiService.targets("CONTROLLER"));
        assertEquals(Set.of(Target.ANGULAR), GenerationApiService.targets("ANGULAR"));
        assertEquals(Set.of(Target.ENTITY, Target.CONTROLLER, Target.ANGULAR), GenerationApiService.targets("FULL_STACK"));
        assertThrows(GenerationValidationException.class, () -> GenerationApiService.targets("ALL"));
    }

    @Test void nullCollectionMembersAreRejectedBeforeFingerprinting() {
        var base = new GenerationSpecification("1.0.0", "com.example.fixture",
                new GenerationSpecification.EntitySpec("Gadget", "gadget",
                        java.util.Arrays.asList((GenerationSpecification.FieldSpec) null), false),
                null, Map.of(), null);
        assertTrue(assertThrows(GenerationValidationException.class,
                () -> GenerationAttemptFingerprint.validateClientShape(base)).getMessage()
                .contains("specification.entity.fields[0]"));

        var bindings = new java.util.HashMap<UUID, String>();
        bindings.put(UUID.randomUUID(), null);
        var withBindings = new GenerationSpecification(base.specVersion(), base.basePackage(),
                new GenerationSpecification.EntitySpec("Gadget", "gadget", List.of(), false),
                new GenerationSpecification.OpenApiSpec("gadget-api", "1.0.0", bindings), Map.of(), null);
        assertThrows(GenerationValidationException.class,
                () -> GenerationAttemptFingerprint.validateClientShape(withBindings));

        var roles = new java.util.HashMap<String, Set<GenerationSpecification.SupportedRole>>();
        roles.put("findGadget", null);
        var withRoles = new GenerationSpecification(base.specVersion(), base.basePackage(),
                withBindings.entity(), null, roles, null);
        assertThrows(GenerationValidationException.class,
                () -> GenerationAttemptFingerprint.validateClientShape(withRoles));

        var columns = new java.util.HashMap<UUID, List<String>>();
        columns.put(UUID.randomUUID(), java.util.Arrays.asList((String) null));
        var withColumns = new GenerationSpecification(base.specVersion(), base.basePackage(),
                withBindings.entity(), null, Map.of(), new GenerationSpecification.AngularSpec("Gadget", columns));
        assertThrows(GenerationValidationException.class,
                () -> GenerationAttemptFingerprint.validateClientShape(withColumns));

        UUID binding = UUID.randomUUID();
        var valid = new GenerationSpecification("1.0.0", "com.example.fixture",
                new GenerationSpecification.EntitySpec("Gadget", "gadget",
                        List.of(new GenerationSpecification.FieldSpec("label", "label", "String",
                                false, 100, null)), false),
                new GenerationSpecification.OpenApiSpec("gadget-api", "1.0.0",
                        Map.of(binding, "findGadget")),
                Map.of("findGadget", Set.of(GenerationSpecification.SupportedRole.ROLE_ADMIN)),
                new GenerationSpecification.AngularSpec("Gadget", Map.of(binding, List.of("label"))));
        MetadataVersion version = mock(MetadataVersion.class);
        GsuifUser user = mock(GsuifUser.class);
        when(version.getId()).thenReturn(UUID.randomUUID());
        when(version.getSchemaVersion()).thenReturn("1.2.0");
        when(version.getSnapshot()).thenReturn("{}");
        when(user.getId()).thenReturn(UUID.randomUUID());
        String fingerprint = GenerationAttemptFingerprint.request(version, user, valid,
                Set.of(Target.ANGULAR), "spring-angular", Map.of());
        assertEquals(fingerprint, GenerationAttemptFingerprint.request(version, user, valid,
                Set.of(Target.ANGULAR), "spring-angular", Map.of()));
        var changed = new GenerationSpecification(valid.specVersion(), valid.basePackage(),
                new GenerationSpecification.EntitySpec("Gadget", "gadget",
                        List.of(new GenerationSpecification.FieldSpec("label", "label", "String",
                                true, 100, null)), false),
                valid.openApi(), valid.operationRoles(), valid.angular());
        assertNotEquals(fingerprint, GenerationAttemptFingerprint.request(version, user, changed,
                Set.of(Target.ANGULAR), "spring-angular", Map.of()));
    }

    @Test void invalidTypeAndAbsentSelectionNeverStartGeneration() {
        UUID pageId = UUID.randomUUID();
        var request = new eg.mts.gsuif.dto.GenerationApiDtos.GenerateRequest(pageId, "OTHER", specification());
        assertThrows(GenerationValidationException.class, () -> service.generate(request, "esraa"));
        verifyNoInteractions(workflow, pages, versions, users);

        when(users.findByUsername("esraa")).thenReturn(Optional.of(mock(GsuifUser.class)));
        GsuifPage page = mock(GsuifPage.class);
        when(pages.findById(pageId)).thenReturn(Optional.of(page));
        assertThrows(ResourceNotFoundException.class, () -> service.generate(
                new eg.mts.gsuif.dto.GenerationApiDtos.GenerateRequest(pageId, "ENTITY", specification()), "esraa"));
        verifyNoInteractions(workflow);
    }

    @Test void generationUsesSelectedVersionAndReturnsPersistedStatusAndText() {
        UUID pageId = UUID.randomUUID(), versionId = UUID.randomUUID(), runId = UUID.randomUUID();
        GsuifPage page = mock(GsuifPage.class);
        MetadataVersion version = mock(MetadataVersion.class);
        GsuifUser user = mock(GsuifUser.class);
        GenerationRun run = mock(GenerationRun.class);
        when(users.findByUsername("esraa")).thenReturn(Optional.of(user));
        when(pages.findById(pageId)).thenReturn(Optional.of(page));
        when(page.getCurrentMetadataVersionId()).thenReturn(versionId);
        when(versions.findByIdAndPageId(versionId, pageId)).thenReturn(Optional.of(version));
        when(version.getId()).thenReturn(versionId);
        when(run.getId()).thenReturn(runId);
        when(run.getStatus()).thenReturn(GenerationRunStatus.SUCCESS);
        when(run.getCompileExitCode()).thenReturn(0);
        when(run.getTestExitCode()).thenReturn(0);
        var generated = new GenerationResult.Artifact("src/main/java/Thing.java", "class Thing {}".getBytes(StandardCharsets.UTF_8),
                "abc", "1.0.0", "1.0.0");
        var angular = new GenerationResult.Artifact("src/app/generated/thing.component.ts", "export class ThingComponent {}".getBytes(StandardCharsets.UTF_8),
                "def", "1.0.0", "1.0.0");
        when(workflow.generateAndRecord(any(UUID.class), same(version), same(user), any(),
                eq(Set.of(Target.ENTITY)), eq("spring-angular"), eq(Map.of())))
                .thenReturn(new GenerationWorkflow.CompletedAttempt(new GenerationResult(List.of(generated, angular), List.of()), run));
        var result = service.generate(new eg.mts.gsuif.dto.GenerationApiDtos.GenerateRequest(pageId, "ENTITY", specification()), "esraa");
        assertEquals(runId, result.runId());
        assertEquals("SUCCESS", result.status());
        assertEquals(versionId, result.metadataVersionId());
        assertEquals("class Thing {}", result.artifacts().getFirst().content());
        assertEquals(List.of("JAVA_COMPILE", "JAVA_CONSUMER_TESTS"), result.validationScope().passedTargets());
        assertEquals(List.of("ANGULAR"), result.validationScope().notValidatedTargets());
    }

    @Test void runLookupReturnsRegistryAndPersistedBuildDiagnostics() {
        UUID id = UUID.randomUUID(), versionId = UUID.randomUUID(), artifactId = UUID.randomUUID();
        GenerationRun run = mock(GenerationRun.class);
        MetadataVersion version = mock(MetadataVersion.class);
        GeneratedArtifact artifact = mock(GeneratedArtifact.class);
        when(runs.findById(id)).thenReturn(Optional.of(run));
        when(run.getId()).thenReturn(id);
        when(run.getStatus()).thenReturn(GenerationRunStatus.BUILD_FAILED);
        when(run.getMetadataVersion()).thenReturn(version);
        when(version.getId()).thenReturn(versionId);
        when(run.getCreatedAt()).thenReturn(Instant.parse("2026-10-01T00:00:00Z"));
        when(run.getUpdatedAt()).thenReturn(Instant.parse("2026-10-01T00:00:01Z"));
        when(run.getCompileExitCode()).thenReturn(1);
        when(run.getCompileOutput()).thenReturn("compile error");
        when(run.getTestExitCode()).thenReturn((Integer) null);
        when(artifacts.findAllByGenerationRunIdOrderByRelativePathAsc(id)).thenReturn(List.of(artifact));
        when(artifact.getId()).thenReturn(artifactId);
        when(artifact.getRelativePath()).thenReturn("src/app/generated/thing.component.ts");
        var detail = service.run(id);
        assertEquals("BUILD_FAILED", detail.status());
        assertEquals(versionId, detail.metadataVersionId());
        assertEquals(artifactId, detail.artifacts().getFirst().id());
        assertEquals("compile error", detail.compileOutput());
        assertNull(detail.testExitCode());
        assertTrue(detail.validationScope().passedTargets().isEmpty());
        assertEquals(List.of("ANGULAR"), detail.validationScope().notValidatedTargets());
        assertThrows(ResourceNotFoundException.class, () -> service.run(UUID.randomUUID()));
    }

    @Test void failedAttemptRequiresOwnerAndBothResourcePermissions() {
        UUID id = UUID.randomUUID();
        GenerationRun run = mock(GenerationRun.class);
        GsuifUser owner = mock(GsuifUser.class);
        MetadataVersion version = mock(MetadataVersion.class);
        when(runs.findById(id)).thenReturn(Optional.of(run));
        when(run.getId()).thenReturn(id);
        when(run.getTriggeringUser()).thenReturn(owner);
        when(owner.getUsername()).thenReturn("owner");
        when(run.getStatus()).thenReturn(GenerationRunStatus.FAILED);
        when(run.getMetadataVersion()).thenReturn(version);
        when(version.getId()).thenReturn(UUID.randomUUID());
        when(runs.persistedFailure(id, "owner")).thenReturn(Optional.of(
                new GenerationRunService.PersistedFailure(id, GenerationFailureStage.CONFIG,
                        GenerationFailureCode.CONFIG_MISSING)));

        assertThrows(org.springframework.security.authentication.InsufficientAuthenticationException.class,
                () -> service.failedAttempt(id, null));
        var context = org.springframework.security.core.context.SecurityContextHolder.getContext();
        context.setAuthentication(new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(
                "other", null, List.of()));
        assertThrows(org.springframework.security.access.AccessDeniedException.class,
                () -> service.failedAttempt(id, "other"));
        try {
            context.setAuthentication(new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(
                    "owner", null, List.of()));
            doThrow(new AccessDeniedException("missing Page"))
                    .when(permissionChecker).requireEntityAccess(eq("GsuifPage"), any());
            assertThrows(AccessDeniedException.class, () -> service.failedAttempt(id, "owner"));
            reset(permissionChecker);
            doThrow(new AccessDeniedException("missing Version"))
                    .when(permissionChecker).requireEntityAccess(eq("MetadataVersion"), any());
            assertThrows(AccessDeniedException.class, () -> service.failedAttempt(id, "owner"));
            reset(permissionChecker);
            var detail = service.failedAttempt(id, "owner");
            assertEquals(id, detail.runId());
            assertEquals("CONFIG", detail.failureStage());
            assertEquals("CONFIG_MISSING", detail.failureCode());
        } finally {
            org.springframework.security.core.context.SecurityContextHolder.clearContext();
        }
        verifyNoInteractions(artifacts);
    }

    @Test void runDeniedWhenPageAccessDenied() {
        UUID runId = UUID.randomUUID();
        doThrow(new AccessDeniedException("Entity access denied: GsuifPage"))
                .when(permissionChecker).requireEntityAccess(eq("GsuifPage"), any());

        assertThrows(AccessDeniedException.class, () -> service.run(runId));

        verify(runs, never()).findById(any());
    }

    private static GenerationSpecification specification() {
        return new GenerationSpecification("1.0.0", "com.example.fixture", null, null, null, null);
    }
}
