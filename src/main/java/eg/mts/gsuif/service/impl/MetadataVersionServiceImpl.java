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
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

@Service
@Transactional(readOnly = true)
public class MetadataVersionServiceImpl implements MetadataVersionService {

    private static final int DEFAULT_PAGE_SIZE = 20;
    private static final int MAX_PAGE_SIZE = 100;

    private final MetadataVersionRepository metadataVersionRepository;
    private final GsuifPageRepository pageRepository;
    private final ObjectMapper objectMapper;

    public MetadataVersionServiceImpl(MetadataVersionRepository metadataVersionRepository,
                                      GsuifPageRepository pageRepository,
                                      ObjectMapper objectMapper) {
        this.metadataVersionRepository = metadataVersionRepository;
        this.pageRepository = pageRepository;
        this.objectMapper = objectMapper;
    }

    @Override
    @Transactional
    @Loggable
    public MetadataVersionDto create(UUID pageId, CreateMetadataVersionRequest request) {
        GsuifPage page = pageRepository.findByIdWithLock(pageId)
                .orElseThrow(() -> new ResourceNotFoundException("Page not found with id: " + pageId));

        int nextVersion = metadataVersionRepository.findFirstByPageIdOrderByVersionDesc(pageId)
                .map(v -> v.getVersion() + 1)
                .orElse(1);

        MetadataVersion newVersion = new MetadataVersion();
        newVersion.setPage(page);
        newVersion.setVersion(nextVersion);
        newVersion.setSchemaVersion(request.schemaVersion());
        try {
            newVersion.setSnapshot(objectMapper.writeValueAsString(request.snapshot()));
        } catch (Exception e) {
            throw new IllegalArgumentException("Failed to serialize snapshot", e);
        }

        newVersion = metadataVersionRepository.save(newVersion);
        page.setCurrentMetadataVersionId(newVersion.getId());
        pageRepository.save(page);

        return toDto(newVersion);
    }

    @Override
    @Loggable
    public MetadataVersionDto getLatest(UUID pageId) {
        verifyPageExists(pageId);
        return metadataVersionRepository.findFirstByPageIdOrderByVersionDesc(pageId)
                .map(this::toDto)
                .orElseThrow(() -> new ResourceNotFoundException("No metadata versions found for page id: " + pageId));
    }

    @Override
    @Loggable
    public MetadataVersionDto getById(UUID pageId, UUID versionId) {
        verifyPageExists(pageId);
        return metadataVersionRepository.findByIdAndPageId(versionId, pageId)
                .map(this::toDto)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Metadata version not found with id: " + versionId + " for page: " + pageId));
    }

    @Override
    @Loggable
    public PagedBody<MetadataVersionDto> getAll(UUID pageId, Pageable pageable) {
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
        return PagedBody.of(page.map(this::toDto));
    }

    private void verifyPageExists(UUID pageId) {
        if (!pageRepository.existsById(pageId)) {
            throw new ResourceNotFoundException("Page not found with id: " + pageId);
        }
    }

    private MetadataVersionDto toDto(MetadataVersion entity) {
        try {
            return new MetadataVersionDto(
                    entity.getId(),
                    entity.getPage().getId(),
                    entity.getPage().getProject().getId(),
                    entity.getVersion(),
                    entity.getSchemaVersion(),
                    entity.getId().equals(entity.getPage().getCurrentMetadataVersionId()),
                    objectMapper.readTree(entity.getSnapshot()),
                    entity.getCreatedAt(),
                    entity.getUpdatedAt(),
                    entity.getCreatedBy(),
                    entity.getLastModifiedBy()
            );
        } catch (Exception e) {
            throw new RuntimeException("Failed to deserialize snapshot from DB", e);
        }
    }
}
