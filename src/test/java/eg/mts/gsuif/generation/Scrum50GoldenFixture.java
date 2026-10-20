package eg.mts.gsuif.generation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import eg.mts.gsuif.entity.GsuifPage;
import eg.mts.gsuif.entity.GsuifProject;
import eg.mts.gsuif.entity.MetadataVersion;
import eg.mts.gsuif.generation.GenerationContext.Target;
import eg.mts.gsuif.generation.GenerationSpecification.AngularSpec;
import eg.mts.gsuif.generation.GenerationSpecification.EntitySpec;
import eg.mts.gsuif.generation.GenerationSpecification.FieldSpec;
import eg.mts.gsuif.generation.GenerationSpecification.OpenApiSpec;
import eg.mts.gsuif.generation.GenerationSpecification.SupportedRole;
import eg.mts.gsuif.validator.MetadataSchemaValidator;

import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

final class Scrum50GoldenFixture {
    static final String BASE_PACKAGE = "com.example.fixture";
    static final String CONTROLLER_PATH =
            "src/main/java/com/example/fixture/controller/AssetTicketController.java";
    static final String VALIDATOR_PATH =
            "src/main/java/com/example/fixture/validation/AssetTicketGeneratedValidator.java";
    static final String ANGULAR_TYPESCRIPT_PATH =
            "src/app/generated/asset-ticket.component.ts";
    static final String ANGULAR_HTML_PATH =
            "src/app/generated/asset-ticket.component.html";
    static final String ANGULAR_VISIBILITY_PATH =
            "src/app/generated/angular-visibility.ts";

    private final ObjectMapper json = new ObjectMapper();

    GenerationResult generate(Set<Target> targets) throws Exception {
        var builder = new GenerationContextBuilder(json, new MetadataSchemaValidator());
        return GenerationEngine.templateOnly(builder).generate(version(), specification(), targets, "spring-angular");
    }

    private MetadataVersion version() throws Exception {
        ObjectNode snapshot = snapshot();
        var project = new GsuifProject();
        project.setName("SCRUM-50 Golden Fixture");
        var page = new GsuifPage();
        page.setProject(project);
        page.setName("Asset Tickets");
        page.setRoute("/asset-tickets");
        var version = new MetadataVersion();
        version.setPage(page);
        version.setSchemaVersion("1.0.0");
        version.setSnapshot(json.writeValueAsString(snapshot));
        return version;
    }

    private ObjectNode snapshot() {
        ObjectNode snapshot = json.createObjectNode();
        var components = snapshot.putArray("components");
        UUID textId = new UUID(0, 10);
        UUID selectId = new UUID(0, 11);
        UUID tableId = new UUID(0, 12);
        component(components.addObject(), textId, "text-field", "Title", "title", 0);
        component(components.addObject(), selectId, "select", "Status", "status", 1);
        component(components.addObject(), tableId, "table", "Tickets", null, 2);

        String[] verbs = {"GET", "POST", "GET", "PUT", "DELETE"};
        for (int i = 0; i < verbs.length; i++) {
            ObjectNode binding = snapshot.withArray("apiBindings").addObject();
            binding.put("id", new UUID(0, i + 1).toString());
            binding.put("httpMethod", verbs[i]);
            binding.put("endpointUrl", "/api/v1/asset-tickets" + (i > 1 ? "/{id}" : ""));
            binding.putObject("headers");
            ObjectNode request = binding.putObject("requestMapping");
            if (i == 0) {
                request.putObject("query").put("status", "status");
                binding.putArray("linkedComponentIds")
                        .add(textId.toString()).add(selectId.toString()).add(tableId.toString());
            }
            if (i > 1) request.putObject("path").put("id", "id");
            if (i == 1 || i == 3)
                request.putObject("body").put("title", "title").put("status", "status");
            ObjectNode response = binding.putObject("responseMapping");
            if (i == 0) response.put("list", "body.data");
            else response.put("item", "body");
        }
        return snapshot;
    }

    private static void component(ObjectNode component, UUID id, String type, String label,
                                  String fieldKey, int row) {
        component.put("id", id.toString());
        component.put("type", type);
        component.put("label", label);
        if (fieldKey != null) component.put("fieldKey", fieldKey);
        component.putObject("position").put("row", row).put("col", 0);
        component.putObject("size").put("width", 6).put("height", 1);
        component.put("visibility", true);
        component.put("disabled", false);
    }

    private GenerationSpecification specification() {
        Map<UUID, String> bindings = new LinkedHashMap<>();
        Map<String, Set<SupportedRole>> roles = new LinkedHashMap<>();
        String[] methods = {"listAssetTickets", "createAssetTicket", "getAssetTicketById",
                "updateAssetTicket", "deleteAssetTicket"};
        for (int i = 0; i < methods.length; i++) {
            bindings.put(new UUID(0, i + 1), methods[i]);
            roles.put(methods[i], EnumSet.of(SupportedRole.ROLE_USER));
        }
        return new GenerationSpecification("1.0.0", BASE_PACKAGE,
                new EntitySpec("AssetTicket", "asset_ticket", List.of(
                        new FieldSpec("title", "title", "String", false, 100, null),
                        new FieldSpec("status", "status", "AssetTicketStatus", false, null,
                                "AssetTicketStatus")), false),
                new OpenApiSpec("asset-ticket-api", "1.0.0", bindings), roles,
                new AngularSpec("AssetTicket", Map.of(new UUID(0, 12), List.of("id", "title"))));
    }
}
