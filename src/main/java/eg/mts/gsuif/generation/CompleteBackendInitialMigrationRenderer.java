package eg.mts.gsuif.generation;

import eg.mts.gsuif.generation.GenerationCapabilityPreflight.OutputMode;
import eg.mts.gsuif.generation.GenerationFilePlan.Owner;
import freemarker.template.Configuration;
import freemarker.template.TemplateExceptionHandler;

import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/** Emits the V1 subset from the single frozen entity-schema projection. */
final class CompleteBackendInitialMigrationRenderer {
    private static final String MIGRATION = "backend/src/main/resources/db/migration/V1__initial_schema.sql";
    private final Configuration templates = new Configuration(Configuration.VERSION_2_3_34);

    CompleteBackendInitialMigrationRenderer() {
        templates.setClassForTemplateLoading(getClass(), "/");
        templates.setDefaultEncoding(StandardCharsets.UTF_8.name());
        templates.setTemplateExceptionHandler(TemplateExceptionHandler.RETHROW_HANDLER);
        templates.setLogTemplateExceptions(false);
    }

    Map<String, byte[]> render(CompleteOutputDescriptor descriptor, GenerationFilePlan plan) {
        if (descriptor == null || descriptor.mode() == OutputMode.FRONTEND)
            throw new IllegalArgumentException("Initial migration requires BACKEND or FULL_STACK mode");
        CompleteBackendEntitySchema entity = CompleteBackendEntitySchema.from(descriptor);
        String root = "backend/src/main/java/" + descriptor.basePackage().replace('.', '/') + "/";
        String userPath = root + "security/UserAccount.java";
        String revisionPath = root + "audit/AppRevision.java";
        Set<String> required = descriptor.enversAudited()
                ? Set.of(MIGRATION, userPath, revisionPath) : Set.of(MIGRATION, userPath);
        Map<String, Owner> planned = new TreeMap<>();
        plan.entries().forEach(entry -> planned.put(entry.path(), entry.owner()));
        for (String path : required) {
            GenerationArtifactStore.safeRelative(path);
            if (planned.get(path) != Owner.DETERMINISTIC)
                throw new IllegalArgumentException("Unplanned or incorrectly owned V1 support path");
        }
        List<Map<String, Object>> auditColumns = List.of();
        List<Map<String, Object>> auditChecks = List.of();
        if (descriptor.enversAudited()) {
            auditColumns = entity.columns().stream()
                    .map(column -> Map.<String, Object>of("name", column.name(), "sqlType", column.sqlType())).toList();
            auditChecks = entity.constraints().stream().map(check -> {
                String name = check.name() + CompleteBackendPersistenceNames.AUDIT_SUFFIX;
                CompleteBackendEntitySchema.identifier(name, "audit CHECK constraint");
                return Map.<String, Object>of("name", name, "column", check.column(),
                        "enumValues", check.enumValues());
            }).toList();
        }
        if (descriptor.enversAudited()) for (String name : List.of("pk_" + entity.auditTableName(),
                "fk_" + entity.auditTableName() + "_rev"))
            CompleteBackendEntitySchema.identifier(name, "audit table constraint");
        Map<String, Object> sqlModel = Map.of("entitySql", entity.sqlFragment(),
                "audited", descriptor.enversAudited(), "auditTable",
                entity.auditTableName() == null ? "" : entity.auditTableName(),
                "auditColumns", auditColumns, "auditChecks", auditChecks);
        Map<String, byte[]> emitted = new TreeMap<>();
        emitted.put(MIGRATION, template("initial-schema.sql.ftl", sqlModel));
        emitted.put(userPath, template("user-account.java.ftl", Map.of("packageName", descriptor.basePackage())));
        if (descriptor.enversAudited()) emitted.put(revisionPath,
                template("app-revision.java.ftl", Map.of("packageName", descriptor.basePackage())));
        if (!required.equals(emitted.keySet()))
            throw new IllegalArgumentException("V1 output differs from required subset");
        Map<String, byte[]> frozen = new TreeMap<>();
        emitted.forEach((path, bytes) -> frozen.put(path, bytes.clone()));
        return Collections.unmodifiableMap(frozen);
    }

    private byte[] template(String name, Map<String, Object> model) {
        try {
            StringWriter writer = new StringWriter();
            templates.getTemplate("templates/complete-backend/" + name).process(model, writer);
            return writer.toString().replace("\r\n", "\n").replace('\r', '\n')
                    .getBytes(StandardCharsets.UTF_8);
        } catch (Exception ex) {
            throw new IllegalArgumentException("Could not render V1 support template: " + name, ex);
        }
    }
}
