package eg.mts.gsuif.generation;

import eg.mts.gsuif.generation.GenerationContext.Target;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.yaml.snakeyaml.Yaml;

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
        String testPath = "src/test/java/" + base.replace('.', '/') + "/generation/GeneratedControllerBehaviorTest.java";
        inputs.put(testPath, behaviorTest(base, entity, createOperationId(contract)).getBytes(StandardCharsets.UTF_8));
        return Map.copyOf(inputs);
    }

    private static String createOperationId(String contract) {
        String path = "/openapi/" + contract + ".yaml";
        try (var stream = PackagedConsumerInputs.class.getResourceAsStream(path)) {
            if (stream == null) throw new IllegalStateException("Packaged OpenAPI contract is missing: " + path);
            Map<?, ?> root = new Yaml().load(stream);
            if (root.get("paths") instanceof Map<?, ?> paths) {
                for (Object value : paths.values()) {
                    if (value instanceof Map<?, ?> pathItem && pathItem.get("post") instanceof Map<?, ?> post
                            && post.get("operationId") instanceof String operationId
                            && operationId.matches("[a-z][A-Za-z0-9]*")) return operationId;
                }
            }
            throw new IllegalStateException("OpenAPI contract lacks a valid POST operationId: " + path);
        } catch (IOException ex) {
            throw new IllegalStateException("Could not read packaged OpenAPI contract: " + path, ex);
        }
    }

    private static String behaviorTest(String base, String entity, String operationId) {
        return """
                package %s.generation;
                import %s.controller.%sController;
                import %s.dto.Create%sRequest;
                import %s.dto.%sDto;
                import %s.service.%sService;
                import org.junit.jupiter.api.Test;
                import org.springframework.http.HttpStatus;
                import static org.junit.jupiter.api.Assertions.*;
                import static org.mockito.Mockito.*;
                class GeneratedControllerBehaviorTest {
                    @Test void createDelegatesAndReturnsCreatedBody() {
                        var service = mock(%sService.class);
                        var request = new Create%sRequest();
                        var expected = new %sDto();
                        when(service.create(request)).thenReturn(expected);
                        var controller = new %sController(service);
                        var response = controller.%s(request);
                        assertEquals(HttpStatus.CREATED, response.getStatusCode());
                        assertSame(expected, response.getBody().body());
                        verify(service).create(request);
                    }
                }
                """.formatted(base, base, entity, base, entity, base, entity, base, entity,
                        entity, entity, entity, entity, operationId);
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
