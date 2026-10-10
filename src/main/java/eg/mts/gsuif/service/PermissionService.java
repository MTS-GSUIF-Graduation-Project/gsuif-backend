package eg.mts.gsuif.service;

import eg.mts.gsuif.dto.CreatePermissionRequest;
import eg.mts.gsuif.dto.PagedBody;
import eg.mts.gsuif.dto.PermissionDto;
import eg.mts.gsuif.dto.UpdatePermissionRequest;
import jakarta.validation.Valid;
import org.springframework.data.domain.Pageable;

import java.util.UUID;

/**
 * Business service for permission CRUD operations.
 */
public interface PermissionService {

    PermissionDto create(@Valid CreatePermissionRequest request);

    PermissionDto getById(UUID id);

    PagedBody<PermissionDto> getAll(Pageable pageable);

    PermissionDto update(UUID id, @Valid UpdatePermissionRequest request);

    void delete(UUID id);
}
