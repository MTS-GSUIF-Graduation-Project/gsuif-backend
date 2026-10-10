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
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

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

    public WorkOrderServiceImpl(WorkOrderRepository workOrderRepository) {
        this.workOrderRepository = workOrderRepository;
    }

    @Override
    @Transactional
    @Loggable
    public WorkOrderDto create(CreateWorkOrderRequest request) {
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
        return toDto(saved);
    }

    @Override
    @Loggable
    public WorkOrderDto getById(UUID id) {
        return workOrderRepository.findById(id)
                .map(this::toDto)
                .orElseThrow(() -> new ResourceNotFoundException("Work order not found with id: " + id));
    }

    @Override
    @Loggable
    public PagedBody<WorkOrderDto> getAll(WorkOrderStatus status, Pageable pageable) {
        Page<WorkOrder> page = (status != null)
                ? workOrderRepository.findByStatus(status, pageable)
                : workOrderRepository.findAll(pageable);

        return PagedBody.of(page.map(this::toDto));
    }

    @Override
    @Transactional
    @Loggable
    public WorkOrderDto update(UUID id, UpdateWorkOrderRequest request) {
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
        return toDto(updated);
    }

    @Override
    @Transactional
    @Loggable
    public void delete(UUID id) {
        if (!workOrderRepository.existsById(id)) {
            throw new ResourceNotFoundException("Work order not found with id: " + id);
        }
        workOrderRepository.deleteById(id);
    }

    private WorkOrderDto toDto(WorkOrder entity) {
        return new WorkOrderDto(
                entity.getId(),
                entity.getOrderNumber(),
                entity.getStatus(),
                entity.getDueDate(),
                entity.getAssignedTo(),
                entity.getCreatedAt(),
                entity.getUpdatedAt(),
                entity.getCreatedBy(),
                entity.getLastModifiedBy()
        );
    }
}
