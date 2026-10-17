package eg.mts.gsuif.generation;

import eg.mts.gsuif.generation.GenerationFilePlan.Owner;
import freemarker.template.Configuration;
import freemarker.template.TemplateExceptionHandler;

import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/** Emits only app-owned audit actor definitions; it does not modify V1 or Envers mappings. */
final class CompleteBackendAuditRenderer {
    private static final Map<String, String> TEMPLATES = Map.of(
            "AuditActorConfig.java", "audit-actor-config.java.ftl",
            "GeneratedAppAuditorAware.java", "generated-app-auditor-aware.java.ftl");
    private final Configuration templates = new Configuration(Configuration.VERSION_2_3_34);

    CompleteBackendAuditRenderer() {
        templates.setClassForTemplateLoading(getClass(), "/");
        templates.setDefaultEncoding(StandardCharsets.UTF_8.name());
        templates.setTemplateExceptionHandler(TemplateExceptionHandler.RETHROW_HANDLER);
        templates.setLogTemplateExceptions(false);
    }

    Map<String, byte[]> render(CompleteOutputDescriptor descriptor, GenerationFilePlan plan) {
        if (descriptor == null || descriptor.mode() == GenerationCapabilityPreflight.OutputMode.FRONTEND)
            throw new IllegalArgumentException("Backend audit requires BACKEND or FULL_STACK mode");
        String root = "backend/src/main/java/" + descriptor.basePackage().replace('.', '/') + "/audit/";
        Map<String, Owner> owners = new TreeMap<>();
        plan.entries().forEach(entry -> owners.put(entry.path(), entry.owner()));
        Set<String> required = TEMPLATES.keySet().stream().map(root::concat)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        for (String path : required) {
            GenerationArtifactStore.safeRelative(path);
            if (owners.get(path) != Owner.DETERMINISTIC)
                throw new IllegalArgumentException("Audit path absent or incorrectly owned: " + path);
        }
        Map<String, byte[]> files = new TreeMap<>();
        TEMPLATES.forEach((name, template) -> files.put(root + name, template(template, descriptor)));
        if (!files.keySet().equals(required))
            throw new IllegalArgumentException("Audit subset differs from required definitions");
        Map<String, byte[]> frozen = new TreeMap<>();
        files.forEach((path, bytes) -> frozen.put(path, bytes.clone()));
        return Collections.unmodifiableMap(frozen);
    }

    private byte[] template(String name, CompleteOutputDescriptor descriptor) {
        try {
            StringWriter writer = new StringWriter();
            templates.getTemplate("templates/complete-backend/" + name)
                    .process(Map.of("packageName", descriptor.basePackage()), writer);
            return writer.toString().replace("\r\n", "\n").replace('\r', '\n')
                    .getBytes(StandardCharsets.UTF_8);
        } catch (Exception exception) {
            throw new IllegalArgumentException("Could not render backend audit template: " + name, exception);
        }
    }
}
