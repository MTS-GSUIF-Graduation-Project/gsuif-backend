<#ftl strip_whitespace=true encoding="UTF-8">
package ${packageName}.security;

import ${packageName}.api.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Profile({"test", "prod"})
@RequestMapping("/api/auth")
public final class AuthController {
    private final AccountIdentityService accounts;
    private final JwtService tokens;

    public AuthController(AccountIdentityService accounts, JwtService tokens) {
        this.accounts = accounts;
        this.tokens = tokens;
    }

    @io.swagger.v3.oas.annotations.Operation(operationId = "login", summary = "Authenticate an enabled account")
    @io.swagger.v3.oas.annotations.security.SecurityRequirements
    @io.swagger.v3.oas.annotations.responses.ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Authenticated",
                useReturnTypeSchema = true),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "Invalid login request",
                content = @io.swagger.v3.oas.annotations.media.Content(mediaType = "application/json",
                        schema = @io.swagger.v3.oas.annotations.media.Schema(implementation = ${packageName}.api.ErrorApiResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Authentication failed",
                content = @io.swagger.v3.oas.annotations.media.Content(mediaType = "application/json",
                        schema = @io.swagger.v3.oas.annotations.media.Schema(implementation = ${packageName}.api.ErrorApiResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "405", description = "Method not allowed",
                content = @io.swagger.v3.oas.annotations.media.Content(mediaType = "application/json",
                        schema = @io.swagger.v3.oas.annotations.media.Schema(implementation = ${packageName}.api.ErrorApiResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "415", description = "Unsupported media type",
                content = @io.swagger.v3.oas.annotations.media.Content(mediaType = "application/json",
                        schema = @io.swagger.v3.oas.annotations.media.Schema(implementation = ${packageName}.api.ErrorApiResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "500", description = "Unexpected server error",
                content = @io.swagger.v3.oas.annotations.media.Content(mediaType = "application/json",
                        schema = @io.swagger.v3.oas.annotations.media.Schema(implementation = ${packageName}.api.ErrorApiResponse.class)))
    })
    @PostMapping(path = "/login", consumes = "application/json", produces = "application/json")
    public ResponseEntity<ApiResponse<LoginResponse>> login(@Valid @RequestBody LoginRequest request) {
        AuthenticatedAccount account = accounts.authenticate(request.username(), request.password());
        return ResponseEntity.ok(ApiResponse.success(new LoginResponse(tokens.issue(account)), "Authenticated", 200));
    }
}
