package eg.mts.gsuif.controller;

import eg.mts.gsuif.dto.ApiResponse;
import eg.mts.gsuif.dto.CreateMetadataVersionRequest;
import eg.mts.gsuif.dto.MetadataVersionDto;
import eg.mts.gsuif.dto.PagedBody;
import eg.mts.gsuif.service.MetadataVersionService;
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
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * REST controller for managing {@link eg.mts.gsuif.entity.MetadataVersion}.
 */
@RestController
@RequestMapping("/api/v1/pages/{pageId}/metadata")
@Tag(name = "Metadata Versions", description = "Endpoints for managing page metadata versions")
public class MetadataVersionController {

    private final MetadataVersionService metadataVersionService;

    public MetadataVersionController(MetadataVersionService metadataVersionService) {
        this.metadataVersionService = metadataVersionService;
    }

    @PostMapping
    @Operation(summary = "Create a new metadata version", description = "Creates a new metadata version for the specified page and returns HTTP 201")
    @ApiCommonWriteResponses
    @io.swagger.v3.oas.annotations.responses.ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "Metadata version created successfully"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Page not found", content = @io.swagger.v3.oas.annotations.media.Content(schema = @io.swagger.v3.oas.annotations.media.Schema(implementation = eg.mts.gsuif.dto.ErrorApiResponse.class)))
    })
    public ResponseEntity<ApiResponse<MetadataVersionDto>> create(
            @Parameter(description = "Page UUID", required = true)
            @PathVariable("pageId") UUID pageId,
            @Valid @RequestBody CreateMetadataVersionRequest request) {
        MetadataVersionDto created = metadataVersionService.create(pageId, request);
        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(ApiResponse.success(created, "Metadata version created successfully", 201));
    }

    @GetMapping
    @Operation(summary = "List metadata versions", description = "Retrieves a paginated list of metadata versions for the specified page")
    @ApiCommonResponses
    @io.swagger.v3.oas.annotations.responses.ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Versions retrieved successfully"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Page not found", content = @io.swagger.v3.oas.annotations.media.Content(schema = @io.swagger.v3.oas.annotations.media.Schema(implementation = eg.mts.gsuif.dto.ErrorApiResponse.class)))
    })
    public ResponseEntity<ApiResponse<PagedBody<MetadataVersionDto>>> getAll(
            @Parameter(description = "Page UUID", required = true)
            @PathVariable("pageId") UUID pageId,
            @PageableDefault(size = 20, sort = "version", direction = Sort.Direction.DESC) Pageable pageable) {
        PagedBody<MetadataVersionDto> pagedBody = metadataVersionService.getAll(pageId, pageable);
        return ResponseEntity.ok(ApiResponse.page(pagedBody, "Metadata versions retrieved successfully"));
    }

    @GetMapping("/latest")
    @Operation(summary = "Get the latest metadata version", description = "Retrieves the highest sequential version for the page")
    @ApiCommonResponses
    @io.swagger.v3.oas.annotations.responses.ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Version retrieved successfully"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Page not found or no versions exist", content = @io.swagger.v3.oas.annotations.media.Content(schema = @io.swagger.v3.oas.annotations.media.Schema(implementation = eg.mts.gsuif.dto.ErrorApiResponse.class)))
    })
    public ResponseEntity<ApiResponse<MetadataVersionDto>> getLatest(
            @Parameter(description = "Page UUID", required = true)
            @PathVariable("pageId") UUID pageId) {
        MetadataVersionDto dto = metadataVersionService.getLatest(pageId);
        return ResponseEntity.ok(ApiResponse.success(dto, "Latest metadata version retrieved successfully"));
    }

    @GetMapping("/{versionId}")
    @Operation(summary = "Get a metadata version by ID", description = "Retrieves a single metadata version by its UUID")
    @ApiCommonResponses
    @io.swagger.v3.oas.annotations.responses.ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Version retrieved successfully"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Page or version not found", content = @io.swagger.v3.oas.annotations.media.Content(schema = @io.swagger.v3.oas.annotations.media.Schema(implementation = eg.mts.gsuif.dto.ErrorApiResponse.class)))
    })
    public ResponseEntity<ApiResponse<MetadataVersionDto>> getById(
            @Parameter(description = "Page UUID", required = true)
            @PathVariable("pageId") UUID pageId,
            @Parameter(description = "Version UUID", required = true)
            @PathVariable("versionId") UUID versionId) {
        MetadataVersionDto dto = metadataVersionService.getById(pageId, versionId);
        return ResponseEntity.ok(ApiResponse.success(dto, "Metadata version retrieved successfully"));
    }
}
