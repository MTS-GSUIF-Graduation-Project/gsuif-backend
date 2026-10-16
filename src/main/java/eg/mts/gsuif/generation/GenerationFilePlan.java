package eg.mts.gsuif.generation;

import eg.mts.gsuif.generation.GenerationCapabilityPreflight.OutputMode;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;

/** Required output paths and exclusive owners; this plan does not create files. */
record GenerationFilePlan(List<Entry> entries) {
    enum Owner { DETERMINISTIC, MODEL, ASSEMBLER }
    record Entry(String path, Owner owner, String purpose) { }

    GenerationFilePlan {
        List<Entry> ordered = new ArrayList<>(entries);
        ordered.sort(Comparator.comparing(Entry::path));
        Set<String> aliases = new HashSet<>();
        for (Entry entry : ordered) {
            if (entry == null || entry.owner() == null || entry.purpose() == null || entry.purpose().isBlank())
                throw new IllegalArgumentException("Invalid file-plan entry");
            GenerationArtifactStore.safeRelative(entry.path());
            for (String part : entry.path().split("/")) {
                String stem = part.split("\\.", 2)[0].toUpperCase(Locale.ROOT);
                if (part.endsWith(".") || part.endsWith(" ") || Set.of("CON", "PRN", "AUX", "NUL",
                        "COM1", "COM2", "COM3", "COM4", "COM5", "COM6", "COM7", "COM8", "COM9",
                        "LPT1", "LPT2", "LPT3", "LPT4", "LPT5", "LPT6", "LPT7", "LPT8", "LPT9").contains(stem))
                    throw new IllegalArgumentException("Unsafe Windows file-plan path");
            }
            String alias = entry.path().toLowerCase(Locale.ROOT);
            if (!aliases.add(alias)) throw new IllegalArgumentException("File-plan path collision: " + entry.path());
        }
        for (String alias : aliases) for (String other : aliases)
            if (!alias.equals(other) && other.startsWith(alias + "/"))
                throw new IllegalArgumentException("File-plan file/directory collision: " + alias);
        entries = List.copyOf(ordered);
    }

    static GenerationFilePlan forDescriptor(CompleteOutputDescriptor descriptor) {
        List<Entry> entries = new ArrayList<>();
        add(entries, "README.md", Owner.DETERMINISTIC, "runtime prerequisites");
        add(entries, "contracts/" + descriptor.contractId() + "-" + descriptor.contractVersion() + ".yaml",
                Owner.DETERMINISTIC, "selected OpenAPI contract");
        add(entries, "generation-manifest.json", Owner.ASSEMBLER, "final file hashes and provenance");
        String root = "backend/src/main/java/" + descriptor.basePackage().replace('.', '/') + "/";
        String entity = descriptor.entityName();
        if (descriptor.mode() != OutputMode.FRONTEND) {
            for (String path : List.of("backend/pom.xml", "backend/src/main/resources/application.yml",
                    "backend/src/main/resources/db/migration/V1__initial_schema.sql"))
                add(entries, path, Owner.DETERMINISTIC, "backend runtime");
            for (String relative : List.of("Application.java", "api/ApiResponse.java",
                    "exception/GlobalExceptionHandler.java", "exception/RequestedRecordNotFoundException.java",
                    "entity/AuditableEntity.java", "entity/" + entity + ".java", "repository/" + entity + "Repository.java",
                    "dto/" + descriptor.dtoName() + ".java", "dto/" + descriptor.pageDtoName() + ".java",
                    "dto/" + descriptor.createRequestName() + ".java", "dto/" + descriptor.updateRequestName() + ".java",
                    "api/" + entity + "Api.java", "controller/" + entity + "Controller.java",
                    "service/" + entity + "Service.java", "security/SecurityConfig.java",
                    "security/SecurityMode.java", "security/EnforcedMethodSecurityConfig.java",
                    "security/AuthenticatedAccount.java", "security/AccountIdentityService.java",
                    "security/FirstAdminBootstrap.java", "security/UserAccount.java", "security/UserAccountRepository.java",
                    "security/AuthController.java", "security/LoginRequest.java",
                    "security/LoginResponse.java", "security/JwtAuthenticationFilter.java",
                    "security/JwtService.java"))
                add(entries, root + relative, Owner.DETERMINISTIC, "backend contract or framework");
            if (descriptor.enversAudited())
                add(entries, root + "audit/AppRevision.java", Owner.DETERMINISTIC, "app-owned Envers revision");
            enumNames(descriptor).forEach(enumName ->
                            add(entries, root + "dto/" + enumName + ".java", Owner.DETERMINISTIC, "contract enum"));
            add(entries, root + "service/" + entity + "ServiceImpl.java", Owner.MODEL, "CRUD service implementation");
        }
        if (descriptor.mode() != OutputMode.BACKEND) {
            String slug = entity.replaceAll("([a-z0-9])([A-Z])", "$1-$2").toLowerCase(Locale.ROOT);
            for (String path : List.of("frontend/package.json", "frontend/package-lock.json", "frontend/angular.json",
                    "frontend/tsconfig.json", "frontend/tsconfig.app.json", "frontend/src/index.html",
                    "frontend/src/main.ts", "frontend/src/styles.css", "frontend/src/app/app.routes.ts",
                    "frontend/src/app/app.config.ts", "frontend/src/app/app.component.ts",
                    "frontend/src/app/shared/api-client.ts", "frontend/src/app/shared/auth.service.ts",
                    "frontend/src/app/shared/auth.guard.ts", "frontend/src/environments/environment.ts",
                    "frontend/src/app/generated/" + slug + "/" + slug + ".component.ts",
                    "frontend/src/app/generated/" + slug + "/" + slug + ".component.html",
                    "frontend/src/app/generated/" + slug + "/" + slug + "-api.service.ts",
                    "frontend/src/app/generated/" + slug + "/" + slug + ".behavior.ts"))
                add(entries, path, Owner.DETERMINISTIC, "frontend framework or metadata rendering");
        }
        return new GenerationFilePlan(entries);
    }

    private static void add(List<Entry> entries, String path, Owner owner, String purpose) {
        entries.add(new Entry(path, owner, purpose));
    }

    static Set<String> enumNames(CompleteOutputDescriptor descriptor) {
        Set<String> names = new TreeSet<>();
        descriptor.fields().forEach(field -> { if (field.enumType() != null) names.add(field.enumType()); });
        descriptor.createFields().values().forEach(schema -> { if (!schema.enumName().isEmpty()) names.add(schema.enumName()); });
        descriptor.editableFields().values().forEach(schema -> { if (!schema.enumName().isEmpty()) names.add(schema.enumName()); });
        descriptor.responseFields().values().forEach(schema -> { if (!schema.enumName().isEmpty()) names.add(schema.enumName()); });
        descriptor.operations().forEach(operation -> operation.queryFields().values().forEach(parameter -> {
            if (!parameter.schema().enumName().isEmpty()) names.add(parameter.schema().enumName());
        }));
        return Set.copyOf(names);
    }
}
