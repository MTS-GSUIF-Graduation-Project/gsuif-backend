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

    @io.swagger.v3.oas.annotations.Operation(summary = "Authenticate an enabled account")
    @PostMapping(path = "/login", consumes = "application/json", produces = "application/json")
    public ResponseEntity<ApiResponse<LoginResponse>> login(@Valid @RequestBody LoginRequest request) {
        AuthenticatedAccount account = accounts.authenticate(request.username(), request.password());
        return ResponseEntity.ok(ApiResponse.success(new LoginResponse(tokens.issue(account)), "Authenticated", 200));
    }
}
