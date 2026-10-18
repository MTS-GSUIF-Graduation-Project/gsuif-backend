package eg.mts.gsuif.generation;

import eg.mts.gsuif.generation.GenerationCapabilityPreflight.OutputMode;
import eg.mts.gsuif.generation.GenerationSpecification.FieldSpec;
import eg.mts.gsuif.generation.GenerationSpecification.SupportedRole;
import eg.mts.gsuif.generation.PackagedContractRegistry.CrudAction;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.Collections;
import java.util.TreeMap;
import java.util.TreeSet;

/** Validated inputs for a future complete package. This type is never emitted into that package. */
record CompleteOutputDescriptor(UUID metadataVersionId, Integer metadataVersionNumber,
        String metadataSchemaVersion, String specificationVersion,
        String validatedSnapshotJson, UUID pageId, String pageName, String pageRoute,
        UUID projectId, String projectName,
        String framework, String catalogVersion, Map<String, GenerationCatalog.Choice> catalogAssets,
        String catalogProviderVersion, List<String> standardRefs, OutputMode mode,
        String basePackage, String entityName, String tableName, boolean enversAudited,
        List<FieldSpec> fields, String angularClassName, Map<UUID, List<String>> angularTableColumns,
        String contractId, String contractVersion, String contractResourceSha256,
        String contractDtoPackage, String contractServicePackage, String contractInterfacePackage,
        String contractServiceName, String contractInterfaceName,
        String dtoName, String pageDtoName, String createRequestName, String updateRequestName,
        Map<String, PackagedContractLoader.UpdateField> createRules,
        Map<String, PackagedContractLoader.FieldSchema> createFields,
        Map<String, PackagedContractLoader.UpdateField> updateRules,
        Map<String, PackagedContractLoader.FieldSchema> editableFields,
        Map<String, PackagedContractLoader.FieldSchema> responseFields, List<Operation> operations) {
    record Operation(CrudAction action, UUID bindingId, String operationId, String method, String path,
            String requestDto, String responseDto, int successStatus, String successEnvelope,
            Map<Integer, String> errorEnvelopes,
            Map<String, PackagedContractLoader.QueryParameter> queryFields,
            PackagedContractLoader.Ordering ordering, Set<SupportedRole> roles) {
        Operation {
            errorEnvelopes = Collections.unmodifiableMap(new TreeMap<>(errorEnvelopes));
            queryFields = Collections.unmodifiableMap(new TreeMap<>(queryFields));
            roles = Collections.unmodifiableSet(new TreeSet<>(roles));
        }
    }

    CompleteOutputDescriptor {
        catalogAssets = Collections.unmodifiableMap(new TreeMap<>(catalogAssets));
        standardRefs = List.copyOf(standardRefs);
        fields = List.copyOf(fields);
        TreeMap<UUID, List<String>> columns = new TreeMap<>();
        angularTableColumns.forEach((id, values) -> columns.put(id, List.copyOf(values)));
        angularTableColumns = Collections.unmodifiableMap(columns);
        createRules = Collections.unmodifiableMap(new TreeMap<>(createRules));
        createFields = Collections.unmodifiableMap(new TreeMap<>(createFields));
        updateRules = Collections.unmodifiableMap(new TreeMap<>(updateRules));
        editableFields = Collections.unmodifiableMap(new TreeMap<>(editableFields));
        responseFields = Collections.unmodifiableMap(new TreeMap<>(responseFields));
        operations = List.copyOf(operations);
    }
}
