package eg.mts.gsuif.service.impl;

import eg.mts.gsuif.aspect.Loggable;
import eg.mts.gsuif.dto.CreateWorkOrderRequest;
import eg.mts.gsuif.dto.PagedBody;
import eg.mts.gsuif.dto.UpdateWorkOrderRequest;
import eg.mts.gsuif.dto.WorkOrderDto;
import eg.mts.gsuif.entity.WorkOrder;
import eg.mts.gsuif.entity.WorkOrderStatus;
import eg.mts.gsuif.exception.DuplicateResourceException;
import eg.mts.gsuif.exception.ResourceNotFoundException;
import eg.mts.gsuif.repository.WorkOrderRepository;
import eg.mts.gsuif.service.WorkOrderService;
import eg.mts.gsuif.security.EntityPermissionChecker;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;
import java.util.Set;

/**
 * Production implementation of {@link WorkOrderService}.
 *
 * <p>Adheres to:
 * <ul>
 *   <li>STD-15: {@link Loggable} on business methods for entry/exit/timing logs.
 *   <li>STD-11: Throws {@link ResourceNotFoundException} on non-existent IDs.
 *   <li>STD-28: Throws {@link DuplicateResourceException} on duplicate orderNumber.
 *   <li>STD-04: Returns {@link PagedBody} for paginated queries.
 * </ul>
 */
@Service
@Transactional(readOnly = true)
public class WorkOrderServiceImpl implements WorkOrderService {

    private final WorkOrderRepository workOrderRepository;
    private final EntityPermissionChecker permissionChecker;

    public WorkOrderServiceImpl(WorkOrderRepository workOrderRepository,
                                EntityPermissionChecker permissionChecker) {
        this.workOrderRepository = workOrderRepository;
        this.permissionChecker = permissionChecker;
    }

    @Override
    @Transactional
    @Loggable
    public WorkOrderDto create(CreateWorkOrderRequest request) {
        Authentication authentication = requireAccess();
        if (workOrderRepository.existsByOrderNumber(request.orderNumber())) {
            throw new DuplicateResourceException("Work order with order number '" + request.orderNumber() + "' already exists");
        }

        WorkOrder entity = new WorkOrder(
                request.orderNumber(),
                request.status(),
                request.dueDate(),
                request.assignedTo()
        );

        WorkOrder saved = workOrderRepository.save(entity);
        return toDto(saved, deniedFields(authentication));
    }

    @Override
    @Loggable
    public WorkOrderDto getById(UUID id) {
        Authentication authentication = requireAccess();
        return workOrderRepository.findById(id)
                .map(entity -> toDto(entity, deniedFields(authentication)))
                .orElseThrow(() -> new ResourceNotFoundException("Work order not found with id: " + id));
    }

    @Override
    @Loggable
    public PagedBody<WorkOrderDto> getAll(WorkOrderStatus status, Pageable pageable) {
        Authentication authentication = requireAccess();
        Set<String> deniedFields = deniedFields(authentication);
        Page<WorkOrder> page = (status != null)
                ? workOrderRepository.findByStatus(status, pageable)
                : workOrderRepository.findAll(pageable);

        return PagedBody.of(page.map(entity -> toDto(entity, deniedFields)));
    }

    @Override
    @Transactional
    @Loggable
    public WorkOrderDto update(UUID id, UpdateWorkOrderRequest request) {
        Authentication authentication = requireAccess();
        WorkOrder entity = workOrderRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Work order not found with id: " + id));

        if (!entity.getOrderNumber().equals(request.orderNumber())
                && workOrderRepository.existsByOrderNumber(request.orderNumber())) {
            throw new DuplicateResourceException(
                    "Work order with order number '" + request.orderNumber() + "' already exists"
            );
        }

        entity.setOrderNumber(request.orderNumber());
        entity.setStatus(request.status());
        entity.setDueDate(request.dueDate());
        entity.setAssignedTo(request.assignedTo());

        WorkOrder updated = workOrderRepository.save(entity);
        return toDto(updated, deniedFields(authentication));
    }

    @Override
    @Transactional
    @Loggable
    public void delete(UUID id) {
        requireAccess();
        if (!workOrderRepository.existsById(id)) {
            throw new ResourceNotFoundException("Work order not found with id: " + id);
        }
        workOrderRepository.deleteById(id);
    }

    private Authentication requireAccess() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        permissionChecker.requireEntityAccess("WorkOrder", authentication);
        return authentication;
    }

    private Set<String> deniedFields(Authentication authentication) {
        return permissionChecker.getDeniedFields("WorkOrder", authentication);
    }

    private WorkOrderDto toDto(WorkOrder entity, Set<String> deniedFields) {
        return new WorkOrderDto(
                deniedFields.contains("id") ? null : entity.getId(),
                deniedFields.contains("orderNumber") ? null : entity.getOrderNumber(),
                deniedFields.contains("status") ? null : entity.getStatus(),
                deniedFields.contains("dueDate") ? null : entity.getDueDate(),
                deniedFields.contains("assignedTo") ? null : entity.getAssignedTo(),
                deniedFields.contains("createdAt") ? null : entity.getCreatedAt(),
                deniedFields.contains("updatedAt") ? null : entity.getUpdatedAt(),
                deniedFields.contains("createdBy") ? null : entity.getCreatedBy(),
                deniedFields.contains("lastModifiedBy") ? null : entity.getLastModifiedBy()
        );
    }
}
