package eg.mts.gsuif.generation;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Internal versioned inputs. API signatures are derived from a packaged contract. */
public record GenerationSpecification(String specVersion, String basePackage, EntitySpec entity,
        OpenApiSpec openApi, Map<String, Set<SupportedRole>> operationRoles, AngularSpec angular) {
    public record EntitySpec(String className, String tableName, List<FieldSpec> fields, boolean enversAudited) { }
    public record FieldSpec(String name, String columnName, String javaType, boolean nullable,
            Integer columnLength, String enumType) { }
    public record OpenApiSpec(String contractId, String contractVersion, Map<UUID, String> bindingOperations) { }
    public record AngularSpec(String className, Map<UUID, List<String>> tableColumns) { }
    public enum SupportedRole { ROLE_ADMIN, ROLE_USER }
}
