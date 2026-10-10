package eg.mts.gsuif.service;

import eg.mts.gsuif.dto.CreateProjectRequest;
import eg.mts.gsuif.dto.PagedBody;
import eg.mts.gsuif.dto.ProjectDto;
import eg.mts.gsuif.dto.UpdateProjectRequest;
import org.springframework.data.domain.Pageable;

import java.util.UUID;

/**
 * Business service interface for managing projects.
 */
public interface GsuifProjectService {

    ProjectDto create(CreateProjectRequest request);

    ProjectDto getById(UUID id);

    PagedBody<ProjectDto> getAll(Pageable pageable);

    ProjectDto update(UUID id, UpdateProjectRequest request);

    void delete(UUID id);
}
