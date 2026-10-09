package eg.mts.gsuif.generation;

import eg.mts.gsuif.generation.GenerationContext.Target;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Build inputs for the selected packaged OpenAPI interface and DTOs. */
final class PackagedConsumerInputs {
    private PackagedConsumerInputs() { }

    static Map<String, byte[]> forBuild(GenerationSpecification specification, Set<Target> targets) {
        if (specification == null || specification.openApi() == null ||
                (!targets.contains(Target.ENTITY) && !targets.contains(Target.CONTROLLER))) return Map.of();
        String contract = specification.openApi().contractId();
        if (contract == null) return Map.of();
        String entity = switch (contract) {
            case "asset-ticket-api" -> "AssetTicket";
            case "service-request-api" -> "ServiceRequest";
            default -> null;
        };
        if (entity == null) return Map.of();
        String base = specification.basePackage();
        if (base == null || !base.matches("[A-Za-z_$][A-Za-z0-9_$]*(\\.[A-Za-z_$][A-Za-z0-9_$]*)*"))
            return Map.of(); // The generation context reports the specification error.
        String sourceRoot = "com/example/fixture/";
        List<String> names = List.of("api/ApiUtil.java", "api/" + entity + "Api.java",
                "dto/" + entity + "Dto.java", "dto/" + entity + "DtoResponse.java",
                "dto/" + entity + "Page.java", "dto/" + entity + "PageResponse.java",
                "dto/" + entity + "Status.java", "dto/" + entity + "VoidResponse.java",
                "dto/Create" + entity + "Request.java", "dto/Update" + entity + "Request.java");
        Map<String, byte[]> inputs = new LinkedHashMap<>();
        String destinationRoot = "src/main/java/" + base.replace('.', '/') + "/";
        for (String name : names) inputs.put(destinationRoot + name,
                resource(contract, sourceRoot + name, base));
        inputs.put("src/main/java/org/openapitools/configuration/EnumConverterConfiguration.java",
                resource(contract, "org/openapitools/configuration/EnumConverterConfiguration.java", base));
        String service = """
                package %s.service;
                import java.util.UUID;
                import %s.dto.*;
                public interface %sService {
                    %sDto create(Create%sRequest request);
                    %sDto getById(UUID id);
                    %sPage list(%sStatus status);
                    %sDto update(UUID id, Update%sRequest request);
                    void delete(UUID id);
                }
                """.formatted(base, base, entity, entity, entity, entity, entity,
                        entity, entity, entity);
        inputs.put(destinationRoot + "service/" + entity + "Service.java", service.getBytes(StandardCharsets.UTF_8));
        return Map.copyOf(inputs);
    }

    private static byte[] resource(String contract, String name, String base) {
        String path = "/consumer-contracts/" + contract + "/" + name;
        try (var stream = PackagedConsumerInputs.class.getResourceAsStream(path)) {
            if (stream == null) throw new IllegalStateException("Packaged consumer source is missing: " + path);
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8)
                    .replace("com.example.fixture", base).getBytes(StandardCharsets.UTF_8);
        } catch (IOException ex) { throw new IllegalStateException("Could not read packaged consumer source: " + path, ex); }
    }
}
