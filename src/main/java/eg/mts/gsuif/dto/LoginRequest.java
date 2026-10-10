package eg.mts.gsuif.dto;

import jakarta.validation.constraints.NotBlank;
import io.swagger.v3.oas.annotations.media.Schema;

public class LoginRequest {

    @NotBlank(message = "Username is required")
    @Schema(minLength = 1, description = "Required; must contain a non-whitespace character (Java Character.isWhitespace).", example = "operator")
    private String username;

    @NotBlank(message = "Password is required")
    @Schema(minLength = 1, description = "Required; must contain a non-whitespace character (Java Character.isWhitespace).", example = "Example-only-password!42")
    private String password;

    public LoginRequest() {
    }

    public LoginRequest(String username, String password) {
        this.username = username;
        this.password = password;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }
}
