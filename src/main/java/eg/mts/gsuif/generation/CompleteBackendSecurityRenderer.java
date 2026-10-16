package eg.mts.gsuif.generation;

import eg.mts.gsuif.generation.GenerationFilePlan.Owner;
import freemarker.template.Configuration;
import freemarker.template.TemplateExceptionHandler;

import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Map;
import java.util.TreeMap;

/** Emits only the security-mode and account-identity subset; JWT and bootstrap remain deferred. */
final class CompleteBackendSecurityRenderer {
    private static final Map<String, String> TEMPLATES = Map.of(
            "SecurityMode.java", "security-mode.java.ftl",
            "SecurityConfig.java", "security-config.java.ftl",
            "EnforcedMethodSecurityConfig.java", "enforced-method-security-config.java.ftl",
            "AuthenticatedAccount.java", "authenticated-account.java.ftl",
            "AccountIdentityService.java", "account-identity-service.java.ftl",
            "UserAccountRepository.java", "user-account-repository.java.ftl");
    private final Configuration templates = new Configuration(Configuration.VERSION_2_3_34);

    CompleteBackendSecurityRenderer() {
        templates.setClassForTemplateLoading(getClass(), "/");
        templates.setDefaultEncoding(StandardCharsets.UTF_8.name());
        templates.setTemplateExceptionHandler(TemplateExceptionHandler.RETHROW_HANDLER);
        templates.setLogTemplateExceptions(false);
    }

    Map<String, byte[]> render(CompleteOutputDescriptor descriptor, GenerationFilePlan plan) {
        if (descriptor == null || descriptor.mode() == GenerationCapabilityPreflight.OutputMode.FRONTEND)
            throw new IllegalArgumentException("Backend security requires BACKEND or FULL_STACK mode");
        String root = "backend/src/main/java/" + descriptor.basePackage().replace('.', '/') + "/security/";
        Map<String, Owner> owners = new TreeMap<>();
        plan.entries().forEach(entry -> owners.put(entry.path(), entry.owner()));
        Map<String, byte[]> emitted = new TreeMap<>();
        TEMPLATES.forEach((file, template) -> {
            String path = root + file;
            GenerationArtifactStore.safeRelative(path);
            if (owners.get(path) != Owner.DETERMINISTIC)
                throw new IllegalArgumentException("Security path absent or incorrectly owned: " + path);
            emitted.put(path, renderTemplate(template, descriptor.basePackage()));
        });
        if (emitted.size() != TEMPLATES.size())
            throw new IllegalArgumentException("Security subset differs from required definitions");
        Map<String, byte[]> frozen = new TreeMap<>();
        emitted.forEach((path, bytes) -> frozen.put(path, bytes.clone()));
        return Collections.unmodifiableMap(frozen);
    }

    private byte[] renderTemplate(String name, String packageName) {
        try {
            StringWriter writer = new StringWriter();
            templates.getTemplate("templates/complete-backend/" + name)
                    .process(Map.of("packageName", packageName), writer);
            return writer.toString().replace("\r\n", "\n").replace('\r', '\n')
                    .getBytes(StandardCharsets.UTF_8);
        } catch (Exception ex) {
            throw new IllegalArgumentException("Could not render backend security template: " + name, ex);
        }
    }
}
