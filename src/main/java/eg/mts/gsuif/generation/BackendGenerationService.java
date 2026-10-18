package eg.mts.gsuif.generation;

import com.fasterxml.jackson.databind.ObjectMapper;
import eg.mts.gsuif.dto.GenerationApiDtos;
import eg.mts.gsuif.entity.GenerationRun;
import eg.mts.gsuif.entity.GenerationRunStatus;
import eg.mts.gsuif.entity.GsuifUser;
import eg.mts.gsuif.entity.MetadataVersion;
import eg.mts.gsuif.exception.ResourceNotFoundException;
import eg.mts.gsuif.repository.GsuifPageRepository;
import eg.mts.gsuif.repository.GsuifUserRepository;
import eg.mts.gsuif.repository.MetadataVersionRepository;
import eg.mts.gsuif.security.EntityPermissionChecker;
import eg.mts.gsuif.validator.MetadataSchemaValidator;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.authentication.InsufficientAuthenticationException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Connected BACKEND path. Only lookup, reservation and completion hold database transactions. */
@Service
public final class BackendGenerationService {
    private final GsuifPageRepository pages;
    private final MetadataVersionRepository versions;
    private final GsuifUserRepository users;
    private final EntityPermissionChecker permissions;
    private final GenerationRunService runs;
    private final GenerationArtifactStore store;
    private final BackendModelGateway model;
    private final BackendFinalBuild finalBuild;
    private final GenerationCapabilityPreflight preflight;
    private final PackagedContractRegistry registry = new PackagedContractRegistry();
    private final TransactionTemplate readTransaction;

    BackendGenerationService(GsuifPageRepository pages, MetadataVersionRepository versions,
            GsuifUserRepository users, EntityPermissionChecker permissions, GenerationRunService runs,
            GenerationArtifactStore store, BackendModelGateway model, BackendFinalBuild finalBuild,
            MetadataSchemaValidator schemaValidator, PlatformTransactionManager transactionManager) {
        this.pages = pages;
        this.versions = versions;
        this.users = users;
        this.permissions = permissions;
        this.runs = runs;
        this.store = store;
        this.model = model;
        this.finalBuild = finalBuild;
        this.preflight = new GenerationCapabilityPreflight(
                new GenerationContextBuilder(new ObjectMapper(), schemaValidator, registry), registry);
        this.readTransaction = new TransactionTemplate(transactionManager);
        this.readTransaction.setReadOnly(true);
    }

    public GenerationApiDtos.BackendCreated generate(GenerationApiDtos.BackendRequest request, String username) {
        if (username == null || username.isBlank())
            throw new InsufficientAuthenticationException("Authentication required");
        if (request == null || request.pageId() == null || request.specification() == null)
            throw new GenerationValidationException(List.of("pageId and specification are required"));
        GenerationAttemptFingerprint.validateClientShape(request.specification());
        UUID attemptId = request.attemptId() == null ? UUID.randomUUID() : request.attemptId();
        var provenance = model.provenance();
        Resolution selected = readTransaction.execute(status -> resolve(request, username));
        if (selected == null) throw new IllegalStateException("Could not resolve selected metadata version");
        if (selected.descriptor() == null) {
            String fallback = BackendAttemptFingerprint.of(selected.baseFingerprint(),
                    null, List.of(), provenance, finalBuild.imageIdentity());
            GenerationRun reserved = reserveOrReplay(attemptId, selected, fallback, provenance);
            if (reserved.getStatus() == GenerationRunStatus.SUCCESS) return created(reserved);
            GenerationRun failed = runs.recordFailure(attemptId, fallback,
                    GenerationFailureStage.CONFIG, GenerationFailureCode.CONFIG_MISSING);
            throw new GenerationStageException(GenerationFailureStage.CONFIG,
                    GenerationFailureCode.CONFIG_MISSING, failed.getId());
        }
        Prepared prepared;
        try { prepared = prepare(selected, provenance); }
        catch (RuntimeException ex) {
            String fallback = BackendAttemptFingerprint.of(selected.baseFingerprint(),
                    null, List.of(), provenance, finalBuild.imageIdentity());
            GenerationRun reserved = reserveOrReplay(attemptId, selected, fallback, provenance);
            if (reserved.getStatus() == GenerationRunStatus.SUCCESS) return created(reserved);
            GenerationRun failed = runs.recordFailure(attemptId, fallback,
                    GenerationFailureStage.CONFIG, GenerationFailureCode.CONFIG_MISSING);
            throw new GenerationStageException(GenerationFailureStage.CONFIG,
                    GenerationFailureCode.CONFIG_MISSING, failed.getId());
        }
        GenerationRun reserved = reserveOrReplay(attemptId, selected, prepared.fingerprint(), provenance);
        if (reserved.getStatus() == GenerationRunStatus.SUCCESS) return created(reserved);
        BackendModelOutputValidator.Accepted accepted;
        try { accepted = model.generate(prepared.input()); }
        catch (OpenRouterBackendModelAdapter.Failed ex) {
            GenerationFailureStage stage = switch (ex.reason()) {
                case CONFIG, DEPENDENCY_CONFIG, DEPENDENCY_TIMEOUT, DEPENDENCY_UNAVAILABLE, DEPENDENCY_INTEGRITY -> GenerationFailureStage.CONFIG;
                default -> GenerationFailureStage.AI;
            };
            GenerationFailureCode code = stage == GenerationFailureStage.CONFIG
                    ? GenerationFailureCode.CONFIG_MISSING : GenerationFailureCode.MODEL_UNAVAILABLE;
            throw failed(attemptId, prepared.fingerprint(), stage, code);
        } catch (RuntimeException ex) {
            throw failed(attemptId, prepared.fingerprint(), GenerationFailureStage.AI,
                    GenerationFailureCode.UNEXPECTED_FAILURE);
        }
        CompleteBackendPackageAssembler.Assembled assembled;
        try { assembled = CompleteBackendPackageAssembler.combine(prepared.input(), accepted,
                provenance, prepared.deterministic()); }
        catch (RuntimeException ex) {
            throw failed(attemptId, prepared.fingerprint(), GenerationFailureStage.ASSEMBLY,
                    GenerationFailureCode.INVALID_SOURCE_MAP);
        }
        try { finalBuild.validate(assembled); }
        catch (RuntimeException ex) {
            throw failed(attemptId, prepared.fingerprint(), GenerationFailureStage.FINAL_VALIDATION,
                    GenerationFailureCode.RESULT_VALIDATION_FAILED);
        }
        GenerationResult result = result(assembled, selected.descriptor().catalogVersion());
        GenerationArtifactStore.Staged staged = null;
        try {
            staged = store.stage(reserved.getId(), prepared.fingerprint(), result);
            GenerationRun completed = runs.completeBackendAttempt(attemptId, prepared.fingerprint(), staged,
                    result, provenance);
            return created(completed);
        } catch (RuntimeException ex) {
            throw failed(attemptId, prepared.fingerprint(), GenerationFailureStage.FINAL_VALIDATION,
                    GenerationFailureCode.RESULT_VALIDATION_FAILED);
        } finally { store.discard(staged); }
    }

    private Resolution resolve(GenerationApiDtos.BackendRequest request, String username) {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        permissions.requireEntityAccess("GsuifPage", authentication);
        permissions.requireEntityAccess("MetadataVersion", authentication);
        GsuifUser user = users.findByUsername(username)
                .orElseThrow(() -> new InsufficientAuthenticationException("Authenticated user not found"));
        var page = pages.findById(request.pageId())
                .orElseThrow(() -> new ResourceNotFoundException("Page not found"));
        UUID selectedVersionId = page.getCurrentMetadataVersionId();
        if (selectedVersionId == null) throw new ResourceNotFoundException("Page has no selected metadata version");
        MetadataVersion version = versions.findByIdAndPageId(selectedVersionId, request.pageId())
                .orElseThrow(() -> new ResourceNotFoundException("Selected metadata version not found"));
        String base = GenerationAttemptFingerprint.request(version, user, request.specification(),
                Set.of(GenerationContext.Target.ENTITY, GenerationContext.Target.CONTROLLER),
                "spring-angular", Map.of());
        try {
            CompleteOutputDescriptor descriptor = preflight.describe(version, request.specification(),
                    GenerationCapabilityPreflight.OutputMode.BACKEND);
            return new Resolution(version, user, base, descriptor);
        } catch (GenerationValidationException ex) { throw ex; }
        catch (RuntimeException internal) { return new Resolution(version, user, base, null); }
    }

    private Prepared prepare(Resolution selected, CompleteBackendPackageAssembler.Provenance provenance) {
        var descriptor = selected.descriptor();
        var plan = GenerationFilePlan.forDescriptor(descriptor);
        var runtime = new CompleteBackendRuntimeRenderer(registry);
        var rendered = new CompleteBackendDefinitionRenderer().render(descriptor, plan);
        var input = BackendModelInput.from(descriptor, plan, rendered, runtime);
        var deterministic = new CompleteBackendPackageAssembler(runtime).deterministic(input);
        String fingerprint = BackendAttemptFingerprint.of(selected.baseFingerprint(), input, deterministic,
                provenance, finalBuild.imageIdentity());
        return new Prepared(input, deterministic, fingerprint);
    }

    private GenerationRun reserveOrReplay(UUID attemptId, Resolution selected, String fingerprint,
            CompleteBackendPackageAssembler.Provenance provenance) {
        GenerationRun existing = runs.findByAttemptId(attemptId).orElse(null);
        if (existing == null) try {
            return runs.reserveBackendAttempt(attemptId, selected.version(), selected.user(), fingerprint, provenance);
        } catch (DataIntegrityViolationException | IllegalStateException race) {
            existing = runs.findByAttemptId(attemptId).orElseThrow(() -> race);
        }
        try { GenerationRunService.requireSameAttempt(existing, fingerprint); }
        catch (IllegalArgumentException mismatch) {
            throw new GenerationValidationException(List.of("attemptId: bound to different inputs"));
        }
        if (existing.getStatus() != GenerationRunStatus.SUCCESS
                || !BackendPackageIntegrity.TOOL.equals(existing.getToolName()))
            throw new GenerationValidationException(List.of("attemptId: already reserved or terminal; use a new ID"));
        runs.readResult(existing); // Replay only a committed, verified package.
        return existing;
    }

    private GenerationStageException failed(UUID attemptId, String fingerprint,
            GenerationFailureStage stage, GenerationFailureCode code) {
        GenerationRun run = runs.recordFailure(attemptId, fingerprint, stage, code);
        return new GenerationStageException(stage, code, run.getId());
    }

    private GenerationApiDtos.BackendCreated created(GenerationRun run) {
        GenerationResult verified = runs.readResult(run);
        return new GenerationApiDtos.BackendCreated(run.getId(), run.getAttemptId(), run.getStatus().name(),
                run.getMetadataVersion().getId(), verified.artifacts().size(),
                BackendPackageIntegrity.summary(run, verified));
    }

    private static GenerationResult result(CompleteBackendPackageAssembler.Assembled assembled, String catalogVersion) {
        List<GenerationResult.Artifact> files = new ArrayList<>();
        assembled.files().forEach((path, bytes) -> files.add(new GenerationResult.Artifact(path, bytes,
                BackendPackageIntegrity.hash(bytes), "complete-backend", catalogVersion)));
        return new GenerationResult(files, List.of("ISOLATED_BACKEND_BUILD_PASSED"));
    }

    private record Resolution(MetadataVersion version, GsuifUser user, String baseFingerprint,
                              CompleteOutputDescriptor descriptor) { }
    private record Prepared(BackendModelInput input, List<Map<String, byte[]>> deterministic,
                            String fingerprint) { }
}
