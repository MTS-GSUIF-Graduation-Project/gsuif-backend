package eg.mts.gsuif.generation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import eg.mts.gsuif.entity.GsuifPage;
import eg.mts.gsuif.entity.GsuifProject;
import eg.mts.gsuif.entity.MetadataVersion;
import eg.mts.gsuif.generation.GenerationSpecification.*;
import eg.mts.gsuif.validator.MetadataSchemaValidator;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.*;

final class CompleteOutputTestFixture {
    final ObjectMapper mapper = new ObjectMapper();
    final List<String> ids = List.of("scanStockUnits", "registerStockUnit", "inspectStockUnit",
            "replaceStockUnit", "discardStockUnit");

    CompleteOutputDescriptor auditedSchemaDescriptor(CompleteOutputDescriptor d, String table) {
        return new CompleteOutputDescriptor(d.metadataVersionId(), d.metadataVersionNumber(), d.metadataSchemaVersion(),
                d.specificationVersion(), d.validatedSnapshotJson(), d.pageId(), d.pageName(), d.pageRoute(),
                d.projectId(), d.projectName(), d.framework(), d.catalogVersion(), d.catalogAssets(),
                d.catalogProviderVersion(), d.standardRefs(), d.mode(), d.basePackage(), d.entityName(), table,
                true, d.fields(), d.angularClassName(), d.angularTableColumns(), d.contractId(),
                d.contractVersion(), d.contractResourceSha256(), d.contractDtoPackage(), d.contractServicePackage(),
                d.contractInterfacePackage(), d.contractServiceName(), d.contractInterfaceName(), d.dtoName(),
                d.pageDtoName(), d.createRequestName(), d.updateRequestName(), d.createRules(), d.createFields(),
                d.updateRules(), d.editableFields(), d.responseFields(), d.operations());
    }

    CompleteOutputDescriptor schemaDescriptor(CompleteOutputDescriptor d, String table,
                                                               List<FieldSpec> fields) {
        return new CompleteOutputDescriptor(d.metadataVersionId(), d.metadataVersionNumber(), d.metadataSchemaVersion(),
                d.specificationVersion(), d.validatedSnapshotJson(), d.pageId(), d.pageName(), d.pageRoute(),
                d.projectId(), d.projectName(), d.framework(), d.catalogVersion(), d.catalogAssets(),
                d.catalogProviderVersion(), d.standardRefs(), d.mode(), d.basePackage(), d.entityName(), table,
                d.enversAudited(), fields, d.angularClassName(), d.angularTableColumns(), d.contractId(),
                d.contractVersion(), d.contractResourceSha256(), d.contractDtoPackage(), d.contractServicePackage(),
                d.contractInterfacePackage(), d.contractServiceName(), d.contractInterfaceName(), d.dtoName(),
                d.pageDtoName(), d.createRequestName(), d.updateRequestName(), d.createRules(), d.createFields(),
                d.updateRules(), d.editableFields(), d.responseFields(), d.operations());
    }

    void addBoundLabel(ObjectNode snapshot, String label) {
        UUID id = new UUID(0, 100);
        ObjectNode component = snapshot.withArray("components").addObject();
        component.put("id", id.toString()); component.put("type", "text-field");
        component.put("fieldKey", "label"); component.put("label", label);
        component.putObject("position").put("row", 0).put("col", 0);
        component.putObject("size").put("width", 6).put("height", 1);
        component.put("visibility", true); component.put("disabled", false);
        ((ObjectNode) snapshot.withArray("apiBindings").get(0)).putArray("linkedComponentIds").add(id.toString());
    }

    GenerationCapabilityPreflight preflight() {
        var registry = new PackagedContractRegistry("/generation/stock-unit-contracts.yaml");
        return new GenerationCapabilityPreflight(
                new GenerationContextBuilder(mapper, new MetadataSchemaValidator(), registry), registry);
    }

    GenerationSpecification specification() {
        Map<UUID, String> bindings = new java.util.LinkedHashMap<>();
        Map<String, Set<SupportedRole>> roles = new java.util.LinkedHashMap<>();
        for (int i = 0; i < ids.size(); i++) {
            bindings.put(new UUID(0, i + 1), ids.get(i));
            roles.put(ids.get(i), Set.of(SupportedRole.ROLE_ADMIN));
        }
        return new GenerationSpecification("1.0.0", "com.example.inventory",
                new EntitySpec("StockUnit", "stock_unit", List.of(
                        new FieldSpec("label", "label", "String", false, 100, null),
                        new FieldSpec("condition", "condition", "StockUnitCondition", false, null, "StockUnitCondition"),
                        new FieldSpec("retiredOn", "retired_on", "LocalDate", true, null, null)), false),
                new OpenApiSpec("stock-unit-api", "2.0.0", bindings), roles,
                new AngularSpec("StockUnit", Map.of()));
    }

    GenerationSpecification pagedSpecification() {
        var base = specification();
        return new GenerationSpecification(base.specVersion(), base.basePackage(), base.entity(),
                new OpenApiSpec("stock-unit-api", "3.0.0", base.openApi().bindingOperations()),
                base.operationRoles(), base.angular());
    }

    ObjectNode pagedSnapshot() {
        ObjectNode selected = snapshot("condition");
        ObjectNode query = (ObjectNode) selected.withArray("apiBindings").get(0)
                .path("requestMapping").path("query");
        query.put("page", "page").put("size", "size");
        return selected;
    }

    ObjectNode snapshot(String queryName) {
        var snapshot = mapper.createObjectNode();
        snapshot.putArray("components");
        var bindings = snapshot.putArray("apiBindings");
        String[] verbs = {"GET", "POST", "GET", "PUT", "DELETE"};
        for (int i = 0; i < verbs.length; i++) {
            ObjectNode binding = bindings.addObject();
            binding.put("id", new UUID(0, i + 1).toString());
            binding.put("httpMethod", verbs[i]);
            binding.put("endpointUrl", "/api/v1/stock-units" + (i > 1 ? "/{id}" : ""));
            binding.putObject("headers");
            ObjectNode request = binding.putObject("requestMapping");
            if (i == 0) request.putObject("query").put(queryName, queryName).put("label", "label");
            if (i > 1) request.putObject("path").put("id", "id");
            if (i == 1 || i == 3) request.putObject("body")
                    .put("label", "label").put("condition", "condition").put("retiredOn", "retiredOn");
            ObjectNode response = binding.putObject("responseMapping");
            if (i == 0) response.put("list", "body.data"); else response.put("item", "body");
        }
        return snapshot;
    }

    MetadataVersion version(ObjectNode snapshot) throws Exception {
        var project = new GsuifProject(); project.setName("Fixture");
        var page = new GsuifPage(); page.setProject(project);
        page.setName("Inventory"); page.setRoute("/inventory");
        var version = new MetadataVersion(); version.setPage(page);
        version.setVersion(1); version.setSchemaVersion("1.0.0");
        version.setSnapshot(mapper.writeValueAsString(snapshot));
        return version;
    }
}
