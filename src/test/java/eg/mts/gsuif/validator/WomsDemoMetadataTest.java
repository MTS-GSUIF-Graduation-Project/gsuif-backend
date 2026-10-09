package eg.mts.gsuif.validator;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class WomsDemoMetadataTest {
    private static final List<String> FIXTURES = List.of(
            "work-order-search-1.2.0.json",
            "work-order-create-edit-1.2.0.json",
            "work-order-detail-1.2.0.json");

    private final ObjectMapper mapper = new ObjectMapper();
    private final MetadataSchemaValidator validator = new MetadataSchemaValidator();

    @Test
    void validatesThreeDistinctPagesAndMatchingSnapshots() throws Exception {
        Set<String> pageIds = new HashSet<>();
        Set<String> routes = new HashSet<>();

        for (String fixture : FIXTURES) {
            JsonNode bundle = load(fixture);
            JsonNode page = bundle.path("page");
            JsonNode version = bundle.path("metadataVersion");

            assertThat(pageIds.add(page.path("id").asText())).as(fixture + " page id").isTrue();
            assertThat(routes.add(page.path("route").asText())).as(fixture + " route").isTrue();
            assertThat(page.path("projectId")).isEqualTo(bundle.path("project").path("id"));
            assertThat(version.path("projectId")).isEqualTo(page.path("projectId"));
            assertThat(version.path("pageId")).isEqualTo(page.path("id"));
            assertThat(version.path("schemaVersion").asText()).isEqualTo("1.2.0");
            assertThat(version.path("snapshot").path("components")).isEqualTo(page.path("components"));
            assertThat(version.path("snapshot").path("apiBindings")).isEqualTo(page.path("apiBindings"));
            assertThat(validator.validate("1.2.0", version.path("snapshot"))).as(fixture).isEmpty();
        }

        assertThat(pageIds).hasSize(3);
        assertThat(routes).containsExactlyInAnyOrder(
                "/demo/woms/work-orders",
                "/demo/woms/work-orders/edit",
                "/demo/woms/work-orders/{id}");
    }

    @Test
    void searchBindingUsesVisibleFilterInputsInsideItsForm() throws Exception {
        JsonNode bundle = load("work-order-search-1.2.0.json");

        for (JsonNode scope : List.of(bundle.path("page"), bundle.path("metadataVersion").path("snapshot"))) {
            JsonNode query = component(scope.path("components"), "query");
            JsonNode status = component(scope.path("components"), "status");
            JsonNode search = binding(scope.path("apiBindings"), "searchWorkOrders");

            assertThat(query.path("type").asText()).isEqualTo("text-field");
            assertThat(status.path("type").asText()).isEqualTo("select");
            assertThat(query.path("visibility").asBoolean()).isTrue();
            assertThat(status.path("visibility").asBoolean()).isTrue();
            assertThat(search.path("requestMapping").path("query").path("query").asText()).isEqualTo("query");
            assertThat(search.path("requestMapping").path("query").path("status").asText()).isEqualTo("status");
            assertThat(search.path("parentComponentId").asText())
                    .isEqualTo("61000000-0000-4000-8000-000000000001");
            assertThat(search.path("childComponentIds")).extracting(JsonNode::asText)
                    .containsExactly(query.path("id").asText(), status.path("id").asText());
            assertThat(search.path("linkedComponentIds")).extracting(JsonNode::asText)
                    .contains(query.path("id").asText(), status.path("id").asText());
        }
    }

    @Test
    void editorLoadsAndUpdatesTheSameSelectedWorkOrder() throws Exception {
        JsonNode bundle = load("work-order-create-edit-1.2.0.json");

        for (JsonNode scope : List.of(bundle.path("page"), bundle.path("metadataVersion").path("snapshot"))) {
            JsonNode load = binding(scope.path("apiBindings"), "loadWorkOrderForEdit");
            JsonNode update = binding(scope.path("apiBindings"), "updateWorkOrder");

            assertThat(load.path("httpMethod").asText()).isEqualTo("GET");
            assertThat(load.path("endpointUrl")).isEqualTo(update.path("endpointUrl"));
            assertThat(load.path("requestMapping").path("path").path("id").asText()).isEqualTo("id");
            assertThat(update.path("requestMapping").path("path").path("id").asText()).isEqualTo("id");
            assertThat(load.path("responseMapping").path("item").asText()).isEqualTo("body");
            assertThat(load.path("linkedComponentIds")).extracting(JsonNode::asText)
                    .contains(
                            "61000000-0000-4000-8000-000000000011",
                            "61000000-0000-4000-8000-000000000012",
                            "61000000-0000-4000-8000-000000000013");
        }
    }

    @Test
    void documentsOneToManyTasksAndManyToManyTechnicianOperations() throws Exception {
        JsonNode bindings = load("work-order-detail-1.2.0.json").path("page").path("apiBindings");
        JsonNode tasks = binding(bindings, "listWorkOrderTasks");

        assertThat(tasks.path("parentComponentId").asText())
                .isEqualTo("61000000-0000-4000-8000-000000000021");
        assertThat(tasks.path("childComponentIds")).extracting(JsonNode::asText)
                .containsExactly("61000000-0000-4000-8000-000000000022");
        assertThat(tasks.path("endpointUrl").asText())
                .isEqualTo("/api/demo/woms/work-orders/{id}/tasks");

        assertThat(binding(bindings, "listAssignedTechnicians").path("httpMethod").asText()).isEqualTo("GET");
        assertThat(binding(bindings, "listTechnicianAssignments").path("httpMethod").asText()).isEqualTo("GET");
        assertThat(binding(bindings, "assignTechnicianToWorkOrder").path("httpMethod").asText()).isEqualTo("POST");
        assertThat(binding(bindings, "removeTechnicianFromWorkOrder").path("httpMethod").asText()).isEqualTo("DELETE");
        assertThat(binding(bindings, "assignTechnicianToWorkOrder").path("endpointUrl").asText())
                .isEqualTo("/api/demo/woms/work-orders/{id}/technicians");
        assertThat(binding(bindings, "removeTechnicianFromWorkOrder").path("endpointUrl").asText())
                .isEqualTo("/api/demo/woms/work-orders/{id}/technicians/{technicianId}");
    }

    @Test
    void technicianAndAssignmentTablesUseSeparateListResponses() throws Exception {
        JsonNode bundle = load("work-order-detail-1.2.0.json");

        for (JsonNode scope : List.of(bundle.path("page"), bundle.path("metadataVersion").path("snapshot"))) {
            JsonNode technicians = binding(scope.path("apiBindings"), "listAssignedTechnicians");
            JsonNode assignments = binding(scope.path("apiBindings"), "listTechnicianAssignments");

            assertThat(technicians.path("endpointUrl").asText())
                    .isEqualTo("/api/demo/woms/work-orders/{id}/technicians");
            assertThat(technicians.path("linkedComponentIds")).extracting(JsonNode::asText)
                    .containsExactly("61000000-0000-4000-8000-000000000023");
            assertThat(assignments.path("endpointUrl").asText())
                    .isEqualTo("/api/demo/woms/work-orders/{id}/technician-assignments");
            assertThat(assignments.path("linkedComponentIds")).extracting(JsonNode::asText)
                    .containsExactly("61000000-0000-4000-8000-000000000024");
            assertThat(technicians.path("responseMapping").path("list").asText()).isEqualTo("body.data");
            assertThat(assignments.path("responseMapping").path("list").asText()).isEqualTo("body.data");

            assertThat(component(scope.path("components"), "technicians")
                    .path("tableConfig").path("columns")).extracting(column -> column.path("fieldKey").asText())
                    .containsExactly("technicianName");
            assertThat(component(scope.path("components"), "assignments")
                    .path("tableConfig").path("columns")).extracting(column -> column.path("fieldKey").asText())
                    .containsExactly("workOrderId", "technicianId");
        }
    }

    @Test
    void demoEntryPointDocumentsFixtureLocationAndEditorInitialization() throws Exception {
        String readme = Files.readString(Path.of("demo", "README.md"));

        assertThat(readme).contains("metadata/examples/woms/");
        assertThat(readme).contains("/demo/woms/...");
        assertThat(readme).contains("selected work-order id");
        assertThat(readme).contains("GET load binding and PUT update binding consume the same id");
    }

    @Test
    void rejectsBrokenComponentReferenceInDemoSnapshot() throws Exception {
        ObjectNode snapshot = (ObjectNode) load("work-order-detail-1.2.0.json")
                .path("metadataVersion").path("snapshot").deepCopy();
        ((ObjectNode) snapshot.withArray("apiBindings").get(0))
                .withArray("linkedComponentIds")
                .set(0, mapper.getNodeFactory().textNode("61000000-0000-4000-8000-000000000099"));

        assertThat(validator.validate("1.2.0", snapshot))
                .anyMatch(error -> error.message().contains("does not exist"));
    }

    private JsonNode binding(JsonNode bindings, String name) {
        for (JsonNode binding : bindings) {
            if (name.equals(binding.path("name").asText())) {
                return binding;
            }
        }
        throw new AssertionError("Missing binding: " + name);
    }

    private JsonNode component(JsonNode components, String fieldKey) {
        for (JsonNode component : components) {
            if (fieldKey.equals(component.path("fieldKey").asText())) {
                return component;
            }
        }
        throw new AssertionError("Missing component with fieldKey: " + fieldKey);
    }

    private JsonNode load(String fixture) throws Exception {
        return mapper.readTree(new File("metadata/examples/woms/" + fixture));
    }
}
