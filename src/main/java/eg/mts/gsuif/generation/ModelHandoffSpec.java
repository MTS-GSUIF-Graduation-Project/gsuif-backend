package eg.mts.gsuif.generation;

import eg.mts.gsuif.generation.GenerationFilePlan.Owner;
import eg.mts.gsuif.generation.GenerationSpecification.FieldSpec;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Collections;
import java.util.TreeMap;
import java.util.ArrayList;
import java.util.TreeSet;

/** Declares authoritative definitions and writable paths; rendered bytes arrive in a later slice. */
record ModelHandoffSpec(String contractId, String contractVersion, String contractResourceSha256,
        List<Definition> definitions, List<CompleteOutputDescriptor.Operation> operations,
        List<FieldSpec> fields, Map<String, PackagedContractLoader.UpdateField> createRules,
        Map<String, PackagedContractLoader.FieldSchema> createFields,
        Map<String, PackagedContractLoader.UpdateField> updateRules,
        Map<String, PackagedContractLoader.FieldSchema> editableFields,
        Map<String, PackagedContractLoader.FieldSchema> responseFields,
        Set<String> allowedDependencyGroups, List<String> writablePaths) {
    record Definition(String role, String path) { }

    ModelHandoffSpec {
        definitions = List.copyOf(definitions);
        operations = List.copyOf(operations);
        fields = List.copyOf(fields);
        createRules = Collections.unmodifiableMap(new TreeMap<>(createRules));
        createFields = Collections.unmodifiableMap(new TreeMap<>(createFields));
        updateRules = Collections.unmodifiableMap(new TreeMap<>(updateRules));
        editableFields = Collections.unmodifiableMap(new TreeMap<>(editableFields));
        responseFields = Collections.unmodifiableMap(new TreeMap<>(responseFields));
        allowedDependencyGroups = Collections.unmodifiableSet(new TreeSet<>(allowedDependencyGroups));
        writablePaths = List.copyOf(writablePaths);
    }

    static ModelHandoffSpec from(CompleteOutputDescriptor descriptor, GenerationFilePlan plan) {
        String root = "backend/src/main/java/" + descriptor.basePackage().replace('.', '/') + "/";
        String entity = descriptor.entityName();
        List<String> paths = plan.entries().stream().filter(entry -> entry.owner() == Owner.MODEL)
                .map(GenerationFilePlan.Entry::path).toList();
        List<Definition> definitions = new ArrayList<>();
        if (!paths.isEmpty()) {
            definitions.addAll(List.of(
                    new Definition("service interface", root + "service/" + descriptor.contractServiceName() + ".java"),
                    new Definition("repository signatures", root + "repository/" + entity + "Repository.java"),
                    new Definition("entity fields", root + "entity/" + entity + ".java"),
                    new Definition("audit base", root + "entity/AuditableEntity.java"),
                    new Definition("create request", root + "dto/" + descriptor.createRequestName() + ".java"),
                    new Definition("update request", root + "dto/" + descriptor.updateRequestName() + ".java"),
                    new Definition("response DTO", root + "dto/" + descriptor.dtoName() + ".java"),
                    new Definition("page DTO", root + "dto/" + descriptor.pageDtoName() + ".java"),
                    new Definition("response envelope", root + "api/ApiResponse.java")));
            GenerationFilePlan.enumNames(descriptor).stream().sorted().forEach(name ->
                    definitions.add(new Definition("contract enum", root + "dto/" + name + ".java")));
        }
        for (Definition definition : definitions) if (plan.entries().stream().noneMatch(e ->
                e.path().equals(definition.path()) && e.owner() == Owner.DETERMINISTIC))
            throw new IllegalArgumentException("Handoff definition absent from deterministic file plan");
        return new ModelHandoffSpec(descriptor.contractId(), descriptor.contractVersion(),
                descriptor.contractResourceSha256(), definitions, descriptor.operations(), descriptor.fields(),
                descriptor.createRules(), descriptor.createFields(),
                descriptor.updateRules(), descriptor.editableFields(), descriptor.responseFields(),
                Set.of("java", "jakarta.persistence", "org.springframework", "com.fasterxml.jackson"), paths);
    }
}
