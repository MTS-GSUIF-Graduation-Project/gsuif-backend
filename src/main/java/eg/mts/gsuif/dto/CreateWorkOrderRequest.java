package eg.mts.gsuif.dto;

import eg.mts.gsuif.entity.WorkOrderStatus;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;

/**
 * Request payload for creating a new {@link eg.mts.gsuif.entity.WorkOrder}.
 *
 * <p>Enforces Bean Validation constraints (STD-10).
 */
public record CreateWorkOrderRequest(
        @NotBlank(message = "Order number is required")
        @Size(max = 64, message = "Order number must not exceed 64 characters")
        String orderNumber,

        @NotNull(message = "Status is required")
        WorkOrderStatus status,

        @NotNull(message = "Due date is required")
        LocalDate dueDate,

        @NotBlank(message = "Assigned user is required")
        @Size(max = 128, message = "Assigned user must not exceed 128 characters")
        String assignedTo
) {
}
