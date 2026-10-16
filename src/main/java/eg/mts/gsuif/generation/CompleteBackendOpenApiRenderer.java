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

/** Emits only app-owned runtime documentation support, not the selected contract. */
final class CompleteBackendOpenApiRenderer {
    private final Configuration templates = new Configuration(Configuration.VERSION_2_3_34);

    CompleteBackendOpenApiRenderer() {
        templates.setClassForTemplateLoading(getClass(), "/");
        templates.setDefaultEncoding(StandardCharsets.UTF_8.name());
        templates.setTemplateExceptionHandler(TemplateExceptionHandler.RETHROW_HANDLER);
        templates.setLogTemplateExceptions(false);
    }

    Map<String, byte[]> render(CompleteOutputDescriptor descriptor, GenerationFilePlan plan) {
        if (descriptor == null || descriptor.mode() == GenerationCapabilityPreflight.OutputMode.FRONTEND)
            throw new IllegalArgumentException("Backend OpenAPI requires BACKEND or FULL_STACK mode");
        String root = "backend/src/main/java/" + descriptor.basePackage().replace('.', '/') + "/";
        String config = root + "config/OpenApiConfig.java";
        String error = root + "api/ErrorApiResponse.java";
        Set<String> required = Set.of(config, error);
        Map<String, Owner> owners = new TreeMap<>();
        plan.entries().forEach(entry -> owners.put(entry.path(), entry.owner()));
        for (String path : required) {
            GenerationArtifactStore.safeRelative(path);
            if (owners.get(path) != Owner.DETERMINISTIC)
                throw new IllegalArgumentException("OpenAPI path absent or incorrectly owned: " + path);
        }
        Map<String, Object> model = Map.of("packageName", descriptor.basePackage(),
                "entityName", descriptor.entityName(), "contractId", descriptor.contractId(),
                "contractVersion", descriptor.contractVersion());
        Map<String, byte[]> files = new TreeMap<>();
        files.put(config, template("openapi-config.java.ftl", model));
        files.put(error, template("openapi-error-response.java.ftl", model));
        if (!required.equals(files.keySet())) throw new IllegalArgumentException("OpenAPI output differs from required subset");
        Map<String, byte[]> frozen = new TreeMap<>();
        files.forEach((path, bytes) -> frozen.put(path, bytes.clone()));
        return Collections.unmodifiableMap(frozen);
    }

    private byte[] template(String name, Map<String, Object> model) {
        try {
            StringWriter writer = new StringWriter();
            templates.getTemplate("templates/complete-backend/" + name).process(model, writer);
            return writer.toString().replace("\r\n", "\n").replace('\r', '\n').getBytes(StandardCharsets.UTF_8);
        } catch (Exception ex) {
            throw new IllegalArgumentException("Could not render backend OpenAPI asset: " + name, ex);
        }
    }
}
