package eg.mts.gsuif.dto;

import eg.mts.gsuif.entity.WorkOrderStatus;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Immutable DTO representing a WorkOrder response.
 *
 * <p>Used as the {@code body} inside {@link ApiResponse}.
 */
public record WorkOrderDto(
        UUID id,
        String orderNumber,
        WorkOrderStatus status,
        LocalDate dueDate,
        String assignedTo,
        Instant createdAt,
        Instant updatedAt,
        String createdBy,
        String lastModifiedBy
) {
}
