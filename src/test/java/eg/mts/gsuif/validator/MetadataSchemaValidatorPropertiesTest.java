package eg.mts.gsuif.validator;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import eg.mts.gsuif.validator.MetadataSchemaValidator.ValidationError;
import net.jqwik.api.*;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class MetadataSchemaValidatorPropertiesTest {

    private final MetadataSchemaValidator validator = new MetadataSchemaValidator();
    private final ObjectMapper mapper = new ObjectMapper();

    @Property(tries = 100)
    void validSnapshotShouldPass(@ForAll("validSnapshots") JsonNode snapshot) {
        List<ValidationError> errors = validator.validate("1.0.0", snapshot);
        assertThat(errors).isEmpty();
    }

    @Property(tries = 50)
    void deterministicValidation(@ForAll("snapshotsWithComponents") JsonNode original) {
        ObjectNode snapshot = original.deepCopy();
        snapshot.remove("apiBindings");
        ((ObjectNode) snapshot.withArray("components").get(0)).remove("label");

        List<ValidationError> errors1 = validator.validate("unsupported", snapshot);
        List<ValidationError> errors2 = validator.validate("unsupported", snapshot);
        assertThat(errors1).extracting(ValidationError::path).contains(
                "$.schemaVersion", "$.snapshot.apiBindings", "$.snapshot.components[0].label");
        assertThat(errors1).hasSizeGreaterThanOrEqualTo(3);
        assertThat(errors1).isEqualTo(errors2);
    }

    @Property(tries = 50)
    void validationDoesNotAlterInput(@ForAll("validSnapshots") JsonNode snapshot) {
        String originalJson = snapshot.toString();
        validator.validate("1.0.0", snapshot);
        assertThat(snapshot.toString()).isEqualTo(originalJson);
    }

    @Property(tries = 50)
    void missingRequiredComponentFieldProducesError(
            @ForAll("snapshotsWithComponents") JsonNode snapshotOriginal,
            @ForAll("requiredComponentFields") String fieldToRemove) {
        assertThat(validator.validate("1.0.0", snapshotOriginal)).isEmpty();
        ObjectNode snapshot = snapshotOriginal.deepCopy();
        ArrayNode components = (ArrayNode) snapshot.get("components");
        ObjectNode component = (ObjectNode) components.get(0);
        assertThat(component.has(fieldToRemove)).isTrue();
        component.remove(fieldToRemove);

        List<ValidationError> errors = validator.validate("1.0.0", snapshot);
        assertThat(errors).anyMatch(e ->
                e.path().equals("$.snapshot.components[0]." + fieldToRemove)
                        && e.message().contains("required property '" + fieldToRemove + "'"));
    }

    @Property(tries = 50)
    void invalidUuidFormatProducesError(
            @ForAll("snapshotsWithComponents") JsonNode snapshotOriginal,
            @ForAll("invalidUuids") String invalidUuid) {
        assertThat(validator.validate("1.0.0", snapshotOriginal)).isEmpty();
        ObjectNode snapshot = snapshotOriginal.deepCopy();
        ArrayNode components = (ArrayNode) snapshot.get("components");
        ObjectNode component = (ObjectNode) components.get(0);
        component.put("id", invalidUuid);

        List<ValidationError> errors = validator.validate("1.0.0", snapshot);
        assertThat(errors).anyMatch(e -> e.path().equals("$.snapshot.components[0].id")
                && (e.message().contains("format") || e.message().contains("uuid")));
    }

    @Provide
    Arbitrary<String> invalidUuids() {
        return Arbitraries.strings().alpha().ofMinLength(1).ofMaxLength(10);
    }

    @Provide
    Arbitrary<String> requiredComponentFields() {
        return Arbitraries.of("id", "type", "label", "position", "size", "visibility", "disabled");
    }

    @Provide
    Arbitrary<JsonNode> validSnapshots() {
        return snapshots(0);
    }

    @Provide
    Arbitrary<JsonNode> snapshotsWithComponents() {
        return snapshots(1);
    }

    private Arbitrary<JsonNode> snapshots(int minimumComponents) {
        return Arbitraries.integers().between(minimumComponents, 5).flatMap(numComponents ->
                components(numComponents).map(componentsArray -> {
                    ObjectNode snapshot = JsonNodeFactory.instance.objectNode();
                    snapshot.set("components", componentsArray);
                    snapshot.set("apiBindings", JsonNodeFactory.instance.arrayNode());
                    return snapshot;
                }));
    }

    private Arbitrary<ArrayNode> components(int count) {
        return validComponent().list().ofSize(count).map(list -> {
            ArrayNode arrayNode = JsonNodeFactory.instance.arrayNode();
            list.forEach(arrayNode::add);
            return arrayNode;
        });
    }

    private Arbitrary<ObjectNode> validComponent() {
        return Combinators.combine(
                Arbitraries.longs(),
                Arbitraries.longs(),
                Arbitraries.strings().alpha().ofMinLength(1).ofMaxLength(10),
                Arbitraries.of("text-field", "button"))
                .as((most, least, label, type) -> component(new UUID(most, least), label, type));
    }

    private ObjectNode component(UUID id, String label, String type) {
        ObjectNode component = JsonNodeFactory.instance.objectNode();
        component.put("id", id.toString());
        component.put("type", type);
        component.put("label", label);
        ObjectNode position = component.putObject("position");
        position.put("row", 0);
        position.put("col", type.equals("button") ? 6 : 0);
        ObjectNode size = component.putObject("size");
        size.put("width", type.equals("button") ? 2 : 6);
        size.put("height", 1);
        component.put("visibility", true);
        component.put("disabled", false);
        return component;
    }

    @Example
    void fractionalWidthIsRejectedByIntegerSchema() throws Exception {
        JsonNode snapshot = mapper.readTree("""
            {
                "components": [
                    {
                        "id": "123e4567-e89b-12d3-a456-426614174001",
                        "type": "text-field",
                        "label": "Test",
                        "position": { "row": 0, "col": 0 },
                        "size": { "width": 1.234567890123456789, "height": 1 },
                        "visibility": true,
                        "disabled": false
                    }
                ],
                "apiBindings": []
            }
        """);

        List<ValidationError> errors = validator.validate("1.0.0", snapshot);
        assertThat(errors).isNotEmpty();
        assertThat(errors).anyMatch(e -> e.path().contains("components[0].size.width") && e.message().contains("integer expected"));
    }

    @Property(tries = 30)
    void wrongJsonTypeProducesError(@ForAll("validSnapshots") JsonNode baseSnapshot) {
        // Change the type of components to a string
        ObjectNode snapshot = baseSnapshot.deepCopy();
        snapshot.set("components", JsonNodeFactory.instance.textNode("not an array"));
        List<ValidationError> errors = validator.validate("1.0.0", snapshot);
        assertThat(errors).anyMatch(e -> e.path().equals("$.snapshot.components") && e.message().contains("array"));
    }

    @Property(tries = 30)
    void independentStructuralErrorsArePreserved(@ForAll("snapshotsWithComponents") JsonNode baseSnapshot) {
        assertThat(validator.validate("1.0.0", baseSnapshot)).isEmpty();
        ObjectNode snapshot = baseSnapshot.deepCopy();
        snapshot.remove("apiBindings");
        ((ObjectNode) snapshot.withArray("components").get(0)).remove("label");

        assertThat(validator.validate("1.0.0", snapshot))
                .extracting(ValidationError::path)
                .containsExactlyInAnyOrder("$.snapshot.apiBindings", "$.snapshot.components[0].label");
    }

    @Property(tries = 30)
    void missingSnapshotFieldsProduceErrors(@ForAll("validSnapshots") JsonNode baseSnapshot) {
        assertThat(validator.validate("1.0.0", baseSnapshot)).isEmpty();
        for (String field : List.of("components", "apiBindings")) {
            ObjectNode snapshot = baseSnapshot.deepCopy();
            snapshot.remove(field);

            assertThat(validator.validate("1.0.0", snapshot)).anyMatch(e ->
                    e.path().equals("$.snapshot." + field)
                            && e.message().contains("required property '" + field + "'"));
        }
    }

    @Property(tries = 30)
    void missingApiBindingFieldProducesError(@ForAll("validSnapshots") JsonNode baseSnapshot) {
        ObjectNode snapshot = baseSnapshot.deepCopy();
        ObjectNode binding = validBinding();
        snapshot.withArray("apiBindings").add(binding);
        assertThat(validator.validate("1.0.0", snapshot)).isEmpty();

        binding.remove("endpointUrl");
        assertThat(validator.validate("1.0.0", snapshot)).anyMatch(e ->
                e.path().equals("$.snapshot.apiBindings[0].endpointUrl")
                        && e.message().contains("required property 'endpointUrl'"));
    }

    @Property(tries = 30)
    void invalidApiBindingHttpMethodProducesError(@ForAll("validSnapshots") JsonNode baseSnapshot,
                                                 @ForAll("invalidHttpMethods") String badMethod) {
        // Build a snapshot with a valid apiBinding first
        ObjectNode snapshot = baseSnapshot.deepCopy();
        ObjectNode binding = validBinding();
        ArrayNode apiBindings = JsonNodeFactory.instance.arrayNode();
        apiBindings.add(binding);
        snapshot.set("apiBindings", apiBindings);
        // Baseline must validate with no errors
        List<ValidationError> baseline = validator.validate("1.0.0", snapshot);
        assertThat(baseline).isEmpty();
        // Mutate only the httpMethod to an invalid value
        binding.put("httpMethod", badMethod);
        List<ValidationError> errors = validator.validate("1.0.0", snapshot);
        assertThat(errors).anyMatch(e -> e.path().equals("$.snapshot.apiBindings[0].httpMethod") && e.message().contains("enum"));
    }

    @Property(tries = 30)
    void componentSizeWidthBoundaryViolation(@ForAll("snapshotsWithComponents") JsonNode baseSnapshot) {
        ObjectNode snapshot = baseSnapshot.deepCopy();
        ArrayNode components = (ArrayNode) snapshot.get("components");
        ObjectNode component = (ObjectNode) components.get(0);
        // Baseline must validate with no errors
        assertThat(validator.validate("1.0.0", snapshot)).isEmpty();
        // Set width to 0 – should be rejected (minimum violation)
        component.with("size").put("width", 0);
        List<ValidationError> errorsZero = validator.validate("1.0.0", snapshot);
        assertThat(errorsZero).anyMatch(e -> e.path().equals("$.snapshot.components[0].size.width") && e.message().contains("minimum"));
        // Set width to 1 – should be accepted
        component.with("size").put("width", 1);
        List<ValidationError> errorsOne = validator.validate("1.0.0", snapshot);
        assertThat(errorsOne).isEmpty();
    }

    private ObjectNode validBinding() {
        ObjectNode binding = JsonNodeFactory.instance.objectNode();
        binding.put("id", "123e4567-e89b-12d3-a456-426614174000");
        binding.put("httpMethod", "GET");
        binding.put("endpointUrl", "/test");
        binding.putObject("headers");
        binding.putObject("requestMapping");
        binding.putObject("responseMapping");
        return binding;
    }

    @Provide
    Arbitrary<String> invalidHttpMethods() {
        // Generate strings unlikely to be valid HTTP methods
        return Arbitraries.strings().alpha().ofMinLength(3).ofMaxLength(6)
                .filter(s -> !java.util.Set.of("GET", "POST", "PUT", "DELETE", "PATCH", "OPTIONS", "HEAD").contains(s.toUpperCase()));
    }
}
