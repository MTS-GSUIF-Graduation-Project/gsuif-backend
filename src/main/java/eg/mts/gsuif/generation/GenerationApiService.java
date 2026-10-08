package eg.mts.gsuif.generation;

import com.fasterxml.jackson.databind.ObjectMapper;
import eg.mts.gsuif.dto.GenerationApiDtos;
import eg.mts.gsuif.entity.GsuifPage;
import eg.mts.gsuif.entity.GsuifUser;
import eg.mts.gsuif.entity.MetadataVersion;
import eg.mts.gsuif.exception.ResourceNotFoundException;
import eg.mts.gsuif.generation.GenerationContext.Target;
import eg.mts.gsuif.repository.GeneratedArtifactRepository;
import eg.mts.gsuif.repository.GsuifPageRepository;
import eg.mts.gsuif.repository.GsuifUserRepository;
import eg.mts.gsuif.repository.MetadataVersionRepository;
import eg.mts.gsuif.validator.MetadataSchemaValidator;
import org.springframework.security.authentication.InsufficientAuthenticationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
public class GenerationApiService {
    private final GsuifPageRepository pages;
    private final MetadataVersionRepository versions;
    private final GsuifUserRepository users;
    private final GeneratedArtifactRepository artifacts;
    private final GenerationWorkflow workflow;
    private final GenerationRunService runs;
    private final TemplateOnlyProvider provider;

    public GenerationApiService(GsuifPageRepository pages, MetadataVersionRepository versions,
            GsuifUserRepository users, GeneratedArtifactRepository artifacts,
            GenerationWorkflow workflow, GenerationRunService runs, MetadataSchemaValidator schemaValidator) {
        this.pages = pages;
        this.versions = versions;
        this.users = users;
        this.artifacts = artifacts;
        this.workflow = workflow;
        this.runs = runs;
        this.provider = new TemplateOnlyProvider(new GenerationContextBuilder(new ObjectMapper(), schemaValidator));
    }

    @Transactional
    public GenerationApiDtos.CreatedRun generate(GenerationApiDtos.GenerateRequest request, String username) {
        Set<Target> targets = targets(request.generationType());
        if (request.specification() == null) {
            throw new GenerationValidationException(List.of("specification: required"));
        }
        if (username == null || username.isBlank()) {
            throw new InsufficientAuthenticationException("Authentication required");
        }
        GsuifUser user = users.findByUsername(username)
                .orElseThrow(() -> new InsufficientAuthenticationException("Authenticated user not found"));
        GsuifPage page = pages.findById(request.pageId())
                .orElseThrow(() -> new ResourceNotFoundException("Page not found"));
        UUID versionId = page.getCurrentMetadataVersionId();
        if (versionId == null) throw new ResourceNotFoundException("Page has no selected metadata version");
        MetadataVersion version = versions.findByIdAndPageId(versionId, request.pageId())
                .orElseThrow(() -> new ResourceNotFoundException("Selected metadata version not found"));
        var completed = workflow.generateAndRecord(UUID.randomUUID(), version, user,
                request.specification(), targets, "spring-angular", Map.of());
        List<GenerationApiDtos.TextArtifact> generated = completed.result().artifacts().stream()
                .map(a -> new GenerationApiDtos.TextArtifact(a.relativePath(),
                        new String(a.bytes(), StandardCharsets.UTF_8), a.sha256(),
                        a.templateVersion(), a.catalogVersion()))
                .toList();
        return new GenerationApiDtos.CreatedRun(completed.run().getId(),
                completed.run().getStatus().name(), version.getId(), generated);
    }

    public List<GenerationApiDtos.Provider> providers() {
        return List.of(new GenerationApiDtos.Provider(provider.getProviderName(), provider.isAvailable()));
    }

    @Transactional(readOnly = true)
    public GenerationApiDtos.RunDetails run(UUID id) {
        var run = runs.findById(id).orElseThrow(() -> new ResourceNotFoundException("Generation run not found"));
        var linked = artifacts.findAllByGenerationRunIdOrderByRelativePathAsc(id).stream()
                .map(a -> new GenerationApiDtos.LinkedArtifact(a.getId(), a.getArtifactName(),
                        a.getArtifactType(), a.getRelativePath(), a.getTemplateVersion()))
                .toList();
        return new GenerationApiDtos.RunDetails(run.getId(), run.getStatus().name(),
                run.getCreatedAt(), run.getUpdatedAt(), run.getMetadataVersion().getId(), linked,
                run.getCompileExitCode(), run.getCompileOutput(), run.getTestExitCode(), run.getTestOutput());
    }

    static Set<Target> targets(String generationType) {
        if (generationType == null) throw new GenerationValidationException(List.of("generationType: required"));
        return switch (generationType) {
            case "ENTITY" -> Set.of(Target.ENTITY);
            case "CONTROLLER" -> Set.of(Target.CONTROLLER);
            case "ANGULAR" -> Set.of(Target.ANGULAR);
            case "FULL_STACK" -> Set.of(Target.ENTITY, Target.CONTROLLER, Target.ANGULAR);
            default -> throw new GenerationValidationException(List.of("generationType: unsupported value"));
        };
    }
}
