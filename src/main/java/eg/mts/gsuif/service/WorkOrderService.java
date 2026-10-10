package eg.mts.gsuif.service;

import eg.mts.gsuif.dto.CreateWorkOrderRequest;
import eg.mts.gsuif.dto.PagedBody;
import eg.mts.gsuif.dto.UpdateWorkOrderRequest;
import eg.mts.gsuif.dto.WorkOrderDto;
import eg.mts.gsuif.entity.WorkOrderStatus;
import org.springframework.data.domain.Pageable;

import java.util.UUID;

/**
 * Service contract for {@link eg.mts.gsuif.entity.WorkOrder} operations.
 */
public interface WorkOrderService {

    WorkOrderDto create(CreateWorkOrderRequest request);

    WorkOrderDto getById(UUID id);

    PagedBody<WorkOrderDto> getAll(WorkOrderStatus status, Pageable pageable);

    WorkOrderDto update(UUID id, UpdateWorkOrderRequest request);

    void delete(UUID id);
}
