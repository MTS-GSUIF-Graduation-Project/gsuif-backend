package eg.mts.gsuif.dto;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ApiResponseTest {

    @Test
    void success_withTwoArgs_defaultsTo200AndOk() {
        ApiResponse<String> response = ApiResponse.success("sample-data", "Operation successful");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.status()).isEqualTo("OK");
        assertThat(response.clientMessage()).isEqualTo("Operation successful");
        assertThat(response.body()).isEqualTo("sample-data");
        assertThat(response.errors()).isNull();
    }

    @Test
    void success_withThreeArgs_usesCustomStatusCode() {
        ApiResponse<String> response = ApiResponse.success("created-item", "Created successfully", 201);

        assertThat(response.statusCode()).isEqualTo(201);
        assertThat(response.status()).isEqualTo("OK");
        assertThat(response.clientMessage()).isEqualTo("Created successfully");
        assertThat(response.body()).isEqualTo("created-item");
        assertThat(response.errors()).isNull();
    }

    @Test
    void error_derivesStatusFromCodeAndSetsErrors() {
        Map<String, String> errors = Map.of("username", "must not be blank");
        ApiResponse<Void> response = ApiResponse.error(400, "Validation failed", errors);

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(response.status()).isEqualTo("BAD_REQUEST");
        assertThat(response.clientMessage()).isEqualTo("Validation failed");
        assertThat(response.body()).isNull();
        assertThat(response.errors()).containsEntry("username", "must not be blank");
    }
}

