package eg.mts.gsuif.validator;



import com.fasterxml.jackson.databind.JsonNode;

import com.fasterxml.jackson.databind.ObjectMapper;

import com.fasterxml.jackson.databind.node.ArrayNode;

import com.fasterxml.jackson.databind.node.ObjectNode;

import eg.mts.gsuif.validator.MetadataSchemaValidator.ValidationError;

import org.junit.jupiter.api.BeforeEach;

import org.junit.jupiter.api.Test;



import java.util.List;



import static org.assertj.core.api.Assertions.assertThat;



class MetadataSchemaValidatorTest {



    private MetadataSchemaValidator validator;

    private ObjectMapper objectMapper;



    @BeforeEach

    void setUp() {

        validator = new MetadataSchemaValidator();

        objectMapper = new ObjectMapper();

    }



    @Test

    void shouldAcceptValidSnapshot() throws Exception {

        JsonNode simpleExample = loadExample("metadata/examples/simple.json");

        JsonNode snapshot = simpleExample.path("metadataVersion").path("snapshot");



        List<ValidationError> errors = validator.validate("1.0.0", snapshot);

        assertThat(errors).isEmpty();

    }



    @Test

    void shouldRejectInvalidSchemaVersion() throws Exception {

        JsonNode simpleExample = loadExample("metadata/examples/simple.json");

        JsonNode snapshot = simpleExample.path("metadataVersion").path("snapshot");



        List<ValidationError> errors = validator.validate("1.0.1", snapshot);

        assertThat(errors).hasSize(1);

        ValidationError error = errors.get(0);

        assertThat(error.path()).isEqualTo("$.schemaVersion");

    }



    @Test

    void shouldRejectNullSnapshot() {

        List<ValidationError> errors = validator.validate("1.0.0", null);

        assertThat(errors).hasSize(1);

        ValidationError error = errors.get(0);

        assertThat(error.path()).isEqualTo("$.snapshot");

    }



    @Test

    void shouldRejectInvalidSchemaVersionAndNullSnapshot() {

        List<ValidationError> errors = validator.validate("1.0.1", null);

        assertThat(errors).hasSize(2);

        assertThat(errors.stream().anyMatch(e -> e.path().equals("$.schemaVersion"))).isTrue();

        assertThat(errors.stream().anyMatch(e -> e.path().equals("$.snapshot"))).isTrue();

    }



    @Test

    void shouldRejectMissingRequiredFieldInSnapshot() throws Exception {

        String invalidJson = """

            {

              "apiBindings": []

            }

            """;

        JsonNode snapshot = objectMapper.readTree(invalidJson);



        List<ValidationError> errors = validator.validate("1.0.0", snapshot);

        assertThat(errors).isNotEmpty();

        boolean hasComponentsError = errors.stream().anyMatch(e -> e.path().equals("$.snapshot.components"));

        assertThat(hasComponentsError).isTrue();

    }



    @Test

    void shouldVerifyLocalRefResolutionByAssertingPreciseComponentErrorPath() throws Exception {

        JsonNode simpleExample = loadExample("metadata/examples/simple.json");

        ObjectNode snapshot = (ObjectNode) simpleExample.path("metadataVersion").path("snapshot").deepCopy();



        // Change exactly one field in the committed valid snapshot

        ArrayNode components = (ArrayNode) snapshot.get("components");

        ObjectNode firstComponent = (ObjectNode) components.get(0);

        firstComponent.put("type", 123); // Invalid type (should be string)



        List<ValidationError> errors = validator.validate("1.0.0", snapshot);

        assertThat(errors).isNotEmpty();



        // Assert the precise component error path (proves $ref resolution worked)

        boolean hasTypeError = errors.stream().anyMatch(e -> e.path().equals("$.snapshot.components[0].type"));

        assertThat(hasTypeError).isTrue();

    }



    @Test

    void shouldCoverTwoIndependentErrors() throws Exception {

        JsonNode simpleExample = loadExample("metadata/examples/simple.json");

        ObjectNode snapshot = (ObjectNode) simpleExample.path("metadataVersion").path("snapshot").deepCopy();



        // Introduce error 1: Missing required field in component

        ArrayNode components = (ArrayNode) snapshot.get("components");

        ObjectNode firstComponent = (ObjectNode) components.get(0);

        firstComponent.remove("type");



        // Introduce error 2: Invalid type in apiBindings (field that genuinely exists)

        ArrayNode bindings = (ArrayNode) snapshot.get("apiBindings");

        ObjectNode firstBinding = (ObjectNode) bindings.get(0);

        firstBinding.put("httpMethod", 999);



        List<ValidationError> errors = validator.validate("1.0.0", snapshot);

        assertThat(errors.size()).isGreaterThanOrEqualTo(2);



        boolean hasComponentError = errors.stream().anyMatch(e -> e.path().equals("$.snapshot.components[0].type"));

        boolean hasBindingError = errors.stream().anyMatch(e -> e.path().equals("$.snapshot.apiBindings[0].httpMethod"));



        assertThat(hasComponentError).isTrue();

        assertThat(hasBindingError).isTrue();

    }



    @Test

    void shouldVerifyInvalidUUIDFormatInComponentAndBinding() throws Exception {

        JsonNode simpleExample = loadExample("metadata/examples/simple.json");

        ObjectNode snapshot = (ObjectNode) simpleExample.path("metadataVersion").path("snapshot").deepCopy();



        // 1. Invalid UUID in component ID

        ArrayNode components = (ArrayNode) snapshot.get("components");

        ObjectNode firstComponent = (ObjectNode) components.get(0);

        firstComponent.put("id", "not-a-uuid");



        // 2. Invalid UUID in API binding ID

        ArrayNode bindings = (ArrayNode) snapshot.get("apiBindings");

        ObjectNode firstBinding = (ObjectNode) bindings.get(0);

        firstBinding.put("id", "also-not-a-uuid");



        // 3. Invalid UUID in linkedComponentIds

        ArrayNode linkedIds = firstBinding.putArray("linkedComponentIds");

        linkedIds.add("invalid-linked-uuid");



        List<ValidationError> errors = validator.validate("1.0.0", snapshot);

        assertThat(errors.size()).isGreaterThanOrEqualTo(3);



        assertThat(errors.stream().anyMatch(e -> e.path().equals("$.snapshot.components[0].id"))).isTrue();

        assertThat(errors.stream().anyMatch(e -> e.path().equals("$.snapshot.apiBindings[0].id"))).isTrue();

        assertThat(errors.stream().anyMatch(e -> e.path().equals("$.snapshot.apiBindings[0].linkedComponentIds[0]"))).isTrue();

    }



    @Test

    void shouldReturnIdenticalOrderedErrorsWhenInvalidJsonIsReordered() throws Exception {

        JsonNode simpleExample = loadExample("metadata/examples/simple.json");

        ObjectNode snapshot1 = (ObjectNode) simpleExample.path("metadataVersion").path("snapshot").deepCopy();



        // Introduce errors

        ((ObjectNode) snapshot1.get("components").get(0)).remove("type");

        ((ObjectNode) snapshot1.get("apiBindings").get(0)).put("httpMethod", 999);



        // Reorder properties in a second snapshot

        ObjectNode snapshot2 = objectMapper.createObjectNode();

        snapshot2.set("apiBindings", snapshot1.get("apiBindings"));

        snapshot2.set("components", snapshot1.get("components"));



        List<ValidationError> errors1 = validator.validate("1.0.0", snapshot1);

        List<ValidationError> errors2 = validator.validate("1.0.0", snapshot2);



        assertThat(errors1).isNotEmpty();

        assertThat(errors1).containsExactlyElementsOf(errors2);

    }



    private JsonNode loadExample(String path) throws Exception {

        java.io.File file = new java.io.File(path);

        return objectMapper.readTree(file);

    }

}
