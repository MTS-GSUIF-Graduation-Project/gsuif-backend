package eg.mts.gsuif.service.impl;


import tools.jackson.databind.ObjectMapper;
import eg.mts.gsuif.aspect.Loggable;
import eg.mts.gsuif.dto.CreateMetadataVersionRequest;
import eg.mts.gsuif.dto.MetadataVersionDto;
import eg.mts.gsuif.dto.PagedBody;
import eg.mts.gsuif.entity.GsuifPage;
import eg.mts.gsuif.entity.MetadataVersion;
import eg.mts.gsuif.exception.ResourceNotFoundException;
import eg.mts.gsuif.repository.GsuifPageRepository;
import eg.mts.gsuif.repository.MetadataVersionRepository;
import eg.mts.gsuif.service.MetadataVersionService;
import eg.mts.gsuif.security.EntityPermissionChecker;
import eg.mts.gsuif.exception.MetadataValidationException;
import eg.mts.gsuif.validator.MetadataSchemaValidator;
import eg.mts.gsuif.validator.MetadataBusinessValidator;
import eg.mts.gsuif.validator.MetadataValidationContext;
import java.util.List;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;
import java.util.Map;
import java.util.Set;

@Service
@Transactional(readOnly = true)
public class MetadataVersionServiceImpl implements MetadataVersionService {

    // NetworkNT's JsonSchema uses Jackson 2.x (com.fasterxml.jackson), while this project uses Jackson 3.x (tools.jackson).
    // This separate mapper safely converts the Jackson 3 JsonNode structure into a Jackson 2 JsonNode structure
    // by serializing and deserializing the raw JSON string, preserving the exact incoming value and shape for validation.
    private static final com.fasterxml.jackson.databind.ObjectMapper STANDARD_MAPPER = new com.fasterxml.jackson.databind.ObjectMapper()
            .enable(com.fasterxml.jackson.databind.DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .setNodeFactory(com.fasterxml.jackson.databind.node.JsonNodeFactory.withExactBigDecimals(true));

    private static final int DEFAULT_PAGE_SIZE = 20;
    private static final int MAX_PAGE_SIZE = 100;

    private final MetadataVersionRepository metadataVersionRepository;
    private final GsuifPageRepository pageRepository;
    private final ObjectMapper objectMapper;
    private final MetadataSchemaValidator schemaValidator;
    private final MetadataBusinessValidator businessValidator;
    private final EntityPermissionChecker permissionChecker;

    public MetadataVersionServiceImpl(MetadataVersionRepository metadataVersionRepository,
                                      GsuifPageRepository pageRepository,
                                      ObjectMapper objectMapper,
                                      MetadataSchemaValidator schemaValidator,
                                      MetadataBusinessValidator businessValidator,
                                      EntityPermissionChecker permissionChecker) {
        this.metadataVersionRepository = metadataVersionRepository;
        this.pageRepository = pageRepository;
        this.objectMapper = objectMapper;
        this.schemaValidator = schemaValidator;
        this.businessValidator = businessValidator;
        this.permissionChecker = permissionChecker;
    }

    @Override
    @Transactional
    @Loggable
    public MetadataVersionDto create(UUID pageId, CreateMetadataVersionRequest request) {
        Authentication authentication = requireAccess();
        com.fasterxml.jackson.databind.JsonNode standardSnapshot = null;
        String serializedSnapshot = null;
        if (request.snapshot() != null) {
            try {
                serializedSnapshot = objectMapper.writeValueAsString(request.snapshot());
                standardSnapshot = STANDARD_MAPPER.readTree(serializedSnapshot);
            } catch (Exception e) {
                throw new IllegalStateException("Failed to convert snapshot node", e);
            }
        }
        List<eg.mts.gsuif.validator.MetadataSchemaValidator.ValidationError> validationErrors = schemaValidator.validate(request.schemaVersion(), standardSnapshot);
        if (validationErrors == null) {
            throw new IllegalStateException("Validator unexpectedly returned null");
        }
        if (!validationErrors.isEmpty()) {
            Map<String, String> groupedErrors = new java.util.LinkedHashMap<>();
            for (eg.mts.gsuif.validator.MetadataSchemaValidator.ValidationError error : validationErrors) {
                groupedErrors.merge(error.path(), error.message(), (oldMsg, newMsg) -> oldMsg + "; " + newMsg);
            }
            throw new eg.mts.gsuif.exception.MetadataValidationException("Metadata validation failed", groupedErrors);
        }

        var businessErrors = businessValidator.validate(
                MetadataValidationContext.snapshot(standardSnapshot, serializedSnapshot));
        if (!businessErrors.isEmpty()) {
            throw new MetadataValidationException("Metadata validation failed",
                    MetadataBusinessValidator.groupErrors(businessErrors));
        }

        GsuifPage page = pageRepository.findByIdWithLock(pageId)
                .orElseThrow(() -> new ResourceNotFoundException("Page not found with id: " + pageId));

        int nextVersion = metadataVersionRepository.findFirstByPageIdOrderByVersionDesc(pageId)
                .map(v -> v.getVersion() + 1)
                .orElse(1);

        MetadataVersion newVersion = new MetadataVersion();
        newVersion.setPage(page);
        newVersion.setVersion(nextVersion);
        newVersion.setSchemaVersion(request.schemaVersion());
        newVersion.setSnapshot(serializedSnapshot);

        newVersion = metadataVersionRepository.save(newVersion);
        page.setCurrentMetadataVersionId(newVersion.getId());
        pageRepository.save(page);

        return toDto(newVersion, deniedFields(authentication));
    }

    @Override
    @Loggable
    public MetadataVersionDto getLatest(UUID pageId) {
        Authentication authentication = requireAccess();
        verifyPageExists(pageId);
        return metadataVersionRepository.findFirstByPageIdOrderByVersionDesc(pageId)
                .map(entity -> toDto(entity, deniedFields(authentication)))
                .orElseThrow(() -> new ResourceNotFoundException("No metadata versions found for page id: " + pageId));
    }

    @Override
    @Loggable
    public MetadataVersionDto getCurrent(UUID pageId) {
        Authentication authentication = requireAccess();
        GsuifPage page = pageRepository.findById(pageId)
                .orElseThrow(() -> new ResourceNotFoundException("Page not found with id: " + pageId));
        UUID currentId = page.getCurrentMetadataVersionId();
        if (currentId == null) {
            throw new ResourceNotFoundException("No current metadata version for page id: " + pageId);
        }
        return metadataVersionRepository.findByIdAndPageId(currentId, pageId)
                .map(entity -> toDto(entity, deniedFields(authentication)))
                .orElseThrow(() -> new ResourceNotFoundException("Current metadata version not found for page id: " + pageId));
    }

    @Override
    @Transactional
    @Loggable
    public MetadataVersionDto selectCurrent(UUID pageId, UUID versionId) {
        Authentication authentication = requireAccess();
        GsuifPage page = pageRepository.findByIdWithLock(pageId)
                .orElseThrow(() -> new ResourceNotFoundException("Page not found with id: " + pageId));
        MetadataVersion version = metadataVersionRepository.findByIdAndPageId(versionId, pageId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Metadata version not found with id: " + versionId + " for page: " + pageId));
        if (!versionId.equals(page.getCurrentMetadataVersionId())) {
            page.setCurrentMetadataVersionId(versionId);
            pageRepository.save(page);
        }
        return toDto(version, deniedFields(authentication));
    }

    @Override
    @Loggable
    public MetadataVersionDto getById(UUID pageId, UUID versionId) {
        Authentication authentication = requireAccess();
        verifyPageExists(pageId);
        return metadataVersionRepository.findByIdAndPageId(versionId, pageId)
                .map(entity -> toDto(entity, deniedFields(authentication)))
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Metadata version not found with id: " + versionId + " for page: " + pageId));
    }

    @Override
    @Loggable
    public PagedBody<MetadataVersionDto> getAll(UUID pageId, Pageable pageable) {
        Authentication authentication = requireAccess();
        Set<String> deniedFields = deniedFields(authentication);
        verifyPageExists(pageId);

        int pageNumber = pageable.isPaged() ? Math.max(0, pageable.getPageNumber()) : 0;
        int pageSize = pageable.isPaged()
                ? Math.min(MAX_PAGE_SIZE, Math.max(1, pageable.getPageSize()))
                : DEFAULT_PAGE_SIZE;
        Sort sort = (pageable.isPaged() && pageable.getSort().isSorted())
                ? pageable.getSort()
                : Sort.by(Sort.Direction.DESC, "version");

        Pageable cappedPageable = PageRequest.of(pageNumber, pageSize, sort);
        Page<MetadataVersion> page = metadataVersionRepository.findAllByPageId(pageId, cappedPageable);
        return PagedBody.of(page.map(entity -> toDto(entity, deniedFields)));
    }

    private void verifyPageExists(UUID pageId) {
        if (!pageRepository.existsById(pageId)) {
            throw new ResourceNotFoundException("Page not found with id: " + pageId);
        }
    }

    private Authentication requireAccess() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        permissionChecker.requireEntityAccess("MetadataVersion", authentication);
        return authentication;
    }

    private Set<String> deniedFields(Authentication authentication) {
        return permissionChecker.getDeniedFields("MetadataVersion", authentication);
    }

    private MetadataVersionDto toDto(MetadataVersion entity, Set<String> deniedFields) {
        try {
            return new MetadataVersionDto(
                    deniedFields.contains("id") ? null : entity.getId(),
                    deniedFields.contains("pageId") ? null : entity.getPage().getId(),
                    deniedFields.contains("projectId") ? null : entity.getPage().getProject().getId(),
                    deniedFields.contains("version") ? null : entity.getVersion(),
                    deniedFields.contains("schemaVersion") ? null : entity.getSchemaVersion(),
                    deniedFields.contains("isCurrent") ? null
                            : entity.getId().equals(entity.getPage().getCurrentMetadataVersionId()),
                    deniedFields.contains("snapshot") ? null : objectMapper.readTree(entity.getSnapshot()),
                    deniedFields.contains("createdAt") ? null : entity.getCreatedAt(),
                    deniedFields.contains("updatedAt") ? null : entity.getUpdatedAt(),
                    deniedFields.contains("createdBy") ? null : entity.getCreatedBy(),
                    deniedFields.contains("lastModifiedBy") ? null : entity.getLastModifiedBy()
            );
        } catch (Exception e) {
            throw new RuntimeException("Failed to deserialize snapshot from DB", e);
        }
    }
}
