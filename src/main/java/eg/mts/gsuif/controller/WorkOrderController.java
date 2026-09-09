package eg.mts.gsuif.controller;

import eg.mts.gsuif.dto.ApiResponse;
import eg.mts.gsuif.dto.CreateWorkOrderRequest;
import eg.mts.gsuif.dto.PagedBody;
import eg.mts.gsuif.dto.UpdateWorkOrderRequest;
import eg.mts.gsuif.dto.WorkOrderDto;
import eg.mts.gsuif.entity.WorkOrderStatus;
import eg.mts.gsuif.service.WorkOrderService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * REST controller exposing CRUD operations for {@link eg.mts.gsuif.entity.WorkOrder}.
 *
 * <p>Adheres to:
 * <ul>
 *   <li>STD-01..03: Standard 5-field {@link ApiResponse} envelope.
 *   <li>STD-04: Nested {@link PagedBody} for paginated queries.
 *   <li>STD-05: Returns {@code ResponseEntity<ApiResponse<T>>} on every endpoint.
 *   <li>STD-10: {@code @Valid} triggers Bean Validation.
 *   <li>STD-27: Springdoc OpenAPI {@link Tag} and {@link Operation} annotations.
 *   <li>STD-28: HTTP 201 for POST create, HTTP 200 for GET/PUT/DELETE.
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/work-orders")
@Tag(name = "Work Orders", description = "Endpoints for managing work orders")
public class WorkOrderController {

    private final WorkOrderService workOrderService;

    public WorkOrderController(WorkOrderService workOrderService) {
        this.workOrderService = workOrderService;
    }

    @PostMapping
    @Operation(summary = "Create a new work order", description = "Creates a new work order and returns the created record with HTTP 201")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "Work order created successfully"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "Validation failed or duplicate order number")
    })
    public ResponseEntity<ApiResponse<WorkOrderDto>> create(
            @Valid @RequestBody CreateWorkOrderRequest request) {
        WorkOrderDto created = workOrderService.create(request);
        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(ApiResponse.success(created, "Work order created successfully", 201));
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get a work order by ID", description = "Retrieves a single work order by its unique UUID identifier")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Work order found"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Work order not found")
    })
    public ResponseEntity<ApiResponse<WorkOrderDto>> getById(
            @Parameter(description = "Work order UUID", required = true)
            @PathVariable("id") UUID id) {
        WorkOrderDto dto = workOrderService.getById(id);
        return ResponseEntity.ok(ApiResponse.success(dto, "Work order retrieved successfully"));
    }

    @GetMapping
    @Operation(summary = "List work orders with pagination", description = "Retrieves a paginated list of work orders with optional status filtering")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Work orders retrieved successfully")
    })
    public ResponseEntity<ApiResponse<PagedBody<WorkOrderDto>>> getAll(
            @Parameter(description = "Optional status filter")
            @RequestParam(name = "status", required = false) WorkOrderStatus status,
            @PageableDefault(size = 10, sort = "createdAt", direction = Sort.Direction.DESC) Pageable pageable) {
        PagedBody<WorkOrderDto> pagedBody = workOrderService.getAll(status, pageable);
        return ResponseEntity.ok(ApiResponse.page(pagedBody, "Work orders retrieved successfully"));
    }

    @PutMapping("/{id}")
    @Operation(summary = "Update an existing work order", description = "Updates mutable fields (status, dueDate, assignedTo) of an existing work order")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Work order updated successfully"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "Validation failed"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Work order not found")
    })
    public ResponseEntity<ApiResponse<WorkOrderDto>> update(
            @Parameter(description = "Work order UUID", required = true)
            @PathVariable("id") UUID id,
            @Valid @RequestBody UpdateWorkOrderRequest request) {
        WorkOrderDto updated = workOrderService.update(id, request);
        return ResponseEntity.ok(ApiResponse.success(updated, "Work order updated successfully"));
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "Delete a work order", description = "Deletes a work order by its unique UUID identifier")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Work order deleted successfully"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Work order not found")
    })
    public ResponseEntity<ApiResponse<Void>> delete(
            @Parameter(description = "Work order UUID", required = true)
            @PathVariable("id") UUID id) {
        workOrderService.delete(id);
        return ResponseEntity.ok(ApiResponse.success(null, "Work order deleted successfully"));
    }
}
