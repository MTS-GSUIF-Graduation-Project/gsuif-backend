package eg.mts.gsuif.controller;

import eg.mts.gsuif.dto.ApiResponse;
import eg.mts.gsuif.dto.CreateProjectRequest;
import eg.mts.gsuif.dto.PagedBody;
import eg.mts.gsuif.dto.ProjectDto;
import eg.mts.gsuif.dto.UpdateProjectRequest;
import eg.mts.gsuif.service.GsuifProjectService;
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
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * REST controller exposing CRUD operations for {@link eg.mts.gsuif.entity.GsuifProject}.
 */
@RestController
@RequestMapping("/api/v1/projects")
@Tag(name = "Projects", description = "Endpoints for managing GSUIF projects")
public class GsuifProjectController {

    private final GsuifProjectService gsuifProjectService;

    public GsuifProjectController(GsuifProjectService gsuifProjectService) {
        this.gsuifProjectService = gsuifProjectService;
    }

    @PostMapping
    @Operation(summary = "Create a new project", description = "Creates a new GSUIF project and returns HTTP 201", operationId = "createProject",
    responses = {
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "Project created successfully",
            links = {
                @io.swagger.v3.oas.annotations.links.Link(name = "GetProjectById", operationId = "getProjectById", parameters = @io.swagger.v3.oas.annotations.links.LinkParameter(name = "id", expression = "$response.body#/body/id")),
                @io.swagger.v3.oas.annotations.links.Link(name = "UpdateProject", operationId = "updateProject", parameters = @io.swagger.v3.oas.annotations.links.LinkParameter(name = "id", expression = "$response.body#/body/id")),
                @io.swagger.v3.oas.annotations.links.Link(name = "DeleteProject", operationId = "deleteProject", parameters = @io.swagger.v3.oas.annotations.links.LinkParameter(name = "id", expression = "$response.body#/body/id")),
                @io.swagger.v3.oas.annotations.links.Link(name = "GetPagesByProject", operationId = "getPagesByProject", parameters = @io.swagger.v3.oas.annotations.links.LinkParameter(name = "projectId", expression = "$response.body#/body/id")),
                @io.swagger.v3.oas.annotations.links.Link(name = "CreatePage", operationId = "createPage", parameters = @io.swagger.v3.oas.annotations.links.LinkParameter(name = "projectId", expression = "$response.body#/body/id"))
            })
    })
    @ApiCommonWriteResponses
    public ResponseEntity<ApiResponse<ProjectDto>> create(
            @Valid @RequestBody CreateProjectRequest request) {
        ProjectDto created = gsuifProjectService.create(request);
        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(ApiResponse.success(created, "Project created successfully", 201));
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get a project by ID", description = "Retrieves a single project by its unique UUID identifier", operationId = "getProjectById",
    responses = {
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Project retrieved successfully",
            links = {
                @io.swagger.v3.oas.annotations.links.Link(name = "GetProjectById", operationId = "getProjectById", parameters = @io.swagger.v3.oas.annotations.links.LinkParameter(name = "id", expression = "$response.body#/body/id")),
                @io.swagger.v3.oas.annotations.links.Link(name = "UpdateProject", operationId = "updateProject", parameters = @io.swagger.v3.oas.annotations.links.LinkParameter(name = "id", expression = "$response.body#/body/id")),
                @io.swagger.v3.oas.annotations.links.Link(name = "DeleteProject", operationId = "deleteProject", parameters = @io.swagger.v3.oas.annotations.links.LinkParameter(name = "id", expression = "$response.body#/body/id")),
                @io.swagger.v3.oas.annotations.links.Link(name = "GetPagesByProject", operationId = "getPagesByProject", parameters = @io.swagger.v3.oas.annotations.links.LinkParameter(name = "projectId", expression = "$response.body#/body/id")),
                @io.swagger.v3.oas.annotations.links.Link(name = "CreatePage", operationId = "createPage", parameters = @io.swagger.v3.oas.annotations.links.LinkParameter(name = "projectId", expression = "$response.body#/body/id"))
            }),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Project not found", content = @io.swagger.v3.oas.annotations.media.Content(schema = @io.swagger.v3.oas.annotations.media.Schema(implementation = eg.mts.gsuif.dto.ErrorApiResponse.class)))
    })
    @ApiCommonResponses
    public ResponseEntity<ApiResponse<ProjectDto>> getById(
            @Parameter(description = "Project UUID", required = true)
            @PathVariable("id") UUID id) {
        ProjectDto dto = gsuifProjectService.getById(id);
        return ResponseEntity.ok(ApiResponse.success(dto, "Project retrieved successfully"));
    }

    @GetMapping
    @Operation(summary = "List projects with pagination", description = "Retrieves a paginated list of projects", operationId = "getProjects",
    responses = {
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Projects retrieved successfully")
    })
    @ApiCommonResponses
    public ResponseEntity<ApiResponse<PagedBody<ProjectDto>>> getAll(
            @PageableDefault(size = 20, sort = "createdAt", direction = Sort.Direction.DESC) Pageable pageable) {
        PagedBody<ProjectDto> pagedBody = gsuifProjectService.getAll(pageable);
        return ResponseEntity.ok(ApiResponse.page(pagedBody, "Projects retrieved successfully"));
    }

    @PutMapping("/{id}")
    @Operation(summary = "Update an existing project", description = "Updates name and description of an existing project", operationId = "updateProject",
    responses = {
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Project updated successfully",
            links = {
                @io.swagger.v3.oas.annotations.links.Link(name = "GetProjectById", operationId = "getProjectById", parameters = @io.swagger.v3.oas.annotations.links.LinkParameter(name = "id", expression = "$response.body#/body/id")),
                @io.swagger.v3.oas.annotations.links.Link(name = "UpdateProject", operationId = "updateProject", parameters = @io.swagger.v3.oas.annotations.links.LinkParameter(name = "id", expression = "$response.body#/body/id")),
                @io.swagger.v3.oas.annotations.links.Link(name = "DeleteProject", operationId = "deleteProject", parameters = @io.swagger.v3.oas.annotations.links.LinkParameter(name = "id", expression = "$response.body#/body/id")),
                @io.swagger.v3.oas.annotations.links.Link(name = "GetPagesByProject", operationId = "getPagesByProject", parameters = @io.swagger.v3.oas.annotations.links.LinkParameter(name = "projectId", expression = "$response.body#/body/id")),
                @io.swagger.v3.oas.annotations.links.Link(name = "CreatePage", operationId = "createPage", parameters = @io.swagger.v3.oas.annotations.links.LinkParameter(name = "projectId", expression = "$response.body#/body/id"))
            }),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Project not found", content = @io.swagger.v3.oas.annotations.media.Content(schema = @io.swagger.v3.oas.annotations.media.Schema(implementation = eg.mts.gsuif.dto.ErrorApiResponse.class)))
    })
    @ApiCommonWriteResponses
    public ResponseEntity<ApiResponse<ProjectDto>> update(
            @Parameter(description = "Project UUID", required = true)
            @PathVariable("id") UUID id,
            @Valid @RequestBody UpdateProjectRequest request) {
        ProjectDto updated = gsuifProjectService.update(id, request);
        return ResponseEntity.ok(ApiResponse.success(updated, "Project updated successfully"));
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "Delete a project", description = "Deletes a project by its unique UUID identifier if it contains no pages", operationId = "deleteProject",
    responses = {
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Project deleted successfully"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Project not found", content = @io.swagger.v3.oas.annotations.media.Content(schema = @io.swagger.v3.oas.annotations.media.Schema(implementation = eg.mts.gsuif.dto.ErrorApiResponse.class)))
    })
    @ApiCommonResponses
    public ResponseEntity<ApiResponse<Void>> delete(
            @Parameter(description = "Project UUID", required = true)
            @PathVariable("id") UUID id) {
        gsuifProjectService.delete(id);
        return ResponseEntity.ok(ApiResponse.success(null, "Project deleted successfully"));
    }
}
