<#ftl strip_whitespace=true encoding="UTF-8">
package ${packageName}.security;

/** The password is accepted only as request data and is never included in diagnostics. */
public record LoginRequest(
        @jakarta.validation.constraints.NotBlank @jakarta.validation.constraints.Size(max = 100) String username,
        @jakarta.validation.constraints.NotBlank @jakarta.validation.constraints.Size(max = 1024)
        @io.swagger.v3.oas.annotations.media.Schema(format = "password", accessMode = io.swagger.v3.oas.annotations.media.Schema.AccessMode.WRITE_ONLY)
        String password) {
    @Override public String toString() { return "LoginRequest[redacted]"; }
}
