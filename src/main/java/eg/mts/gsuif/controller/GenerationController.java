package eg.mts.gsuif.controller;

import eg.mts.gsuif.dto.ApiResponse;
import eg.mts.gsuif.dto.GenerationApiDtos;
import eg.mts.gsuif.generation.GenerationApiService;
import eg.mts.gsuif.generation.GenerationExportService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.PutMapping;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/generation")
@Tag(name = "Generation", description = "Generate source files and inspect persisted runs")
@SecurityRequirement(name = "bearerAuth")
public class GenerationController {
    private final GenerationApiService service;
    private final GenerationExportService exports;

    public GenerationController(GenerationApiService service, GenerationExportService exports) {
        this.service = service;
        this.exports = exports;
    }

    @PostMapping("/generate")
    @Operation(summary = "Generate source files", operationId = "generateSource",
            description = "Uses the page's selected immutable metadata version and an explicit versioned specification.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "Run recorded"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "Invalid generation input"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Page or selected version not found")
    })
    @ApiCommonWriteResponses
    public ResponseEntity<ApiResponse<GenerationApiDtos.CreatedRun>> generate(
            @Valid @RequestBody GenerationApiDtos.GenerateRequest request, Authentication authentication) {
        var result = service.generate(request, authentication == null ? null : authentication.getName());
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success(result, "Generation run recorded", 201));
    }

    @GetMapping("/providers")
    @Operation(summary = "List generation providers", operationId = "listGenerationProviders")
    @ApiCommonResponses
    public ApiResponse<List<GenerationApiDtos.Provider>> providers() {
        return ApiResponse.success(service.providers(), "Generation providers retrieved");
    }

    @GetMapping("/runs/{id}")
    @Operation(summary = "Get a generation run", operationId = "getGenerationRun",
            description = "Returns persisted status, timestamps, metadata version, linked artifacts, and Maven diagnostics.")
    @ApiCommonResponses
    public ApiResponse<GenerationApiDtos.RunDetails> run(@PathVariable UUID id) {
        return ApiResponse.success(service.run(id), "Generation run retrieved");
    }

    @GetMapping("/runs/{id}/artifacts/download")
    @Operation(summary = "Download a registered artifact from a saved run", operationId = "downloadGeneratedArtifact")
    @ApiCommonResponses
    public ResponseEntity<byte[]> download(@PathVariable UUID id, @RequestParam String path) {
        var file = service.download(id, path);
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + file.fileName() + "\"")
                .body(file.bytes());
    }

    @PutMapping("/projects/{projectId}/destinations/{name}")
    @Operation(summary = "Configure a project export destination", operationId = "configureGenerationExport")
    @ApiCommonWriteResponses
    public ApiResponse<GenerationExportService.DestinationConfig> configure(
            @PathVariable UUID projectId, @PathVariable String name,
            @RequestBody GenerationExportService.DestinationConfig config) {
        return ApiResponse.success(exports.configure(projectId, name, config), "Export destination configured");
    }

    @PostMapping("/runs/{id}/exports/{name}")
    @Operation(summary = "Export saved run artifacts", operationId = "exportGenerationRun")
    @ApiCommonWriteResponses
    public ApiResponse<GenerationExportService.ExportLocation> export(@PathVariable UUID id, @PathVariable String name) {
        return ApiResponse.success(exports.export(id, name), "Run exported");
    }

    @GetMapping("/artifacts/history")
    @Operation(summary = "Trace registered file history to runs and metadata versions", operationId = "getGeneratedArtifactHistory")
    @ApiCommonResponses
    public ApiResponse<List<GenerationApiService.ArtifactTrace>> history(@RequestParam String path) {
        return ApiResponse.success(service.artifactHistory(path), "Artifact history retrieved");
    }
}
