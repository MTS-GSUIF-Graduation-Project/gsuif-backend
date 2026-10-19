<#ftl strip_whitespace=true encoding="UTF-8">
package ${packageName}.api;

/** Documentation-only shape of ApiResponse.error; no separate runtime envelope. */
@io.swagger.v3.oas.annotations.media.Schema(name = "ErrorApiResponse",
        description = "Five-field safe error envelope; errors contain field keys or _global")
public record ErrorApiResponse(
        String status,
        String clientMessage,
        int statusCode,
        @io.swagger.v3.oas.annotations.media.Schema(nullable = true) Object body,
        java.util.Map<String, String> errors) { }
