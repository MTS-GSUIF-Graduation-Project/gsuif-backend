package eg.mts.gsuif.service.impl;

import eg.mts.gsuif.aspect.Loggable;
import eg.mts.gsuif.dto.CreatePermissionRequest;
import eg.mts.gsuif.dto.PagedBody;
import eg.mts.gsuif.dto.PermissionDto;
import eg.mts.gsuif.dto.UpdatePermissionRequest;
import eg.mts.gsuif.entity.GsuifPermission;
import eg.mts.gsuif.exception.DuplicateResourceException;
import eg.mts.gsuif.exception.PermissionDeletionException;
import eg.mts.gsuif.exception.ResourceNotFoundException;
import eg.mts.gsuif.repository.GsuifPermissionRepository;
import eg.mts.gsuif.repository.GsuifRolePermissionRepository;
import eg.mts.gsuif.service.PermissionService;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.annotation.Validated;

import java.util.UUID;

@Service
@Validated
@Transactional(readOnly = true)
public class PermissionServiceImpl implements PermissionService {

    private static final int DEFAULT_PAGE_SIZE = 20;
    private static final int MAX_PAGE_SIZE = 100;

    private final GsuifPermissionRepository permissionRepository;
    private final GsuifRolePermissionRepository rolePermissionRepository;

    public PermissionServiceImpl(GsuifPermissionRepository permissionRepository,
                                 GsuifRolePermissionRepository rolePermissionRepository) {
        this.permissionRepository = permissionRepository;
        this.rolePermissionRepository = rolePermissionRepository;
    }

    @Override
    @Transactional
    @Loggable
    public PermissionDto create(CreatePermissionRequest request) {
        if (permissionRepository.existsByCode(request.code())) {
            throw new DuplicateResourceException(
                    "code", "Permission with code '" + request.code() + "' already exists");
        }

        GsuifPermission permission = new GsuifPermission();
        permission.setCode(request.code());
        permission.setName(request.name());
        permission.setDescription(request.description());

        return toDto(permissionRepository.save(permission));
    }

    @Override
    @Loggable
    public PermissionDto getById(UUID id) {
        return permissionRepository.findById(id)
                .map(this::toDto)
                .orElseThrow(() -> new ResourceNotFoundException("Permission not found with id: " + id));
    }

    @Override
    @Loggable
    public PagedBody<PermissionDto> getAll(Pageable pageable) {
        int pageNumber = pageable.isPaged() ? Math.max(0, pageable.getPageNumber()) : 0;
        int pageSize = pageable.isPaged()
                ? Math.min(MAX_PAGE_SIZE, Math.max(1, pageable.getPageSize()))
                : DEFAULT_PAGE_SIZE;
        Sort sort = (pageable.isPaged() && pageable.getSort().isSorted())
                ? pageable.getSort()
                : Sort.by(Sort.Direction.ASC, "code");

        Pageable cappedPageable = PageRequest.of(pageNumber, pageSize, sort);
        Page<GsuifPermission> page = permissionRepository.findAll(cappedPageable);
        return PagedBody.of(page.map(this::toDto));
    }

    @Override
    @Transactional
    @Loggable
    public PermissionDto update(UUID id, UpdatePermissionRequest request) {
        GsuifPermission permission = findPermission(id);
        permission.setName(request.name());
        permission.setDescription(request.description());
        return toDto(permissionRepository.save(permission));
    }

    @Override
    @Transactional
    @Loggable
    public void delete(UUID id) {
        GsuifPermission permission = findPermission(id);
        if (rolePermissionRepository.existsByPermission(permission)) {
            throw new PermissionDeletionException(
                    "Cannot delete permission with id '" + id + "' because it is assigned to one or more roles");
        }
        permissionRepository.delete(permission);
    }

    private GsuifPermission findPermission(UUID id) {
        return permissionRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Permission not found with id: " + id));
    }

    private PermissionDto toDto(GsuifPermission permission) {
        return new PermissionDto(
                permission.getId(),
                permission.getCode(),
                permission.getName(),
                permission.getDescription(),
                permission.getCreatedAt(),
                permission.getUpdatedAt(),
                permission.getCreatedBy(),
                permission.getLastModifiedBy()
        );
    }
}
