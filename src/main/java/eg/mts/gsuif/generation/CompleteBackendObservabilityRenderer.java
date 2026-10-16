package eg.mts.gsuif.generation;

import eg.mts.gsuif.generation.GenerationFilePlan.Owner;
import freemarker.template.Configuration;
import freemarker.template.TemplateExceptionHandler;

import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;

/** Emits only app-owned observability definitions, independent of the selected domain. */
final class CompleteBackendObservabilityRenderer {
    private static final Map<String, String> TEMPLATES = Map.of(
            "aspect/Loggable.java", "observability-loggable.java.ftl",
            "aspect/LoggingAspect.java", "observability-aspect.java.ftl",
            "filter/CorrelationIdFilter.java", "observability-correlation-filter.java.ftl",
            "logging/SafeFailureLogger.java", "observability-safe-failure.java.ftl",
            "logging/SensitiveDataMasker.java", "observability-masker.java.ftl",
            "logging/MaskingPatternLayout.java", "observability-layout.java.ftl");
    private final Configuration templates = new Configuration(Configuration.VERSION_2_3_34);

    CompleteBackendObservabilityRenderer() {
        templates.setClassForTemplateLoading(getClass(), "/");
        templates.setDefaultEncoding(StandardCharsets.UTF_8.name());
        templates.setTemplateExceptionHandler(TemplateExceptionHandler.RETHROW_HANDLER);
        templates.setLogTemplateExceptions(false);
    }

    Map<String, byte[]> render(CompleteOutputDescriptor descriptor, GenerationFilePlan plan) {
        if (descriptor == null || descriptor.mode() == GenerationCapabilityPreflight.OutputMode.FRONTEND)
            throw new IllegalArgumentException("Backend observability requires BACKEND or FULL_STACK mode");
        String root = "backend/src/main/java/" + descriptor.basePackage().replace('.', '/') + "/";
        Map<String, String> required = new LinkedHashMap<>();
        TEMPLATES.forEach((relative, template) -> required.put(root + relative, template));
        required.put("backend/src/main/resources/logback-spring.xml", "observability-logback.xml.ftl");
        Map<String, Owner> owners = new TreeMap<>();
        plan.entries().forEach(entry -> owners.put(entry.path(), entry.owner()));
        Map<String, byte[]> emitted = new TreeMap<>();
        required.forEach((path, template) -> {
            GenerationArtifactStore.safeRelative(path);
            if (owners.get(path) != Owner.DETERMINISTIC)
                throw new IllegalArgumentException("Observability path absent or incorrectly owned: " + path);
            emitted.put(path, template(template, descriptor.basePackage()));
        });
        if (!emitted.keySet().equals(required.keySet()))
            throw new IllegalArgumentException("Observability output differs from required subset");
        Map<String, byte[]> frozen = new TreeMap<>();
        emitted.forEach((path, bytes) -> frozen.put(path, bytes.clone()));
        return Collections.unmodifiableMap(frozen);
    }

    private byte[] template(String name, String packageName) {
        try {
            StringWriter writer = new StringWriter();
            templates.getTemplate("templates/complete-backend/" + name)
                    .process(Map.of("packageName", packageName), writer);
            return writer.toString().replace("\r\n", "\n").replace('\r', '\n')
                    .getBytes(StandardCharsets.UTF_8);
        } catch (Exception ex) {
            throw new IllegalArgumentException("Could not render observability template: " + name, ex);
        }
    }
}
