package eg.mts.gsuif.dto;

import eg.mts.gsuif.entity.WorkOrderStatus;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;

/**
 * Request payload for updating an existing {@link eg.mts.gsuif.entity.WorkOrder}.
 *
 * <p>Enforces Bean Validation constraints (STD-10).
 */
public record UpdateWorkOrderRequest(
        @NotNull(message = "Status is required")
        WorkOrderStatus status,

        @NotNull(message = "Due date is required")
        LocalDate dueDate,

        @NotBlank(message = "Assigned user is required")
        @Size(max = 128, message = "Assigned user must not exceed 128 characters")
        String assignedTo
) {
}
