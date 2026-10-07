package eg.mts.gsuif.service;

import eg.mts.gsuif.dto.CreatePermissionRequest;
import eg.mts.gsuif.dto.PagedBody;
import eg.mts.gsuif.dto.PermissionDto;
import eg.mts.gsuif.dto.UpdatePermissionRequest;
import org.springframework.data.domain.Pageable;

import java.util.UUID;

/**
 * Business service for permission CRUD operations.
 */
public interface PermissionService {

    PermissionDto create(CreatePermissionRequest request);

    PermissionDto getById(UUID id);

    PagedBody<PermissionDto> getAll(Pageable pageable);

    PermissionDto update(UUID id, UpdatePermissionRequest request);

    void delete(UUID id);
}
