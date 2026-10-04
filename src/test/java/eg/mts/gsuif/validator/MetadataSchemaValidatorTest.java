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



    @Test
    void originalExamplesRemainValidUnderVersionOne() throws Exception {
        for (String name : List.of("simple", "one-to-many", "many-to-many")) {
            JsonNode example = loadExample("metadata/examples/" + name + ".json");
            assertThat(example.path("metadataVersion").path("schemaVersion").asText()).isEqualTo("1.0.0");
            assertThat(validator.validate("1.0.0", example.path("metadataVersion").path("snapshot"))).isEmpty();
        }
    }

    @Test
    void typedComponentsUseOnlyTheirMatchingConfigInVersionEleven() throws Exception {
        ObjectNode snapshot = (ObjectNode) loadExample("metadata/examples/simple.json")
                .path("metadataVersion").path("snapshot").deepCopy();
        ObjectNode component = (ObjectNode) snapshot.withArray("components").get(0);
        component.put("type", "form");
        component.putObject("formConfig").put("submitLabel", "Save");
        assertThat(validator.validate("1.1.0", snapshot)).isEmpty();
        assertThat(validator.validate("1.0.0", snapshot)).isNotEmpty();

        component.putObject("paginationConfig").put("pageSize", 1);
        assertThat(validator.validate("1.1.0", snapshot)).isNotEmpty();
        component.remove("paginationConfig");
        component.with("formConfig").put("submitLabel", "");
        assertThat(validator.validate("1.1.0", snapshot))
                .anyMatch(error -> error.path().equals("$.snapshot.components[0].formConfig.submitLabel"));
    }

    @Test
    void versionElevenValidatesEveryApprovedTypeAndBoundaries() throws Exception {
        ObjectNode snapshot = (ObjectNode) loadExample("metadata/examples/simple.json")
                .path("metadataVersion").path("snapshot").deepCopy();
        ObjectNode component = (ObjectNode) snapshot.withArray("components").get(0);
        String[][] valid = {
                {"form", "formConfig", "{\"submitLabel\":\"Save\"}"},
                {"table", "tableConfig", "{\"columns\":[{\"fieldKey\":\"name\",\"label\":\"Name\"}]}"},
                {"navigation", "navigationConfig", "{\"items\":[{\"label\":\"Home\",\"route\":\"/home\"}]}"},
                {"pagination", "paginationConfig", "{\"pageSize\":1}"}
        };
        for (String[] entry : valid) {
            component.put("type", entry[0]);
            component.set(entry[1], objectMapper.readTree(entry[2]));
            assertThat(validator.validate("1.1.0", snapshot)).as(entry[0]).isEmpty();
            component.remove(entry[1]);
        }
        component.put("type", "modal");
        assertThat(validator.validate("1.1.0", snapshot)).isEmpty();
        component.put("type", "custom-widget");
        assertThat(validator.validate("1.1.0", snapshot)).isEmpty();
        component.put("type", "table");
        assertThat(validator.validate("1.1.0", snapshot)).isEmpty(); // legacy tableConfig is optional

        component.put("type", "pagination");
        component.putObject("paginationConfig").put("pageSize", 0);
        assertThat(validator.validate("1.1.0", snapshot))
                .anyMatch(error -> error.path().equals("$.snapshot.components[0].paginationConfig.pageSize"));
        component.with("paginationConfig").put("pageSize", 1.5);
        assertThat(validator.validate("1.1.0", snapshot)).isNotEmpty();
        component.remove("paginationConfig");
        component.put("type", "navigation");
        component.set("navigationConfig", objectMapper.readTree("{\"items\":[{\"label\":\"Home\",\"route\":\"https://host\"}]}"));
        assertThat(validator.validate("1.1.0", snapshot))
                .anyMatch(error -> error.path().equals("$.snapshot.components[0].navigationConfig.items[0].route"));
    }

    @Test
    void unsupportedVersionDoesNotApplyAnUnrelatedSnapshotSchema() throws Exception {
        JsonNode snapshot = objectMapper.readTree("{\"unexpected\":true}");
        assertThat(validator.validate("unsupported", snapshot)).extracting(ValidationError::path)
                .containsExactly("$.schemaVersion");
        assertThat(validator.validate("unsupported", null)).extracting(ValidationError::path)
                .containsExactly("$.schemaVersion", "$.snapshot");
    }

    @Test
    void versionTwelveAcceptsNestedExampleAndOlderVersionsRejectNewFields() throws Exception {
        JsonNode snapshot = loadExample("metadata/examples/many-to-many-1.2.0.json")
                .path("metadataVersion").path("snapshot");
        assertThat(validator.validate("1.2.0", snapshot)).isEmpty();
        assertThat(validator.validate("1.0.0", snapshot)).isNotEmpty();
        assertThat(validator.validate("1.1.0", snapshot)).isNotEmpty();
    }

    @Test
    void versionTwelveRejectsBrokenRelationshipTargetsAndCycles() throws Exception {
        ObjectNode snapshot = (ObjectNode) loadExample("metadata/examples/many-to-many-1.2.0.json")
                .path("metadataVersion").path("snapshot").deepCopy();
        ArrayNode bindings = snapshot.withArray("apiBindings");
        ObjectNode first = (ObjectNode) bindings.get(0);
        first.put("parentComponentId", "not-a-uuid");
        assertThat(validator.validate("1.2.0", snapshot)).isNotEmpty();
        first.put("parentComponentId", "12000000-0000-4000-8000-000000000099");
        assertThat(validator.validate("1.2.0", snapshot)).anyMatch(e -> e.message().contains("does not exist"));
        first.put("parentComponentId", "12000000-0000-4000-8000-000000000003");
        first.withArray("childComponentIds").set(0, objectMapper.getNodeFactory().textNode("12000000-0000-4000-8000-000000000099"));
        assertThat(validator.validate("1.2.0", snapshot)).anyMatch(e -> e.message().contains("does not exist"));
        first.withArray("childComponentIds").set(0, objectMapper.getNodeFactory().textNode("12000000-0000-4000-8000-000000000003"));
        assertThat(validator.validate("1.2.0", snapshot)).anyMatch(e -> e.message().contains("own child"));
        first.withArray("childComponentIds").set(0, objectMapper.getNodeFactory().textNode("12000000-0000-4000-8000-000000000001"));
        ((ObjectNode) bindings.get(2)).put("parentComponentId", "12000000-0000-4000-8000-000000000001");
        ((ObjectNode) bindings.get(2)).putArray("childComponentIds").add("12000000-0000-4000-8000-000000000003");
        assertThat(validator.validate("1.2.0", snapshot)).anyMatch(e -> e.message().contains("cycle"));
    }

    @Test
    void versionTwelveRejectsUnsupportedNestedVisibilityAndUnpairedLinks() throws Exception {
        ObjectNode snapshot = (ObjectNode) loadExample("metadata/examples/many-to-many-1.2.0.json")
                .path("metadataVersion").path("snapshot").deepCopy();
        ObjectNode binding = (ObjectNode) snapshot.withArray("apiBindings").get(0);
        binding.set("visibilityRule", objectMapper.readTree("{\"op\":\"NOT\",\"rule\":{\"op\":\"unsupported\"}}"));
        assertThat(validator.validate("1.2.0", snapshot)).isNotEmpty();
        binding.remove("visibilityRule");
        binding.remove("parentComponentId");
        assertThat(validator.validate("1.2.0", snapshot)).isNotEmpty();
    }

    @Test
    @org.junit.jupiter.api.Timeout(10)
    void versionTwelveValidatesDeeplyNestedAndRulesWithoutBranchExplosion() throws Exception {
        assertNestedVisibilityRuleValid("AND", "AND");
    }

    @Test
    @org.junit.jupiter.api.Timeout(10)
    void versionTwelveValidatesDeeplyNestedOrRulesWithoutBranchExplosion() throws Exception {
        assertNestedVisibilityRuleValid("OR", "OR");
    }

    @Test
    @org.junit.jupiter.api.Timeout(10)
    void versionTwelveValidatesMixedAndOrRulesWithoutBranchExplosion() throws Exception {
        assertNestedVisibilityRuleValid("AND", "OR");
    }

    @Test
    void versionTwelveInvalidNestedVisibilityReportsItemPath() throws Exception {
        ObjectNode snapshot = versionTwelveSnapshot();
        ObjectNode binding = (ObjectNode) snapshot.withArray("apiBindings").get(0);
        binding.set("visibilityRule", objectMapper.readTree(
                "{\"op\":\"AND\",\"rules\":[{\"op\":\"field\",\"field\":\"status\"}]}"));

        assertThat(validator.validate("1.2.0", snapshot))
                .anySatisfy(error -> {
                    assertThat(error.path()).isEqualTo(
                            "$.snapshot.apiBindings[0].visibilityRule.rules[0].equals");
                    assertThat(error.message()).isNotBlank();
                });
    }

    @Test
    void versionTwelveVisibilityDepthMatchesEvaluatorBoundary() throws Exception {
        ObjectNode snapshot = versionTwelveSnapshot();
        ObjectNode binding = (ObjectNode) snapshot.withArray("apiBindings").get(0);
        JsonNode rule = nestedVisibilityRule(VisibilityRuleEvaluator.MAX_DEPTH, "NOT", "NOT");
        binding.set("visibilityRule", rule);
        var context = new VisibilityRuleEvaluator.Context(java.util.Set.of("users:read"), java.util.Set.of(), objectMapper.createObjectNode());
        assertThat(validator.validate("1.2.0", snapshot)).isEmpty();
        assertThat(VisibilityRuleEvaluator.evaluate(rule, context)).isTrue();

        JsonNode tooDeep = objectMapper.createObjectNode().put("op", "NOT").set("rule", rule);
        binding.set("visibilityRule", tooDeep);
        assertThat(validator.validate("1.2.0", snapshot))
                .contains(new ValidationError("$.snapshot.apiBindings[0].visibilityRule", "visibility rule exceeds maximum nesting depth of 64"));
        assertThat(VisibilityRuleEvaluator.evaluate(tooDeep, context)).isFalse();
    }

    private void assertNestedVisibilityRuleValid(String firstOperator, String secondOperator) throws Exception {
        ObjectNode snapshot = versionTwelveSnapshot();
        JsonNode rule = nestedVisibilityRule(16, firstOperator, secondOperator);
        ((ObjectNode) snapshot.withArray("apiBindings").get(0)).set("visibilityRule", rule);
        var context = new VisibilityRuleEvaluator.Context(
                java.util.Set.of("users:read"), java.util.Set.of(), objectMapper.createObjectNode());
        assertThat(validator.validate("1.2.0", snapshot)).isEmpty();
        assertThat(VisibilityRuleEvaluator.evaluate(rule, context)).isTrue();
    }

    private JsonNode nestedVisibilityRule(int depth, String firstOperator, String secondOperator) throws Exception {
        JsonNode rule = objectMapper.readTree("{\"op\":\"permission\",\"value\":\"users:read\"}");
        for (int i = 0; i < depth; i++) {
            String operator = i % 2 == 0 ? firstOperator : secondOperator;
            ObjectNode parent = objectMapper.createObjectNode().put("op", operator);
            if ("NOT".equals(operator)) {
                parent.set("rule", rule);
            } else {
                parent.putArray("rules").add(rule);
            }
            rule = parent;
        }
        return rule;
    }

    private ObjectNode versionTwelveSnapshot() throws Exception {
        return (ObjectNode) loadExample("metadata/examples/many-to-many-1.2.0.json")
                .path("metadataVersion").path("snapshot").deepCopy();
    }

    private JsonNode loadExample(String path) throws Exception {

        java.io.File file = new java.io.File(path);

        return objectMapper.readTree(file);

    }

}
