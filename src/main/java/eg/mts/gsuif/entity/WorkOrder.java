package eg.mts.gsuif.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;

/**
 * Core reference entity representing a WorkOrder.
 *
 * <p>Adheres to:
 * <ul>
 *   <li>STD-35: UUID primary key.
 *   <li>STD-33: Database-agnostic JPA mappings.
 *   <li>STD-24: Audit fields inherited from {@link AuditableEntity}.
 * </ul>
 */
@Entity
@Table(name = "work_orders")
public class WorkOrder extends AuditableEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "order_number", nullable = false, unique = true, length = 64)
    private String orderNumber;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    private WorkOrderStatus status;

    @Column(name = "due_date", nullable = false)
    private LocalDate dueDate;

    @Column(name = "assigned_to", nullable = false, length = 128)
    private String assignedTo;

    public WorkOrder() {
    }

    public WorkOrder(String orderNumber, WorkOrderStatus status, LocalDate dueDate, String assignedTo) {
        this.orderNumber = orderNumber;
        this.status = status;
        this.dueDate = dueDate;
        this.assignedTo = assignedTo;
    }

    public UUID getId() {
        return id;
    }

     /**
     * Protected to prevent external mutation of the generated identifier while
     * remaining accessible to testing utilities or frameworks if needed.
     */
    protected void setId(UUID id) {
        this.id = id;
    }

    public String getOrderNumber() {
        return orderNumber;
    }

    public void setOrderNumber(String orderNumber) {
        this.orderNumber = orderNumber;
    }

    public WorkOrderStatus getStatus() {
        return status;
    }

    public void setStatus(WorkOrderStatus status) {
        this.status = status;
    }

    public LocalDate getDueDate() {
        return dueDate;
    }

    public void setDueDate(LocalDate dueDate) {
        this.dueDate = dueDate;
    }

    public String getAssignedTo() {
        return assignedTo;
    }

    public void setAssignedTo(String assignedTo) {
        this.assignedTo = assignedTo;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof WorkOrder that)) return false;
        return id != null && id.equals(that.id);
    }
    @Override
    public int hashCode() {
        return getClass().hashCode();
    }
}
