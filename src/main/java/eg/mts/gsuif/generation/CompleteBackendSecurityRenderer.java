package eg.mts.gsuif.generation;

import eg.mts.gsuif.generation.GenerationFilePlan.Owner;
import freemarker.template.Configuration;
import freemarker.template.TemplateExceptionHandler;

import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Map;
import java.util.TreeMap;

/** Emits the security-mode, account-identity, and token-service subset. */
final class CompleteBackendSecurityRenderer {
    private static final Map<String, String> TEMPLATES = Map.ofEntries(
            Map.entry("SecurityMode.java", "security-mode.java.ftl"),
            Map.entry("SecurityConfig.java", "security-config.java.ftl"),
            Map.entry("EnforcedMethodSecurityConfig.java", "enforced-method-security-config.java.ftl"),
            Map.entry("AuthenticatedAccount.java", "authenticated-account.java.ftl"),
            Map.entry("AccountIdentityService.java", "account-identity-service.java.ftl"),
            Map.entry("UserAccountRepository.java", "user-account-repository.java.ftl"),
            Map.entry("JwtService.java", "jwt-service.java.ftl"),
            Map.entry("JwtAuthenticationFilter.java", "jwt-authentication-filter.java.ftl"),
            Map.entry("AuthController.java", "auth-controller.java.ftl"),
            Map.entry("LoginRequest.java", "login-request.java.ftl"),
            Map.entry("LoginResponse.java", "login-response.java.ftl"));
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
            emitted.put(path, renderTemplate(template, descriptor));
        });
        if (emitted.size() != TEMPLATES.size())
            throw new IllegalArgumentException("Security subset differs from required definitions");
        Map<String, byte[]> frozen = new TreeMap<>();
        emitted.forEach((path, bytes) -> frozen.put(path, bytes.clone()));
        return Collections.unmodifiableMap(frozen);
    }

    private byte[] renderTemplate(String name, CompleteOutputDescriptor descriptor) {
        try {
            StringWriter writer = new StringWriter();
            templates.getTemplate("templates/complete-backend/" + name)
                    .process(Map.of("packageName", descriptor.basePackage(),
                            "contractId", descriptor.contractId(),
                            "contractVersion", descriptor.contractVersion()), writer);
            return writer.toString().replace("\r\n", "\n").replace('\r', '\n')
                    .getBytes(StandardCharsets.UTF_8);
        } catch (Exception ex) {
            throw new IllegalArgumentException("Could not render backend security template: " + name, ex);
        }
    }
}
