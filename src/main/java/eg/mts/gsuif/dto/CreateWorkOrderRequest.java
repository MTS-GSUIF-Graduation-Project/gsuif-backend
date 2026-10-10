package eg.mts.gsuif.dto;

import eg.mts.gsuif.entity.WorkOrderStatus;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDate;

/**
 * Request payload for creating a new {@link eg.mts.gsuif.entity.WorkOrder}.
 *
 * <p>Enforces Bean Validation constraints (STD-10).
 */
public record CreateWorkOrderRequest(
        @NotBlank(message = "Order number is required")
        @Size(max = 64, message = "Order number must not exceed 64 characters")
        @Schema(minLength = 1, description = "Required; must contain a non-whitespace character (Java Character.isWhitespace). Whitespace is not trimmed.", example = "WO-2026-0042")
        String orderNumber,

        @NotNull(message = "Status is required")
        @Schema(example = "OPEN")
        WorkOrderStatus status,

        @NotNull(message = "Due date is required")
        @Schema(example = "2026-10-15")
        LocalDate dueDate,

        @NotBlank(message = "Assigned user is required")
        @Size(max = 128, message = "Assigned user must not exceed 128 characters")
        @Schema(minLength = 1, description = "Required; must contain a non-whitespace character (Java Character.isWhitespace). Whitespace is not trimmed.", example = "maintenance.team")
        String assignedTo
) {
}
