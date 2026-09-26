package eg.mts.gsuif.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.Map;

@Schema(description = "Standard error response envelope (STD-01..03)")
public class ErrorApiResponse {

    @Schema(description = "Success or error status", example = "NOT_FOUND")
    private String status;

    @Schema(description = "Safe client-facing message", example = "Not found")
    private String clientMessage;

    @Schema(description = "HTTP status code", example = "404")
    private int statusCode;

    @Schema(description = "Response body is null for errors", nullable = true)
    private Object body;

    @Schema(description = "Field-level or global validation errors", nullable = true)
    private Map<String, String> errors;

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public String getClientMessage() { return clientMessage; }
    public void setClientMessage(String clientMessage) { this.clientMessage = clientMessage; }

    public int getStatusCode() { return statusCode; }
    public void setStatusCode(int statusCode) { this.statusCode = statusCode; }

    public Object getBody() { return body; }
    public void setBody(Object body) { this.body = body; }

    public Map<String, String> getErrors() { return errors; }
    public void setErrors(Map<String, String> errors) { this.errors = errors; }
}
