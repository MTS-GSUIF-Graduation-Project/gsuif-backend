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
import eg.mts.gsuif.security.EntityPermissionChecker;
import eg.mts.gsuif.exception.MetadataValidationException;
import eg.mts.gsuif.validator.MetadataBusinessValidator;
import eg.mts.gsuif.validator.MetadataValidationContext;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;
import java.util.Set;

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
    private final MetadataBusinessValidator businessValidator;
    private final EntityPermissionChecker permissionChecker;

    public GsuifPageServiceImpl(GsuifProjectRepository projectRepository,
                                GsuifPageRepository pageRepository,
                                MetadataVersionRepository metadataVersionRepository,
                                MetadataBusinessValidator businessValidator,
                                EntityPermissionChecker permissionChecker) {
        this.projectRepository = projectRepository;
        this.pageRepository = pageRepository;
        this.metadataVersionRepository = metadataVersionRepository;
        this.businessValidator = businessValidator;
        this.permissionChecker = permissionChecker;
    }

    @Override
    @Transactional
    @Loggable
    public PageDto create(UUID projectId, CreatePageRequest request) {
        Authentication authentication = requireAccess();
        verifyProjectExists(projectId);

        if (pageRepository.existsByProjectIdAndName(projectId, request.name())) {
            throw new DuplicateResourceException("name",
                    "Page with name '" + request.name() + "' already exists in project '" + projectId + "'");
        }

        validateBusinessRules(projectId, null, request.route());

        GsuifPage page = new GsuifPage();
        page.setProject(projectRepository.getReferenceById(projectId));
        page.setName(request.name());
        page.setRoute(request.route());

        return toDto(pageRepository.save(page), deniedFields(authentication));
    }

    @Override
    @Loggable
    public PageDto getById(UUID projectId, UUID pageId) {
        Authentication authentication = requireAccess();
        verifyProjectExists(projectId);
        return pageRepository.findByIdAndProjectId(pageId, projectId)
                .map(entity -> toDto(entity, deniedFields(authentication)))
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Page not found with id: " + pageId + " in project: " + projectId));
    }

    @Override
    @Loggable
    public PagedBody<PageDto> getAll(UUID projectId, Pageable pageable) {
        Authentication authentication = requireAccess();
        Set<String> deniedFields = deniedFields(authentication);
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
        return PagedBody.of(page.map(entity -> toDto(entity, deniedFields)));
    }

    @Override
    @Transactional
    @Loggable
    public PageDto update(UUID projectId, UUID pageId, UpdatePageRequest request) {
        Authentication authentication = requireAccess();
        verifyProjectExists(projectId);

        GsuifPage page = pageRepository.findByIdAndProjectId(pageId, projectId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Page not found with id: " + pageId + " in project: " + projectId));

        if (!page.getName().equals(request.name())
                && pageRepository.existsByProjectIdAndNameAndIdNot(projectId, request.name(), pageId)) {
            throw new DuplicateResourceException("name",
                    "Page with name '" + request.name() + "' already exists in project '" + projectId + "'");
        }

        validateBusinessRules(projectId, pageId, request.route());

        page.setName(request.name());
        page.setRoute(request.route());

        return toDto(pageRepository.save(page), deniedFields(authentication));
    }

    @Override
    @Transactional
    @Loggable
    public void delete(UUID projectId, UUID pageId) {
        requireAccess();
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

    private void validateBusinessRules(UUID projectId, UUID pageId, String route) {
        var errors = businessValidator.validate(MetadataValidationContext.page(projectId, pageId, route));
        if (errors.isEmpty()) return;
        var grouped = MetadataBusinessValidator.groupErrors(errors);
        // Preserve the existing route-conflict exception contract for page callers.
        if (grouped.size() == 1 && grouped.containsKey("route")) {
            throw new DuplicateResourceException("route", grouped.get("route"));
        }
        throw new MetadataValidationException("Metadata validation failed", grouped);
    }

    private void verifyProjectExists(UUID projectId) {
        if (!projectRepository.existsById(projectId)) {
            throw new ResourceNotFoundException("Project not found with id: " + projectId);
        }
    }

    private Authentication requireAccess() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        permissionChecker.requireEntityAccess("GsuifPage", authentication);
        return authentication;
    }

    private Set<String> deniedFields(Authentication authentication) {
        return permissionChecker.getDeniedFields("GsuifPage", authentication);
    }

    private PageDto toDto(GsuifPage entity, Set<String> deniedFields) {
        return new PageDto(
                deniedFields.contains("id") ? null : entity.getId(),
                deniedFields.contains("projectId") ? null : entity.getProject().getId(),
                deniedFields.contains("name") ? null : entity.getName(),
                deniedFields.contains("route") ? null : entity.getRoute(),
                deniedFields.contains("createdAt") ? null : entity.getCreatedAt(),
                deniedFields.contains("updatedAt") ? null : entity.getUpdatedAt(),
                deniedFields.contains("createdBy") ? null : entity.getCreatedBy(),
                deniedFields.contains("lastModifiedBy") ? null : entity.getLastModifiedBy()
        );
    }
}
