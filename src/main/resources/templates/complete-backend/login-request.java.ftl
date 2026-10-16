<#ftl strip_whitespace=true encoding="UTF-8">
package ${packageName}.security;

/** The password is accepted only as request data and is never included in diagnostics. */
public record LoginRequest(
        @jakarta.validation.constraints.NotBlank @jakarta.validation.constraints.Size(max = 100) String username,
        @jakarta.validation.constraints.NotBlank @jakarta.validation.constraints.Size(max = 1024) String password) {
    @Override public String toString() { return "LoginRequest[redacted]"; }
}
