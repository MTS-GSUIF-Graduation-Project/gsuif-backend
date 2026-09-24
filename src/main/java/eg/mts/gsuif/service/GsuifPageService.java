package eg.mts.gsuif.service;

import eg.mts.gsuif.dto.CreatePageRequest;
import eg.mts.gsuif.dto.PageDto;
import eg.mts.gsuif.dto.PagedBody;
import eg.mts.gsuif.dto.UpdatePageRequest;
import org.springframework.data.domain.Pageable;

import java.util.UUID;

/**
 * Business service interface for managing pages within a project.
 */
public interface GsuifPageService {

    PageDto create(UUID projectId, CreatePageRequest request);

    PageDto getById(UUID projectId, UUID pageId);

    PagedBody<PageDto> getAll(UUID projectId, Pageable pageable);

    PageDto update(UUID projectId, UUID pageId, UpdatePageRequest request);

    void delete(UUID projectId, UUID pageId);
}
