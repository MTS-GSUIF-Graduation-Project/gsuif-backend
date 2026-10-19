package eg.mts.gsuif.controller;

import eg.mts.gsuif.dto.ApiResponse;
import eg.mts.gsuif.dto.ErrorApiResponse;
import eg.mts.gsuif.dto.GenerationApiDtos;
import eg.mts.gsuif.generation.BackendContractCatalogService;
import eg.mts.gsuif.generation.GenerationApiService;
import eg.mts.gsuif.generation.GenerationExportService;
import eg.mts.gsuif.generation.GenerationValidationException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/generation")
@Tag(name = "Generation", description = "Generate source files and inspect persisted runs")
@SecurityRequirement(name = "bearerAuth")
public final class GenerationWorkspaceController {
    private final GenerationExportService exports;
    private final BackendContractCatalogService contracts;
    private final GenerationApiService runs;

    public GenerationWorkspaceController(GenerationExportService exports,
            BackendContractCatalogService contracts, GenerationApiService runs) {
        this.exports = exports;
        this.contracts = contracts;
        this.runs = runs;
    }

    @GetMapping(path = "/runs/{runId}/package.zip", produces = "application/zip")
    @Operation(summary = "Download a committed complete BACKEND package", operationId = "downloadBackendPackage",
            description = "Verifies the persisted registry, stored files, selected contract and package manifest before streaming. Maximum 512 files and 64 MiB of source content.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Verified ZIP",
                    content = @Content(mediaType = "application/zip", schema = @Schema(type = "string", format = "binary"))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Authentication required", content = @Content(schema = @Schema(implementation = ErrorApiResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Persisted grant missing", content = @Content(schema = @Schema(implementation = ErrorApiResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Run not found", content = @Content(schema = @Schema(implementation = ErrorApiResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "500", description = "Run is incomplete, too large, or package integrity failed", content = @Content(schema = @Schema(implementation = ErrorApiResponse.class)))
    })
    public ResponseEntity<StreamingResponseBody> packageZip(@PathVariable UUID runId) {
        var archive = exports.directPackage(runId);
        return ResponseEntity.ok().contentType(MediaType.parseMediaType("application/zip"))
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + archive.fileName() + "\"")
                .body(archive::writeTo);
    }

    @GetMapping("/contracts")
    @Operation(summary = "List registered complete BACKEND contracts", operationId = "listBackendContracts",
            description = "Contract-level eligibility only. A page and specification must pass preflight before generation.")
    @ApiCommonResponses
    public ApiResponse<List<GenerationApiDtos.AvailableContract>> contracts(@RequestParam String output) {
        if (!"BACKEND".equals(output))
            throw new GenerationValidationException(List.of("output: only BACKEND is supported"));
        return ApiResponse.success(contracts.list(), "BACKEND contracts retrieved");
    }

    @GetMapping("/attempts/{attemptId}")
    @Operation(summary = "Read a persisted generation attempt", operationId = "getGenerationAttempt",
            description = "Read-only owner and persisted-grant lookup. A 404 does not prove that a request cannot reserve this ID later.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Persisted attempt", content = @Content(examples = @ExampleObject(value = "{\"status\":\"OK\",\"clientMessage\":\"Generation attempt retrieved\",\"statusCode\":200,\"body\":{\"runId\":\"00000000-0000-0000-0000-000000000001\",\"attemptId\":\"00000000-0000-0000-0000-000000000002\",\"status\":\"IN_PROGRESS\",\"metadataVersionId\":\"00000000-0000-0000-0000-000000000003\",\"failureStage\":null,\"failureCode\":null},\"errors\":null}"))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Authentication required", content = @Content(schema = @Schema(implementation = ErrorApiResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Persisted Page or MetadataVersion grant missing", content = @Content(schema = @Schema(implementation = ErrorApiResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "No accessible persisted attempt", content = @Content(schema = @Schema(implementation = ErrorApiResponse.class)))
    })
    public ApiResponse<GenerationApiDtos.AttemptStatus> attempt(@PathVariable UUID attemptId,
            Authentication authentication) {
        return ApiResponse.success(runs.attempt(attemptId,
                authentication == null ? null : authentication.getName()), "Generation attempt retrieved");
    }
}
