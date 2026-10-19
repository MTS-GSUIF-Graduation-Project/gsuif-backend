<#ftl strip_whitespace=true encoding="UTF-8">
package ${packageName}.security;

public record LoginResponse(
        @jakarta.validation.constraints.NotBlank String accessToken,
        @jakarta.validation.constraints.NotBlank String tokenType,
        @jakarta.validation.constraints.Positive long expiresInSeconds) {
    public LoginResponse(String token) { this(token, "Bearer", 900); }

    public LoginResponse {
        if (accessToken == null || accessToken.isBlank() || !"Bearer".equals(tokenType)
                || expiresInSeconds != 900)
            throw new IllegalArgumentException("Invalid login response");
    }

    @Override public String toString() { return "LoginResponse[redacted]"; }
}
