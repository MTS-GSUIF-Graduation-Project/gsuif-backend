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
import java.util.TreeSet;

/** Emits only the deterministic backend error-contract subset. */
final class CompleteBackendErrorRenderer {
    private final Configuration templates = new Configuration(Configuration.VERSION_2_3_34);

    CompleteBackendErrorRenderer() {
        templates.setClassForTemplateLoading(getClass(), "/");
        templates.setDefaultEncoding(StandardCharsets.UTF_8.name());
        templates.setTemplateExceptionHandler(TemplateExceptionHandler.RETHROW_HANDLER);
        templates.setLogTemplateExceptions(false);
    }

    Map<String, byte[]> render(CompleteOutputDescriptor descriptor, GenerationFilePlan plan) {
        if (descriptor == null || descriptor.mode() == GenerationCapabilityPreflight.OutputMode.FRONTEND)
            throw new IllegalArgumentException("Backend error handler requires BACKEND or FULL_STACK mode");
        String root = "backend/src/main/java/" + descriptor.basePackage().replace('.', '/') + "/exception/";
        String handler = root + "GlobalExceptionHandler.java";
        String missing = ModelHandoffSpec.requestedRecordNotFoundPath(descriptor);
        Set<String> required = Set.of(handler, missing);
        Map<String, Owner> owners = new TreeMap<>();
        plan.entries().forEach(entry -> owners.put(entry.path(), entry.owner()));
        for (String path : required) {
            GenerationArtifactStore.safeRelative(path);
            if (owners.get(path) != Owner.DETERMINISTIC)
                throw new IllegalArgumentException("Error-contract path absent or incorrectly owned: " + path);
        }
        Set<String> keys = new TreeSet<>(descriptor.createFields().keySet());
        keys.addAll(descriptor.editableFields().keySet());
        descriptor.operations().forEach(operation -> keys.addAll(operation.queryFields().keySet()));
        Map<String, byte[]> emitted = new TreeMap<>();
        emitted.put(handler, template("global-exception-handler.java.ftl",
                Map.of("packageName", descriptor.basePackage(), "fieldKeys", keys)));
        emitted.put(missing, requestedRecordNotFound(descriptor, plan));
        if (!required.equals(emitted.keySet()))
            throw new IllegalArgumentException("Error-contract output differs from required subset");
        Map<String, byte[]> frozen = new TreeMap<>();
        emitted.forEach((path, bytes) -> frozen.put(path, bytes.clone()));
        return Collections.unmodifiableMap(frozen);
    }

    byte[] requestedRecordNotFound(CompleteOutputDescriptor descriptor, GenerationFilePlan plan) {
        if (descriptor == null || descriptor.mode() == GenerationCapabilityPreflight.OutputMode.FRONTEND)
            throw new IllegalArgumentException("Backend exception requires BACKEND or FULL_STACK mode");
        String path = ModelHandoffSpec.requestedRecordNotFoundPath(descriptor);
        GenerationArtifactStore.safeRelative(path);
        if (plan.entries().stream().noneMatch(entry -> entry.path().equals(path) && entry.owner() == Owner.DETERMINISTIC))
            throw new IllegalArgumentException("Exception definition absent or incorrectly owned: " + path);
        return template("requested-record-not-found.java.ftl", Map.of("packageName", descriptor.basePackage()));
    }

    private byte[] template(String name, Map<String, Object> model) {
        try {
            StringWriter writer = new StringWriter();
            templates.getTemplate("templates/complete-backend/" + name).process(model, writer);
            return writer.toString().replace("\r\n", "\n").replace('\r', '\n').getBytes(StandardCharsets.UTF_8);
        } catch (Exception ex) {
            throw new IllegalArgumentException("Could not render backend error template: " + name, ex);
        }
    }
}
