package eg.mts.gsuif.controller;

import eg.mts.gsuif.dto.ApiResponse;
import eg.mts.gsuif.dto.CreatePageRequest;
import eg.mts.gsuif.dto.PageDto;
import eg.mts.gsuif.dto.PagedBody;
import eg.mts.gsuif.dto.UpdatePageRequest;
import eg.mts.gsuif.service.GsuifPageService;
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
 * REST controller exposing CRUD operations for {@link eg.mts.gsuif.entity.GsuifPage}
 * scoped to a parent {@link eg.mts.gsuif.entity.GsuifProject}.
 */
@RestController
@RequestMapping("/api/v1/projects/{projectId}/pages")
@Tag(name = "Pages", description = "Endpoints for managing pages within a GSUIF project")
public class GsuifPageController {

    private final GsuifPageService gsuifPageService;

    public GsuifPageController(GsuifPageService gsuifPageService) {
        this.gsuifPageService = gsuifPageService;
    }

    @PostMapping
    @Operation(summary = "Create a new page", description = "Creates a new page within the specified project and returns HTTP 201", operationId = "createPage",
    responses = {
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "Page created successfully",
            links = {
                @io.swagger.v3.oas.annotations.links.Link(name = "GetPageById", operationId = "getPageById", parameters = {@io.swagger.v3.oas.annotations.links.LinkParameter(name = "projectId", expression = "$response.body#/body/projectId"), @io.swagger.v3.oas.annotations.links.LinkParameter(name = "pageId", expression = "$response.body#/body/id")}),
                @io.swagger.v3.oas.annotations.links.Link(name = "UpdatePage", operationId = "updatePage", parameters = {@io.swagger.v3.oas.annotations.links.LinkParameter(name = "projectId", expression = "$response.body#/body/projectId"), @io.swagger.v3.oas.annotations.links.LinkParameter(name = "pageId", expression = "$response.body#/body/id")}),
                @io.swagger.v3.oas.annotations.links.Link(name = "DeletePage", operationId = "deletePage", parameters = {@io.swagger.v3.oas.annotations.links.LinkParameter(name = "projectId", expression = "$response.body#/body/projectId"), @io.swagger.v3.oas.annotations.links.LinkParameter(name = "pageId", expression = "$response.body#/body/id")}),
                @io.swagger.v3.oas.annotations.links.Link(name = "GetMetadataVersionsByPage", operationId = "getMetadataVersionsByPage", parameters = @io.swagger.v3.oas.annotations.links.LinkParameter(name = "pageId", expression = "$response.body#/body/id")),
                @io.swagger.v3.oas.annotations.links.Link(name = "CreateMetadataVersion", operationId = "createMetadataVersion", parameters = @io.swagger.v3.oas.annotations.links.LinkParameter(name = "pageId", expression = "$response.body#/body/id"))
            }),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Project not found", content = @io.swagger.v3.oas.annotations.media.Content(schema = @io.swagger.v3.oas.annotations.media.Schema(implementation = eg.mts.gsuif.dto.ErrorApiResponse.class)))
    })
    @ApiCommonWriteResponses
    public ResponseEntity<ApiResponse<PageDto>> create(
            @Parameter(description = "Project UUID", required = true)
            @PathVariable("projectId") UUID projectId,
            @Valid @RequestBody CreatePageRequest request) {
        PageDto created = gsuifPageService.create(projectId, request);
        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(ApiResponse.success(created, "Page created successfully", 201));
    }

    @GetMapping
    @Operation(summary = "List pages with pagination", description = "Retrieves a paginated list of pages for the specified project", operationId = "getPagesByProject",
    responses = {
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Pages retrieved successfully"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Project not found", content = @io.swagger.v3.oas.annotations.media.Content(schema = @io.swagger.v3.oas.annotations.media.Schema(implementation = eg.mts.gsuif.dto.ErrorApiResponse.class)))
    })
    @ApiCommonResponses
    public ResponseEntity<ApiResponse<PagedBody<PageDto>>> getAll(
            @Parameter(description = "Project UUID", required = true)
            @PathVariable("projectId") UUID projectId,
            @PageableDefault(size = 20, sort = "createdAt", direction = Sort.Direction.DESC) Pageable pageable) {
        PagedBody<PageDto> pagedBody = gsuifPageService.getAll(projectId, pageable);
        return ResponseEntity.ok(ApiResponse.page(pagedBody, "Pages retrieved successfully"));
    }

    @GetMapping("/{pageId}")
    @Operation(summary = "Get a page by ID", description = "Retrieves a single page by its UUID, scoped to the specified project", operationId = "getPageById",
    responses = {
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Page retrieved successfully",
            links = {
                @io.swagger.v3.oas.annotations.links.Link(name = "GetPageById", operationId = "getPageById", parameters = {@io.swagger.v3.oas.annotations.links.LinkParameter(name = "projectId", expression = "$response.body#/body/projectId"), @io.swagger.v3.oas.annotations.links.LinkParameter(name = "pageId", expression = "$response.body#/body/id")}),
                @io.swagger.v3.oas.annotations.links.Link(name = "UpdatePage", operationId = "updatePage", parameters = {@io.swagger.v3.oas.annotations.links.LinkParameter(name = "projectId", expression = "$response.body#/body/projectId"), @io.swagger.v3.oas.annotations.links.LinkParameter(name = "pageId", expression = "$response.body#/body/id")}),
                @io.swagger.v3.oas.annotations.links.Link(name = "DeletePage", operationId = "deletePage", parameters = {@io.swagger.v3.oas.annotations.links.LinkParameter(name = "projectId", expression = "$response.body#/body/projectId"), @io.swagger.v3.oas.annotations.links.LinkParameter(name = "pageId", expression = "$response.body#/body/id")}),
                @io.swagger.v3.oas.annotations.links.Link(name = "GetMetadataVersionsByPage", operationId = "getMetadataVersionsByPage", parameters = @io.swagger.v3.oas.annotations.links.LinkParameter(name = "pageId", expression = "$response.body#/body/id")),
                @io.swagger.v3.oas.annotations.links.Link(name = "CreateMetadataVersion", operationId = "createMetadataVersion", parameters = @io.swagger.v3.oas.annotations.links.LinkParameter(name = "pageId", expression = "$response.body#/body/id"))
            }),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Project or page not found", content = @io.swagger.v3.oas.annotations.media.Content(schema = @io.swagger.v3.oas.annotations.media.Schema(implementation = eg.mts.gsuif.dto.ErrorApiResponse.class)))
    })
    @ApiCommonResponses
    public ResponseEntity<ApiResponse<PageDto>> getById(
            @Parameter(description = "Project UUID", required = true)
            @PathVariable("projectId") UUID projectId,
            @Parameter(description = "Page UUID", required = true)
            @PathVariable("pageId") UUID pageId) {
        PageDto dto = gsuifPageService.getById(projectId, pageId);
        return ResponseEntity.ok(ApiResponse.success(dto, "Page retrieved successfully"));
    }

    @PutMapping("/{pageId}")
    @Operation(summary = "Update an existing page", description = "Updates name and route of an existing page within the specified project", operationId = "updatePage",
    responses = {
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Page updated successfully",
            links = {
                @io.swagger.v3.oas.annotations.links.Link(name = "GetPageById", operationId = "getPageById", parameters = {@io.swagger.v3.oas.annotations.links.LinkParameter(name = "projectId", expression = "$response.body#/body/projectId"), @io.swagger.v3.oas.annotations.links.LinkParameter(name = "pageId", expression = "$response.body#/body/id")}),
                @io.swagger.v3.oas.annotations.links.Link(name = "UpdatePage", operationId = "updatePage", parameters = {@io.swagger.v3.oas.annotations.links.LinkParameter(name = "projectId", expression = "$response.body#/body/projectId"), @io.swagger.v3.oas.annotations.links.LinkParameter(name = "pageId", expression = "$response.body#/body/id")}),
                @io.swagger.v3.oas.annotations.links.Link(name = "DeletePage", operationId = "deletePage", parameters = {@io.swagger.v3.oas.annotations.links.LinkParameter(name = "projectId", expression = "$response.body#/body/projectId"), @io.swagger.v3.oas.annotations.links.LinkParameter(name = "pageId", expression = "$response.body#/body/id")}),
                @io.swagger.v3.oas.annotations.links.Link(name = "GetMetadataVersionsByPage", operationId = "getMetadataVersionsByPage", parameters = @io.swagger.v3.oas.annotations.links.LinkParameter(name = "pageId", expression = "$response.body#/body/id")),
                @io.swagger.v3.oas.annotations.links.Link(name = "CreateMetadataVersion", operationId = "createMetadataVersion", parameters = @io.swagger.v3.oas.annotations.links.LinkParameter(name = "pageId", expression = "$response.body#/body/id"))
            }),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Project or page not found", content = @io.swagger.v3.oas.annotations.media.Content(schema = @io.swagger.v3.oas.annotations.media.Schema(implementation = eg.mts.gsuif.dto.ErrorApiResponse.class)))
    })
    @ApiCommonWriteResponses
    public ResponseEntity<ApiResponse<PageDto>> update(
            @Parameter(description = "Project UUID", required = true)
            @PathVariable("projectId") UUID projectId,
            @Parameter(description = "Page UUID", required = true)
            @PathVariable("pageId") UUID pageId,
            @Valid @RequestBody UpdatePageRequest request) {
        PageDto updated = gsuifPageService.update(projectId, pageId, request);
        return ResponseEntity.ok(ApiResponse.success(updated, "Page updated successfully"));
    }

    @DeleteMapping("/{pageId}")
    @Operation(summary = "Delete a page", description = "Deletes a page within the specified project if it has no metadata versions", operationId = "deletePage",
    responses = {
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Page deleted successfully"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Project or page not found", content = @io.swagger.v3.oas.annotations.media.Content(schema = @io.swagger.v3.oas.annotations.media.Schema(implementation = eg.mts.gsuif.dto.ErrorApiResponse.class)))
    })
    @ApiCommonResponses
    public ResponseEntity<ApiResponse<Void>> delete(
            @Parameter(description = "Project UUID", required = true)
            @PathVariable("projectId") UUID projectId,
            @Parameter(description = "Page UUID", required = true)
            @PathVariable("pageId") UUID pageId) {
        gsuifPageService.delete(projectId, pageId);
        return ResponseEntity.ok(ApiResponse.success(null, "Page deleted successfully"));
    }
}
