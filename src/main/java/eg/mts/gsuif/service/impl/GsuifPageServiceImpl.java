package eg.mts.gsuif.service.impl;

import eg.mts.gsuif.aspect.Loggable;
import eg.mts.gsuif.dto.CreatePageRequest;
import eg.mts.gsuif.dto.PageDto;
import eg.mts.gsuif.dto.PagedBody;
import eg.mts.gsuif.dto.UpdatePageRequest;
import eg.mts.gsuif.entity.GsuifPage;
import eg.mts.gsuif.exception.DuplicateResourceException;
import eg.mts.gsuif.exception.PageDeletionException;
import eg.mts.gsuif.exception.ResourceNotFoundException;
import eg.mts.gsuif.repository.GsuifPageRepository;
import eg.mts.gsuif.repository.GsuifProjectRepository;
import eg.mts.gsuif.repository.MetadataVersionRepository;
import eg.mts.gsuif.service.GsuifPageService;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Production implementation of {@link GsuifPageService}.
 */
@Service
@Transactional(readOnly = true)
public class GsuifPageServiceImpl implements GsuifPageService {

    private static final int DEFAULT_PAGE_SIZE = 20;
    private static final int MAX_PAGE_SIZE = 100;

    private final GsuifProjectRepository projectRepository;
    private final GsuifPageRepository pageRepository;
    private final MetadataVersionRepository metadataVersionRepository;

    public GsuifPageServiceImpl(GsuifProjectRepository projectRepository,
                                GsuifPageRepository pageRepository,
                                MetadataVersionRepository metadataVersionRepository) {
        this.projectRepository = projectRepository;
        this.pageRepository = pageRepository;
        this.metadataVersionRepository = metadataVersionRepository;
    }

    @Override
    @Transactional
    @Loggable
    public PageDto create(UUID projectId, CreatePageRequest request) {
        verifyProjectExists(projectId);

        if (pageRepository.existsByProjectIdAndName(projectId, request.name())) {
            throw new DuplicateResourceException("name",
                    "Page with name '" + request.name() + "' already exists in project '" + projectId + "'");
        }

        if (request.route() != null && pageRepository.existsByProjectIdAndRoute(projectId, request.route())) {
            throw new DuplicateResourceException("route",
                    "Page with route '" + request.route() + "' already exists in project '" + projectId + "'");
        }

        GsuifPage page = new GsuifPage();
        page.setProject(projectRepository.getReferenceById(projectId));
        page.setName(request.name());
        page.setRoute(request.route());

        return toDto(pageRepository.save(page));
    }

    @Override
    @Loggable
    public PageDto getById(UUID projectId, UUID pageId) {
        verifyProjectExists(projectId);
        return pageRepository.findByIdAndProjectId(pageId, projectId)
                .map(this::toDto)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Page not found with id: " + pageId + " in project: " + projectId));
    }

    @Override
    @Loggable
    public PagedBody<PageDto> getAll(UUID projectId, Pageable pageable) {
        verifyProjectExists(projectId);

        int pageNumber = pageable.isPaged() ? Math.max(0, pageable.getPageNumber()) : 0;
        int pageSize = pageable.isPaged()
                ? Math.min(MAX_PAGE_SIZE, Math.max(1, pageable.getPageSize()))
                : DEFAULT_PAGE_SIZE;
        Sort sort = (pageable.isPaged() && pageable.getSort().isSorted())
                ? pageable.getSort()
                : Sort.by(Sort.Direction.DESC, "createdAt");

        Pageable cappedPageable = PageRequest.of(pageNumber, pageSize, sort);
        Page<GsuifPage> page = pageRepository.findAllByProjectId(projectId, cappedPageable);
        return PagedBody.of(page.map(this::toDto));
    }

    @Override
    @Transactional
    @Loggable
    public PageDto update(UUID projectId, UUID pageId, UpdatePageRequest request) {
        verifyProjectExists(projectId);

        GsuifPage page = pageRepository.findByIdAndProjectId(pageId, projectId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Page not found with id: " + pageId + " in project: " + projectId));

        if (!page.getName().equals(request.name())
                && pageRepository.existsByProjectIdAndNameAndIdNot(projectId, request.name(), pageId)) {
            throw new DuplicateResourceException("name",
                    "Page with name '" + request.name() + "' already exists in project '" + projectId + "'");
        }

        if (request.route() != null
                && pageRepository.existsByProjectIdAndRouteAndIdNot(projectId, request.route(), pageId)) {
            throw new DuplicateResourceException("route",
                    "Page with route '" + request.route() + "' already exists in project '" + projectId + "'");
        }

        page.setName(request.name());
        page.setRoute(request.route());

        return toDto(pageRepository.save(page));
    }

    @Override
    @Transactional
    @Loggable
    public void delete(UUID projectId, UUID pageId) {
        verifyProjectExists(projectId);

        GsuifPage page = pageRepository.findByIdAndProjectId(pageId, projectId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Page not found with id: " + pageId + " in project: " + projectId));

        if (metadataVersionRepository.existsByPageId(pageId)) {
            throw new PageDeletionException(
                    "Cannot delete page with id '" + pageId + "' because it has existing metadata versions");
        }

        pageRepository.delete(page);
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private void verifyProjectExists(UUID projectId) {
        if (!projectRepository.existsById(projectId)) {
            throw new ResourceNotFoundException("Project not found with id: " + projectId);
        }
    }

    private PageDto toDto(GsuifPage entity) {
        return new PageDto(
                entity.getId(),
                entity.getProject().getId(),
                entity.getName(),
                entity.getRoute(),
                entity.getCreatedAt(),
                entity.getUpdatedAt(),
                entity.getCreatedBy(),
                entity.getLastModifiedBy()
        );
    }
}
