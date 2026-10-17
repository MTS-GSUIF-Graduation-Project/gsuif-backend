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
        String version = specification.openApi().contractVersion();
        if (!"1.0.0".equals(version))
            throw new GenerationValidationException(List.of("specification.openApi.contractVersion: packaged fragment consumer inputs are available only for 1.0.0; complete-output assembly is not available"));
        String entity = switch (contract) {
            case "asset-ticket-api" -> "AssetTicket";
            case "service-request-api" -> "ServiceRequest";
            case "case-record-api" -> "CaseRecord";
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
        String testRoot = "src/test/java/" + base.replace('.', '/') + "/generation/";
        if (targets.contains(Target.ENTITY)) {
            inputs.put(testRoot + "GeneratedEntityBehaviorTest.java",
                    entityBehaviorTest(base, specification.entity().className()).getBytes(StandardCharsets.UTF_8));
        }
        if (targets.contains(Target.CONTROLLER)) {
            inputs.put(testRoot + "GeneratedControllerBehaviorTest.java",
                    behaviorTest(base, entity, createOperationId(contract, version)).getBytes(StandardCharsets.UTF_8));
        }
        return Map.copyOf(inputs);
    }

    private static String entityBehaviorTest(String base, String entity) {
        return """
                package %s.generation;
                import %s.entity.%s;
                import jakarta.persistence.Entity;
                import org.junit.jupiter.api.Test;
                import static org.junit.jupiter.api.Assertions.*;
                class GeneratedEntityBehaviorTest {
                    @Test void transientIdentityAndJpaMapping() {
                        var first = new %s();
                        var second = new %s();
                        assertTrue(%s.class.isAnnotationPresent(Entity.class));
                        assertEquals(first, first);
                        assertNotEquals(first, second);
                        assertNull(first.getId());
                    }
                }
                """.formatted(base, base, entity, entity, entity, entity);
    }

    private static String createOperationId(String contract, String version) {
        var entry = new PackagedContractRegistry().find(contract, version);
        if (entry == null)
            throw new GenerationValidationException(List.of("specification.openApi: unsupported packaged contract version"));
        return entry.operation(PackagedContractRegistry.CrudAction.CREATE);
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
