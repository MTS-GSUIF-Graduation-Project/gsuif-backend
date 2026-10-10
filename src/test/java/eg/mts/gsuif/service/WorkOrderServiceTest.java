package eg.mts.gsuif.service;

import eg.mts.gsuif.dto.CreateWorkOrderRequest;
import eg.mts.gsuif.dto.PagedBody;
import eg.mts.gsuif.dto.UpdateWorkOrderRequest;
import eg.mts.gsuif.dto.WorkOrderDto;
import eg.mts.gsuif.entity.WorkOrder;
import eg.mts.gsuif.entity.WorkOrderStatus;
import eg.mts.gsuif.exception.DuplicateResourceException;
import eg.mts.gsuif.exception.ResourceNotFoundException;
import eg.mts.gsuif.repository.WorkOrderRepository;
import eg.mts.gsuif.service.impl.WorkOrderServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WorkOrderServiceTest {

    @Mock
    private WorkOrderRepository repository;

    private WorkOrderService service;

    @BeforeEach
    void setUp() {
        service = new WorkOrderServiceImpl(repository);
    }

    @Test
    void create_whenOrderNumberUnique_savesAndReturnsDto() {
        CreateWorkOrderRequest request = new CreateWorkOrderRequest(
                "WO-101",
                WorkOrderStatus.OPEN,
                LocalDate.of(2026, 9, 15),
                "esraa.abdelrazek"
        );
        WorkOrder entity = new WorkOrder(request.orderNumber(), request.status(), request.dueDate(), request.assignedTo());

        when(repository.existsByOrderNumber("WO-101")).thenReturn(false);
        when(repository.save(any(WorkOrder.class))).thenReturn(entity);

        WorkOrderDto result = service.create(request);

        assertThat(result.orderNumber()).isEqualTo("WO-101");
        assertThat(result.status()).isEqualTo(WorkOrderStatus.OPEN);
        assertThat(result.dueDate()).isEqualTo(LocalDate.of(2026, 9, 15));
        assertThat(result.assignedTo()).isEqualTo("esraa.abdelrazek");

        ArgumentCaptor<WorkOrder> captor = ArgumentCaptor.forClass(WorkOrder.class);
        verify(repository).save(captor.capture());
        assertThat(captor.getValue().getOrderNumber()).isEqualTo("WO-101");
    }

    @Test
    void create_whenOrderNumberExists_throwsDuplicateResourceException() {
        CreateWorkOrderRequest request = new CreateWorkOrderRequest(
                "WO-DUPLICATE",
                WorkOrderStatus.OPEN,
                LocalDate.now(),
                "user"
        );

        when(repository.existsByOrderNumber("WO-DUPLICATE")).thenReturn(true);

        assertThatThrownBy(() -> service.create(request))
                .isInstanceOf(DuplicateResourceException.class)
                .hasMessage("Work order with order number 'WO-DUPLICATE' already exists");

        verify(repository, never()).save(any());
    }

    @Test
    void getById_whenFound_returnsDto() {
        UUID id = UUID.randomUUID();
        WorkOrder entity = new WorkOrder("WO-102", WorkOrderStatus.IN_PROGRESS, LocalDate.now(), "user");

        when(repository.findById(id)).thenReturn(Optional.of(entity));

        WorkOrderDto result = service.getById(id);

        assertThat(result.orderNumber()).isEqualTo("WO-102");
        assertThat(result.status()).isEqualTo(WorkOrderStatus.IN_PROGRESS);
    }

    @Test
    void getById_whenNotFound_throwsResourceNotFoundException() {
        UUID id = UUID.randomUUID();
        when(repository.findById(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getById(id))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessage("Work order not found with id: " + id);
    }

    @Test
    void getAll_withStatusFilter_returnsFilteredPagedBody() {
        Pageable pageable = PageRequest.of(0, 10);
        WorkOrder entity = new WorkOrder("WO-103", WorkOrderStatus.OPEN, LocalDate.now(), "user");
        when(repository.findByStatus(WorkOrderStatus.OPEN, pageable))
                .thenReturn(new PageImpl<>(List.of(entity), pageable, 1));

        PagedBody<WorkOrderDto> result = service.getAll(WorkOrderStatus.OPEN, pageable);

        assertThat(result.data()).hasSize(1);
        assertThat(result.totalElements()).isEqualTo(1);
        verify(repository).findByStatus(WorkOrderStatus.OPEN, pageable);
    }

    @Test
    void getAll_withoutStatusFilter_returnsAllPagedBody() {
        Pageable pageable = PageRequest.of(0, 10);
        WorkOrder entity = new WorkOrder("WO-103", WorkOrderStatus.OPEN, LocalDate.now(), "user");
        when(repository.findAll(pageable))
                .thenReturn(new PageImpl<>(List.of(entity), pageable, 1));

        PagedBody<WorkOrderDto> result = service.getAll(null, pageable);

        assertThat(result.data()).hasSize(1);
        assertThat(result.totalElements()).isEqualTo(1);
        verify(repository).findAll(pageable);
    }

    @Test
    void update_whenSameOrderNumber_updatesOtherFieldsAndReturnsDto() {
        UUID id = UUID.randomUUID();
        WorkOrder entity = new WorkOrder("WO-104", WorkOrderStatus.OPEN, LocalDate.of(2026, 9, 1), "old.user");
        UpdateWorkOrderRequest request = new UpdateWorkOrderRequest(
                "WO-104", // Same order number
                WorkOrderStatus.COMPLETED,
                LocalDate.of(2026, 9, 20),
                "new.user"
        );

        when(repository.findById(id)).thenReturn(Optional.of(entity));
        when(repository.save(any(WorkOrder.class))).thenReturn(entity);

        WorkOrderDto result = service.update(id, request);

        assertThat(result.orderNumber()).isEqualTo("WO-104");
        assertThat(result.status()).isEqualTo(WorkOrderStatus.COMPLETED);
        assertThat(result.dueDate()).isEqualTo(LocalDate.of(2026, 9, 20));
        assertThat(result.assignedTo()).isEqualTo("new.user");

        assertThat(entity.getOrderNumber()).isEqualTo("WO-104");
        assertThat(entity.getStatus()).isEqualTo(WorkOrderStatus.COMPLETED);
        assertThat(entity.getDueDate()).isEqualTo(LocalDate.of(2026, 9, 20));
        assertThat(entity.getAssignedTo()).isEqualTo("new.user");
        verify(repository, never()).existsByOrderNumber(any());
        verify(repository).save(entity);
    }

    @Test
    void update_whenNewUnusedOrderNumber_updatesAllFieldsAndReturnsDto() {
        UUID id = UUID.randomUUID();
        WorkOrder entity = new WorkOrder("WO-104", WorkOrderStatus.OPEN, LocalDate.of(2026, 9, 1), "old.user");
        UpdateWorkOrderRequest request = new UpdateWorkOrderRequest(
                "WO-105", // New order number
                WorkOrderStatus.COMPLETED,
                LocalDate.of(2026, 9, 20),
                "new.user"
        );

        when(repository.findById(id)).thenReturn(Optional.of(entity));
        when(repository.existsByOrderNumber("WO-105")).thenReturn(false);
        when(repository.save(any(WorkOrder.class))).thenReturn(entity);

        WorkOrderDto result = service.update(id, request);

        assertThat(result.orderNumber()).isEqualTo("WO-105");
        assertThat(result.status()).isEqualTo(WorkOrderStatus.COMPLETED);
        assertThat(result.dueDate()).isEqualTo(LocalDate.of(2026, 9, 20));
        assertThat(result.assignedTo()).isEqualTo("new.user");

        assertThat(entity.getOrderNumber()).isEqualTo("WO-105");
        assertThat(entity.getStatus()).isEqualTo(WorkOrderStatus.COMPLETED);
        assertThat(entity.getDueDate()).isEqualTo(LocalDate.of(2026, 9, 20));
        assertThat(entity.getAssignedTo()).isEqualTo("new.user");
        verify(repository).save(entity);
    }

    @Test
    void update_whenNewDuplicateOrderNumber_throwsDuplicateResourceException() {
        UUID id = UUID.randomUUID();
        WorkOrder entity = new WorkOrder("WO-104", WorkOrderStatus.OPEN, LocalDate.of(2026, 9, 1), "old.user");
        UpdateWorkOrderRequest request = new UpdateWorkOrderRequest(
                "WO-105", // Duplicate order number
                WorkOrderStatus.COMPLETED,
                LocalDate.of(2026, 9, 20),
                "new.user"
        );

        when(repository.findById(id)).thenReturn(Optional.of(entity));
        when(repository.existsByOrderNumber("WO-105")).thenReturn(true);

        assertThatThrownBy(() -> service.update(id, request))
                .isInstanceOf(DuplicateResourceException.class)
                .hasMessage("Work order with order number 'WO-105' already exists");

        verify(repository, never()).save(any());
    }

    @Test
    void update_whenNotFound_throwsResourceNotFoundException() {
        UUID id = UUID.randomUUID();
        UpdateWorkOrderRequest request = new UpdateWorkOrderRequest(
                "WO-105",
                WorkOrderStatus.COMPLETED,
                LocalDate.now(),
                "user"
        );

        when(repository.findById(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.update(id, request))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessage("Work order not found with id: " + id);

        verify(repository, never()).save(any());
    }

    @Test
    void delete_whenFound_deletes() {
        UUID id = UUID.randomUUID();
        when(repository.existsById(id)).thenReturn(true);

        service.delete(id);

        verify(repository).deleteById(id);
    }

    @Test
    void delete_whenNotFound_throwsResourceNotFoundException() {
        UUID id = UUID.randomUUID();
        when(repository.existsById(id)).thenReturn(false);

        assertThatThrownBy(() -> service.delete(id))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessage("Work order not found with id: " + id);

        verify(repository, never()).deleteById(any());
    }
}
