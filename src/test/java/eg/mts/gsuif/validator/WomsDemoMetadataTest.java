package eg.mts.gsuif.validator;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class WomsDemoMetadataTest {
    private static final Path FIXTURE_DIRECTORY = Path.of(
            System.getProperty("woms.fixture.dir", "metadata/examples/woms"));
    private static final Path DEMO_README = Path.of(
            System.getProperty("woms.demo.readme", "demo/README.md"));
    private static final Path WOMS_README = Path.of(
            System.getProperty("woms.readme", "metadata/examples/woms/README.md"));
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
            assertThat(componentFieldKeys(scope.path("components"))).doesNotContain("id");
            assertThat(load.path("responseMapping").path("item").asText()).isEqualTo("body");
            assertThat(load.path("linkedComponentIds")).extracting(JsonNode::asText)
                    .contains(
                            "61000000-0000-4000-8000-000000000011",
                            "61000000-0000-4000-8000-000000000012",
                            "61000000-0000-4000-8000-000000000013");
        }
    }

    @Test
    void composesSearchQueryFromRenderableComponentValues() throws Exception {
        JsonNode bundle = load("work-order-search-1.2.0.json");
        Map<String, String> values = Map.of(
                "query", "pump",
                "status", "OPEN",
                "page", "3");

        for (JsonNode scope : List.of(bundle.path("page"), bundle.path("metadataVersion").path("snapshot"))) {
            JsonNode search = binding(scope.path("apiBindings"), "searchWorkOrders");

            assertThat(composeRequestPart(scope, search, "query", values))
                    .containsExactly(
                            Map.entry("status", "OPEN"),
                            Map.entry("query", "pump"),
                            Map.entry("page", "3"));
        }
    }

    @Test
    void resolvesEditorLoadAndUpdateAgainstTheSameSelectedId() throws Exception {
        JsonNode bundle = load("work-order-create-edit-1.2.0.json");
        Map<String, String> context = Map.of("id", "WO-42");

        for (JsonNode scope : List.of(bundle.path("page"), bundle.path("metadataVersion").path("snapshot"))) {
            JsonNode load = binding(scope.path("apiBindings"), "loadWorkOrderForEdit");
            JsonNode update = binding(scope.path("apiBindings"), "updateWorkOrder");

            assertThat(resolveEndpoint(load, context))
                    .isEqualTo("/api/demo/woms/work-orders/WO-42");
            assertThat(resolveEndpoint(update, context))
                    .isEqualTo("/api/demo/woms/work-orders/WO-42");
        }
    }

    @Test
    void representativeListRowsCanRenderEveryLinkedTable() throws Exception {
        JsonNode bundle = load("work-order-detail-1.2.0.json");
        ObjectNode technicianRow = mapper.createObjectNode()
                .put("technicianId", "TECH-7")
                .put("technicianName", "Amina");
        ObjectNode assignmentRow = mapper.createObjectNode()
                .put("workOrderId", "WO-42")
                .put("technicianId", "TECH-7");

        for (JsonNode scope : List.of(bundle.path("page"), bundle.path("metadataVersion").path("snapshot"))) {
            assertLinkedTablesCanRender(
                    scope,
                    binding(scope.path("apiBindings"), "listAssignedTechnicians"),
                    technicianRow);
            assertLinkedTablesCanRender(
                    scope,
                    binding(scope.path("apiBindings"), "listTechnicianAssignments"),
                    assignmentRow);
        }
    }

    @Test
    void detailSummaryRendersReadOnlyWorkOrderFields() throws Exception {
        JsonNode bundle = load("work-order-detail-1.2.0.json");
        ObjectNode workOrder = mapper.createObjectNode()
                .put("orderNumber", "WO-42")
                .put("status", "OPEN")
                .put("dueDate", "2026-10-11");

        for (JsonNode scope : scopes(bundle)) {
            JsonNode getWorkOrder = binding(scope.path("apiBindings"), "getWorkOrder");

            assertThat(getWorkOrder.path("responseMapping").path("item").asText()).isEqualTo("body");
            Map<String, String> displayed = new LinkedHashMap<>();
            for (JsonNode linkedId : getWorkOrder.path("linkedComponentIds")) {
                JsonNode field = componentById(scope.path("components"), linkedId.asText());

                assertThat(field.path("visibility").asBoolean()).as(field.path("fieldKey").asText()).isTrue();
                assertThat(field.path("disabled").asBoolean()).as(field.path("fieldKey").asText()).isTrue();
                assertThat(field.has("formConfig")).isFalse();
                String fieldKey = field.path("fieldKey").asText();
                assertThat(workOrder.hasNonNull(fieldKey))
                        .as("work-order response supplies " + fieldKey)
                        .isTrue();
                displayed.put(fieldKey, workOrder.path(fieldKey).asText());
            }
            assertThat(displayed).containsExactly(
                    Map.entry("orderNumber", "WO-42"),
                    Map.entry("status", "OPEN"),
                    Map.entry("dueDate", "2026-10-11"));
            assertThat(component(scope.path("components"), "orderNumber").path("type").asText()).isEqualTo("text-field");
            assertThat(component(scope.path("components"), "status").path("type").asText()).isEqualTo("text-field");
            assertThat(component(scope.path("components"), "dueDate").path("type").asText()).isEqualTo("date-field");
            for (JsonNode apiBinding : scope.path("apiBindings")) {
                if (!"GET".equals(apiBinding.path("httpMethod").asText())) {
                    assertThat(apiBinding.path("linkedComponentIds"))
                            .extracting(JsonNode::asText)
                            .doesNotContain(
                                    "61000000-0000-4000-8000-000000000021",
                                    "61000000-0000-4000-8000-000000000025",
                                    "61000000-0000-4000-8000-000000000026");
                }
            }
        }
    }

    @Test
    void assignTechnicianObtainsTechnicianIdFromSelect() throws Exception {
        JsonNode bundle = load("work-order-detail-1.2.0.json");
        Map<String, String> values = Map.of("technicianToAssign", "TECH-9");

        for (JsonNode scope : scopes(bundle)) {
            JsonNode assign = binding(scope.path("apiBindings"), "assignTechnicianToWorkOrder");
            JsonNode source = referencedComponent(scope, assign, "technicianToAssign");

            assertThat(source.path("type").asText()).isEqualTo("select");
            assertThat(source.path("visibility").asBoolean()).isTrue();
            assertThat(source.path("disabled").asBoolean()).isFalse();
            assertThat(source.has("options")).isFalse();
            assertThat(source.has("selectConfig")).isFalse();
            assertThat(assign.path("parentComponentId").asText())
                    .isEqualTo("61000000-0000-4000-8000-000000000024");
            assertThat(assign.path("childComponentIds")).extracting(JsonNode::asText)
                    .containsExactly(source.path("id").asText());
            assertThat(composeRequestPart(scope, assign, "body", values))
                    .containsExactly(Map.entry("technicianId", "TECH-9"));
        }
    }

    @Test
    void removeTechnicianUsesSelectedAssignmentId() throws Exception {
        JsonNode bundle = load("work-order-detail-1.2.0.json");
        ObjectNode selectedAssignment = mapper.createObjectNode()
                .put("workOrderId", "WO-42")
                .put("technicianId", "TECH-7");
        Map<String, String> pageContext = Map.of("id", "WO-42");

        for (JsonNode scope : scopes(bundle)) {
            JsonNode remove = binding(scope.path("apiBindings"), "removeTechnicianFromWorkOrder");
            JsonNode assignments = componentById(
                    scope.path("components"), "61000000-0000-4000-8000-000000000024");
            Set<String> columns = new HashSet<>();
            assignments.path("tableConfig").path("columns")
                    .forEach(column -> columns.add(column.path("fieldKey").asText()));

            assertThat(remove.path("linkedComponentIds")).extracting(JsonNode::asText)
                    .containsExactly(assignments.path("id").asText());
            assertThat(remove.path("requestMapping").path("path").path("technicianId").asText())
                    .isEqualTo("technicianId");
            assertThat(columns).contains("technicianId");
            assertThat(componentFieldKeys(scope.path("components"))).doesNotContain("technicianId");

            String endpoint = remove.path("endpointUrl").asText();
            var pathMappings = remove.path("requestMapping").path("path").fields();
            while (pathMappings.hasNext()) {
                Map.Entry<String, JsonNode> mapping = pathMappings.next();
                String source = mapping.getValue().asText();
                String value;
                if (columns.contains(source)) {
                    assertThat(selectedAssignment.hasNonNull(source))
                            .as("selected assignment supplies " + source)
                            .isTrue();
                    value = selectedAssignment.path(source).asText();
                } else {
                    assertThat(pageContext).as("detail page context for " + source).containsKey(source);
                    value = pageContext.get(source);
                }
                endpoint = endpoint.replace("{" + mapping.getKey() + "}", value);
            }
            assertThat(endpoint).isEqualTo("/api/demo/woms/work-orders/WO-42/technicians/TECH-7");
        }
    }

    @Test
    void createAndEditRequestFieldsComeFromEditorComponents() throws Exception {
        JsonNode bundle = load("work-order-create-edit-1.2.0.json");
        Map<String, String> values = Map.of(
                "orderNumber", "WO-15",
                "dueDate", "2026-12-01");
        ObjectNode loaded = mapper.createObjectNode()
                .put("orderNumber", "WO-15")
                .put("dueDate", "2026-12-01");

        for (JsonNode scope : scopes(bundle)) {
            JsonNode create = binding(scope.path("apiBindings"), "createWorkOrder");
            JsonNode update = binding(scope.path("apiBindings"), "updateWorkOrder");
            JsonNode load = binding(scope.path("apiBindings"), "loadWorkOrderForEdit");

            assertThat(componentFieldKeys(scope.path("components"))).doesNotContain("id");
            assertThat(create.path("requestMapping").has("path")).isFalse();
            assertThat(load.path("requestMapping").path("path").path("id").asText()).isEqualTo("id");
            assertThat(update.path("requestMapping").path("path").path("id").asText()).isEqualTo("id");
            assertThat(load.path("endpointUrl")).isEqualTo(update.path("endpointUrl"));

            JsonNode orderNumber = referencedComponent(scope, create, "orderNumber");
            JsonNode dueDate = referencedComponent(scope, create, "dueDate");
            assertThat(orderNumber.path("visibility").asBoolean()).isTrue();
            assertThat(dueDate.path("visibility").asBoolean()).isTrue();
            assertThat(orderNumber.path("disabled").asBoolean()).isFalse();
            assertThat(dueDate.path("disabled").asBoolean()).isFalse();
            assertThat(create.path("childComponentIds")).extracting(JsonNode::asText)
                    .containsExactly(orderNumber.path("id").asText(), dueDate.path("id").asText());
            assertThat(referencedComponent(scope, update, "orderNumber").path("id"))
                    .isEqualTo(orderNumber.path("id"));
            assertThat(referencedComponent(scope, update, "dueDate").path("id"))
                    .isEqualTo(dueDate.path("id"));
            assertThat(composeRequestPart(scope, create, "body", values)).containsExactly(
                    Map.entry("orderNumber", "WO-15"),
                    Map.entry("dueDate", "2026-12-01"));
            assertThat(composeRequestPart(scope, update, "body", values)).containsExactly(
                    Map.entry("orderNumber", "WO-15"),
                    Map.entry("dueDate", "2026-12-01"));

            Map<String, String> displayed = new LinkedHashMap<>();
            for (JsonNode linkedId : load.path("linkedComponentIds")) {
                JsonNode linked = componentById(scope.path("components"), linkedId.asText());
                String fieldKey = linked.path("fieldKey").asText();
                if ("workOrder".equals(fieldKey)) {
                    assertThat(linked.path("type").asText()).isEqualTo("form");
                    continue;
                }
                assertThat(loaded.hasNonNull(fieldKey))
                        .as("edit response supplies " + fieldKey)
                        .isTrue();
                displayed.put(fieldKey, loaded.path(fieldKey).asText());
            }
            assertThat(displayed).containsExactly(
                    Map.entry("orderNumber", "WO-15"),
                    Map.entry("dueDate", "2026-12-01"));
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
        String readme = Files.readString(DEMO_README);

        assertThat(readme).contains("metadata/examples/woms/");
        assertThat(readme).contains("/demo/woms/...");
        assertThat(readme).contains("selected work-order id");
        assertThat(readme).contains("GET load binding and PUT update binding consume the same id");
    }

    @Test
    void documentsIllustrativeContractsAndUnspecifiedRuntimeBehavior() throws Exception {
        String readme = Files.readString(WOMS_README);

        assertThat(readme).contains("formConfig.submitLabel always labels a built-in submit action");
        assertThat(readme).contains("technicianToAssign");
        assertThat(readme).contains("selected assignment row");
        assertThat(readme).contains("The editor has no component whose fieldKey is id");
        assertThat(readme).contains("does not reference a technician catalog API");
        assertThat(readme).contains("The Save work order label does not choose POST or PUT");
        assertThat(readme).contains("no runtime implementation");
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

    private List<JsonNode> scopes(JsonNode bundle) {
        return List.of(bundle.path("page"), bundle.path("metadataVersion").path("snapshot"));
    }

    private Set<String> componentFieldKeys(JsonNode components) {
        Set<String> fieldKeys = new HashSet<>();
        for (JsonNode component : components) {
            if (component.hasNonNull("fieldKey")) {
                fieldKeys.add(component.path("fieldKey").asText());
            }
        }
        return fieldKeys;
    }

    private JsonNode referencedComponent(JsonNode scope, JsonNode binding, String fieldKey) {
        Set<String> referenced = new HashSet<>();
        binding.path("linkedComponentIds").forEach(id -> referenced.add(id.asText()));
        binding.path("childComponentIds").forEach(id -> referenced.add(id.asText()));
        JsonNode found = null;
        for (JsonNode component : scope.path("components")) {
            if (!fieldKey.equals(component.path("fieldKey").asText())) {
                continue;
            }
            if (!referenced.contains(component.path("id").asText())) {
                continue;
            }
            if (found != null) {
                throw new AssertionError("Multiple metadata sources for " + fieldKey);
            }
            found = component;
        }
        if (found == null) {
            throw new AssertionError("Binding does not reference a component for " + fieldKey);
        }
        return found;
    }

    private Map<String, String> composeRequestPart(
            JsonNode scope,
            JsonNode binding,
            String part,
            Map<String, String> values) {
        Map<String, String> request = new LinkedHashMap<>();
        binding.path("requestMapping").path(part).fields().forEachRemaining(entry -> {
            String sourceField = entry.getValue().asText();
            JsonNode source = component(scope.path("components"), sourceField);

            assertThat(source.path("visibility").asBoolean())
                    .as(sourceField + " must be visible to supply " + part)
                    .isTrue();
            assertThat(source.path("disabled").asBoolean())
                    .as(sourceField + " must be enabled to supply " + part)
                    .isFalse();
            assertThat(values)
                    .as("input values for " + sourceField)
                    .containsKey(sourceField);
            request.put(entry.getKey(), values.get(sourceField));
        });
        return request;
    }

    private String resolveEndpoint(JsonNode binding, Map<String, String> context) {
        String endpoint = binding.path("endpointUrl").asText();
        var pathMappings = binding.path("requestMapping").path("path").fields();
        while (pathMappings.hasNext()) {
            Map.Entry<String, JsonNode> mapping = pathMappings.next();
            String sourceField = mapping.getValue().asText();

            assertThat(context)
                    .as("page context for " + sourceField)
                    .containsKey(sourceField);
            endpoint = endpoint.replace(
                    "{" + mapping.getKey() + "}",
                    context.get(sourceField));
        }
        assertThat(endpoint).doesNotContain("{", "}");
        return endpoint;
    }

    private void assertLinkedTablesCanRender(JsonNode scope, JsonNode binding, JsonNode row) {
        for (JsonNode linkedId : binding.path("linkedComponentIds")) {
            JsonNode linked = componentById(scope.path("components"), linkedId.asText());
            if (!"table".equals(linked.path("type").asText())) {
                continue;
            }
            for (JsonNode column : linked.path("tableConfig").path("columns")) {
                String fieldKey = column.path("fieldKey").asText();
                assertThat(row.hasNonNull(fieldKey))
                        .as(binding.path("name").asText() + " response supplies " + fieldKey)
                        .isTrue();
            }
        }
    }

    private JsonNode componentById(JsonNode components, String id) {
        for (JsonNode component : components) {
            if (id.equals(component.path("id").asText())) {
                return component;
            }
        }
        throw new AssertionError("Missing component with id: " + id);
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
        return mapper.readTree(FIXTURE_DIRECTORY.resolve(fixture).toFile());
    }
}
