package eg.mts.gsuif.logging;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

class SensitiveDataMaskerTest {

    @Test
    void nullInput_returnsNull() {
        assertThat(SensitiveDataMasker.mask(null)).isNull();
    }

    @Test
    void passwordEquals_masked() {
        String result = SensitiveDataMasker.mask("password=secret");
        assertThat(result).isEqualTo("password=***MASKED***");
        assertThat(result).doesNotContain("secret");
    }

    @Test
    void passwordColon_masked() {
        String result = SensitiveDataMasker.mask("password: secret");
        assertThat(result).isEqualTo("password: ***MASKED***");
        assertThat(result).doesNotContain("secret");
    }

    @Test
    void passwordMixedCase_masked() {
        String result = SensitiveDataMasker.mask("Password=Secret");
        assertThat(result).isEqualTo("Password=***MASKED***");
        assertThat(result).doesNotContain("Secret");
    }

    @Test
    void tokenEquals_masked() {
        String result = SensitiveDataMasker.mask("token=eyJabc");
        assertThat(result).isEqualTo("token=***MASKED***");
        assertThat(result).doesNotContain("eyJabc");
    }

    @Test
    void authorizationBearer_masked() {
        String result = SensitiveDataMasker.mask("Authorization: Bearer eyJabc");
        assertThat(result).contains("***MASKED***");
        assertThat(result).doesNotContain("eyJabc");
    }

    @Test
    void standaloneBearer_masked() {
        String result = SensitiveDataMasker.mask("Bearer eyJabc123");
        assertThat(result).contains("***MASKED***");
        assertThat(result).doesNotContain("eyJabc123");
    }

    @Test
    void jsonDoubleQuotedPassword_preservesQuotes() {
        String input = "{\"password\": \"secret123\"}";
        String result = SensitiveDataMasker.mask(input);
        assertThat(result).isEqualTo("{\"password\": \"***MASKED***\"}");
        assertThat(result).doesNotContain("secret123");
    }

    @Test
    void jsonDoubleQuotedToken_preservesQuotes() {
        String input = "{\"token\": \"eyJabc123\"}";
        String result = SensitiveDataMasker.mask(input);
        assertThat(result).isEqualTo("{\"token\": \"***MASKED***\"}");
        assertThat(result).doesNotContain("eyJabc123");
    }

    @Test
    void singleQuotedPassword_preservesQuotes() {
        String input = "{'password': 'secret'}";
        String result = SensitiveDataMasker.mask(input);
        assertThat(result).isEqualTo("{'password': '***MASKED***'}");
        assertThat(result).doesNotContain("secret");
    }

    @Test
    void passwordQuotedWithSpaces_masked() {
        String input = "password=\"secret pass\"";
        String result = SensitiveDataMasker.mask(input);
        assertThat(result).isEqualTo("password=\"***MASKED***\"");
        assertThat(result).doesNotContain("secret pass");
    }

    @Test
    void recordFormatWithBracketsAndCommas_preserved() {
        String input = "[password=secret, user=1]";
        String result = SensitiveDataMasker.mask(input);
        assertThat(result).isEqualTo("[password=***MASKED***, user=1]");
        assertThat(result).doesNotContain("secret");
    }

    @Test
    void multipleSensitiveValuesInSameString_allMasked() {
        String input = "{\"password\": \"secret123\", \"token\": \"tok456\", \"other\": \"ok\"}";
        String result = SensitiveDataMasker.mask(input);
        assertThat(result).isEqualTo("{\"password\": \"***MASKED***\", \"token\": \"***MASKED***\", \"other\": \"ok\"}");
        assertThat(result).doesNotContain("secret123");
        assertThat(result).doesNotContain("tok456");
        assertThat(result).contains("ok");
    }

    @Test
    void apiKey_masked() {
        String result = SensitiveDataMasker.mask("api_key=my-secret-key");
        assertThat(result).isEqualTo("api_key=***MASKED***");
        assertThat(result).doesNotContain("my-secret-key");
    }

    @Test
    void apikeyWithoutUnderscore_masked() {
        String result = SensitiveDataMasker.mask("apikey: my-secret-key");
        assertThat(result).isEqualTo("apikey: ***MASKED***");
        assertThat(result).doesNotContain("my-secret-key");
    }

    @Test
    void apiKeyMixedCase_masked() {
        String result = SensitiveDataMasker.mask("API-KEY=my-secret-key");
        assertThat(result).isEqualTo("API-KEY=***MASKED***");
        assertThat(result).doesNotContain("my-secret-key");
    }

    @Test
    void multilineSecret_masked() {
        String input = "{\"secret\": \"-----BEGIN PRIVATE KEY-----\\nMIIEvQIBADANBgkqhkiG9w0BAQEFAASC\\n-----END PRIVATE KEY-----\"}";
        String result = SensitiveDataMasker.mask(input);
        assertThat(result).isEqualTo("{\"secret\": \"***MASKED***\"}");
        assertThat(result).doesNotContain("MIIEvQIBADANBgkqhkiG9w0BAQEFAASC");
        assertThat(result).doesNotContain("-----BEGIN PRIVATE KEY-----");
    }

    @Test
    void escapedQuotesInSecret_masked() {
        String input = "{\"token\": \"my \\\"super\\\" secret\"}";
        String result = SensitiveDataMasker.mask(input);
        assertThat(result).isEqualTo("{\"token\": \"***MASKED***\"}");
        assertThat(result).doesNotContain("my \\\"super\\\" secret");
        assertThat(result).doesNotContain("super");
    }
}
