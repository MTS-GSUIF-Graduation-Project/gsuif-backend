package eg.mts.gsuif.service.impl;

import eg.mts.gsuif.aspect.Loggable;
import eg.mts.gsuif.dto.CreateProjectRequest;
import eg.mts.gsuif.dto.PagedBody;
import eg.mts.gsuif.dto.ProjectDto;
import eg.mts.gsuif.dto.UpdateProjectRequest;
import eg.mts.gsuif.entity.GsuifProject;
import eg.mts.gsuif.exception.DuplicateResourceException;
import eg.mts.gsuif.exception.ProjectDeletionException;
import eg.mts.gsuif.exception.ResourceNotFoundException;
import eg.mts.gsuif.repository.GsuifPageRepository;
import eg.mts.gsuif.repository.GsuifProjectRepository;
import eg.mts.gsuif.service.GsuifProjectService;
import eg.mts.gsuif.security.EntityPermissionChecker;
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
 * Production implementation of {@link GsuifProjectService}.
 */
@Service
@Transactional(readOnly = true)
public class GsuifProjectServiceImpl implements GsuifProjectService {

    private static final int DEFAULT_PAGE_SIZE = 20;
    private static final int MAX_PAGE_SIZE = 100;

    private final GsuifProjectRepository gsuifProjectRepository;
    private final GsuifPageRepository gsuifPageRepository;
    private final EntityPermissionChecker permissionChecker;

    public GsuifProjectServiceImpl(GsuifProjectRepository gsuifProjectRepository,
                                   GsuifPageRepository gsuifPageRepository,
                                   EntityPermissionChecker permissionChecker) {
        this.gsuifProjectRepository = gsuifProjectRepository;
        this.gsuifPageRepository = gsuifPageRepository;
        this.permissionChecker = permissionChecker;
    }

    @Override
    @Transactional
    @Loggable
    public ProjectDto create(CreateProjectRequest request) {
        Authentication authentication = requireAccess();
        if (gsuifProjectRepository.existsByName(request.name())) {
            throw new DuplicateResourceException("name", "Project with name '" + request.name() + "' already exists");
        }

        GsuifProject project = new GsuifProject();
        project.setName(request.name());
        project.setDescription(request.description());

        GsuifProject saved = gsuifProjectRepository.save(project);
        return toDto(saved, deniedFields(authentication));
    }

    @Override
    @Loggable
    public ProjectDto getById(UUID id) {
        Authentication authentication = requireAccess();
        return gsuifProjectRepository.findById(id)
                .map(entity -> toDto(entity, deniedFields(authentication)))
                .orElseThrow(() -> new ResourceNotFoundException("Project not found with id: " + id));
    }

    @Override
    @Loggable
    public PagedBody<ProjectDto> getAll(Pageable pageable) {
        Authentication authentication = requireAccess();
        Set<String> deniedFields = deniedFields(authentication);
        int pageNumber = pageable.isPaged() ? Math.max(0, pageable.getPageNumber()) : 0;
        int pageSize = pageable.isPaged() ? Math.min(MAX_PAGE_SIZE, Math.max(1, pageable.getPageSize())) : DEFAULT_PAGE_SIZE;
        Sort sort = (pageable.isPaged() && pageable.getSort().isSorted())
                ? pageable.getSort()
                : Sort.by(Sort.Direction.DESC, "createdAt");

        Pageable cappedPageable = PageRequest.of(pageNumber, pageSize, sort);
        Page<GsuifProject> page = gsuifProjectRepository.findAll(cappedPageable);
        return PagedBody.of(page.map(entity -> toDto(entity, deniedFields)));
    }

    @Override
    @Transactional
    @Loggable
    public ProjectDto update(UUID id, UpdateProjectRequest request) {
        Authentication authentication = requireAccess();
        GsuifProject project = gsuifProjectRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Project not found with id: " + id));

        if (!project.getName().equals(request.name()) && gsuifProjectRepository.existsByNameAndIdNot(request.name(), id)) {
            throw new DuplicateResourceException("name", "Project with name '" + request.name() + "' already exists");
        }

        project.setName(request.name());
        project.setDescription(request.description());

        GsuifProject updated = gsuifProjectRepository.save(project);
        return toDto(updated, deniedFields(authentication));
    }

    @Override
    @Transactional
    @Loggable
    public void delete(UUID id) {
        requireAccess();
        if (!gsuifProjectRepository.existsById(id)) {
            throw new ResourceNotFoundException("Project not found with id: " + id);
        }

        if (gsuifPageRepository.existsByProjectId(id)) {
            throw new ProjectDeletionException("Cannot delete project with id '" + id + "' because it contains existing pages");
        }

        gsuifProjectRepository.deleteById(id);
    }

    private Authentication requireAccess() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        permissionChecker.requireEntityAccess("GsuifProject", authentication);
        return authentication;
    }

    private Set<String> deniedFields(Authentication authentication) {
        return permissionChecker.getDeniedFields("GsuifProject", authentication);
    }

    private ProjectDto toDto(GsuifProject entity, Set<String> deniedFields) {
        return new ProjectDto(
                deniedFields.contains("id") ? null : entity.getId(),
                deniedFields.contains("name") ? null : entity.getName(),
                deniedFields.contains("description") ? null : entity.getDescription(),
                deniedFields.contains("createdAt") ? null : entity.getCreatedAt(),
                deniedFields.contains("updatedAt") ? null : entity.getUpdatedAt(),
                deniedFields.contains("createdBy") ? null : entity.getCreatedBy(),
                deniedFields.contains("lastModifiedBy") ? null : entity.getLastModifiedBy()
        );
    }
}
