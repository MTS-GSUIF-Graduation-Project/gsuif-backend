package eg.mts.gsuif.generation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.TextNode;
import eg.mts.gsuif.generation.GenerationCapabilityPreflight.OutputMode;
import eg.mts.gsuif.generation.PackagedContractRegistry.CrudAction;

import java.io.InputStream;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Converts a preflight-validated context into a typed, exact-version descriptor. */
final class CompleteOutputDescriptorFactory {
    private final PackagedContractLoader loader;

    CompleteOutputDescriptorFactory(PackagedContractRegistry registry) {
        loader = new PackagedContractLoader(Objects.requireNonNull(registry));
    }

    CompleteOutputDescriptor create(GenerationContext context, OutputMode mode) {
        var spec = Objects.requireNonNull(context.specification());
        List<String> errors = new ArrayList<>();
        var contract = loader.load(spec.openApi(), errors);
        if (contract == null || !errors.isEmpty()) throw new GenerationValidationException(errors);
        if (!contract.descriptor().fullUpdate())
            throw new GenerationValidationException(List.of("specification.openApi.contractVersion: complete output requires the full-update contract profile"));
        if (!contract.descriptor().id().equals(spec.openApi().contractId())
                || !contract.descriptor().version().equals(spec.openApi().contractVersion()))
            throw new GenerationValidationException(List.of("specification.openApi: selected contract differs from registered descriptor"));
        List<CompleteOutputDescriptor.Operation> operations = new ArrayList<>();
        for (CrudAction action : List.of(CrudAction.LIST, CrudAction.CREATE, CrudAction.READ,
                CrudAction.UPDATE, CrudAction.DELETE)) {
            String id = contract.descriptor().operations().get(action);
            var selected = contract.operations().get(id);
            UUID binding = spec.openApi().bindingOperations().entrySet().stream()
                    .filter(entry -> id.equals(entry.getValue())).map(Map.Entry::getKey).findFirst().orElse(null);
            if (binding == null || selected == null || spec.operationRoles().get(id) == null)
                throw new GenerationValidationException(List.of("specification.openApi.bindingOperations: all five mapped CRUD operations required"));
            operations.add(new CompleteOutputDescriptor.Operation(action, binding, id, selected.verb(), selected.path(),
                    selected.request(), selected.responseDto(), selected.successStatus(), selected.successEnvelope(), selected.errorEnvelopes(),
                    selected.queryFields(), Set.copyOf(spec.operationRoles().get(id))));
        }
        Map<String, Object> api = contract.api();
        String dtoPackage = (String) api.get("dtoPackage");
        String servicePackage = (String) api.get("servicePackage");
        String interfacePackage = (String) api.get("interfacePackage");
        if (!Objects.equals(dtoPackage, spec.basePackage() + ".dto")
                || !Objects.equals(servicePackage, spec.basePackage() + ".service")
                || !Objects.equals(interfacePackage, spec.basePackage() + ".api"))
            throw new GenerationValidationException(List.of(
                    "specification.openApi: selected contract packages differ from complete-output basePackage"));
        var catalog = context.catalog();
        Map<String, GenerationCatalog.Choice> assets = Map.of(
                "entity", catalog.entity(), "controller", catalog.controller(),
                "angularTypescript", catalog.angularTypescript(), "angularHtml", catalog.angularHtml());
        var page = context.metadataVersion().getPage();
        return new CompleteOutputDescriptor(context.metadataVersion().getId(), context.metadataVersion().getVersion(),
                context.metadataVersion().getSchemaVersion(), spec.specVersion(), canonicalJson(context.snapshot()),
                page.getId(), page.getName(), page.getRoute(), page.getProject().getId(),
                page.getProject().getName(), context.framework(), catalog.version(), assets,
                catalog.providerVersion(), catalog.standardRefs(), mode, spec.basePackage(), spec.entity().className(),
                spec.entity().tableName(), spec.entity().enversAudited(), spec.entity().fields(),
                spec.angular().className(), spec.angular().tableColumns(), spec.openApi().contractId(),
                spec.openApi().contractVersion(), resourceHash(contract.descriptor().resource()),
                dtoPackage, servicePackage, interfacePackage,
                (String) api.get("serviceInterface"), (String) api.get("interfaceClass"),
                (String) api.get("dtoClass"), (String) api.get("pageDtoClass"),
                (String) api.get("createRequestClass"), (String) api.get("updateRequestClass"),
                contract.createFields(), contract.createSchemas(),
                contract.updateFields(), contract.updateSchemas(), contract.dtoSchemas(), operations);
    }

    private static String canonicalJson(JsonNode node) {
        if (node.isObject()) {
            List<String> names = new ArrayList<>();
            node.fieldNames().forEachRemaining(names::add);
            names.sort(String::compareTo);
            List<String> values = new ArrayList<>();
            for (String name : names) values.add(TextNode.valueOf(name) + ":" + canonicalJson(node.get(name)));
            return "{" + String.join(",", values) + "}";
        }
        if (node.isArray()) {
            List<String> values = new ArrayList<>();
            node.forEach(value -> values.add(canonicalJson(value)));
            return "[" + String.join(",", values) + "]";
        }
        return node.toString();
    }

    private static String resourceHash(String resource) {
        try (InputStream input = CompleteOutputDescriptorFactory.class.getResourceAsStream(resource)) {
            if (input == null) throw new IllegalStateException("selected contract resource missing");
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(input.readAllBytes()));
        } catch (Exception ex) { throw new IllegalStateException("cannot hash selected contract resource", ex); }
    }

}
