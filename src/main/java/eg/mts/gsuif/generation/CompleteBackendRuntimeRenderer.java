package eg.mts.gsuif.generation;

import eg.mts.gsuif.generation.GenerationCapabilityPreflight.OutputMode;
import eg.mts.gsuif.generation.GenerationFilePlan.Owner;
import freemarker.template.Configuration;
import freemarker.template.TemplateExceptionHandler;

import java.io.InputStream;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Collections;
import java.util.HexFormat;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

/** Emits only the runtime scaffold subset; it never assembles or validates a complete app. */
final class CompleteBackendRuntimeRenderer {
    @FunctionalInterface interface ResourceBytes { byte[] read(String path); }

    private final PackagedContractRegistry registry;
    private final ResourceBytes resources;
    private final Configuration templates = new Configuration(Configuration.VERSION_2_3_34);

    CompleteBackendRuntimeRenderer(PackagedContractRegistry registry) {
        this(registry, CompleteBackendRuntimeRenderer::classpathBytes);
    }

    CompleteBackendRuntimeRenderer(PackagedContractRegistry registry, ResourceBytes resources) {
        this.registry = Objects.requireNonNull(registry);
        this.resources = Objects.requireNonNull(resources);
        templates.setClassForTemplateLoading(getClass(), "/");
        templates.setDefaultEncoding(StandardCharsets.UTF_8.name());
        templates.setTemplateExceptionHandler(TemplateExceptionHandler.RETHROW_HANDLER);
        templates.setLogTemplateExceptions(false);
    }

    Map<String, byte[]> render(CompleteOutputDescriptor descriptor, GenerationFilePlan plan) {
        if (descriptor == null || descriptor.mode() == OutputMode.FRONTEND)
            throw new IllegalArgumentException("Backend runtime scaffold requires BACKEND or FULL_STACK mode");
        var selected = registry.find(descriptor.contractId(), descriptor.contractVersion());
        if (selected == null || !selected.fullUpdate())
            throw new IllegalArgumentException("Exact full-update contract version is not registered");
        byte[] loaded = resources.read(selected.resource());
        if (loaded == null) throw new IllegalArgumentException("Selected contract resource is missing");
        byte[] contract = loaded.clone();
        if (!sha256(contract).equals(descriptor.contractResourceSha256()))
            throw new IllegalArgumentException("Selected contract resource differs from frozen descriptor hash");
        String contractPath = "contracts/" + descriptor.contractId() + "-" + descriptor.contractVersion() + ".yaml";
        String applicationPath = "backend/src/main/java/" + descriptor.basePackage().replace('.', '/') + "/Application.java";
        Set<String> required = Set.of("README.md", contractPath, "backend/pom.xml",
                "backend/src/main/resources/application.yml", applicationPath);
        String artifactId = descriptor.entityName().replaceAll("([a-z0-9])([A-Z])", "$1-$2")
                .toLowerCase(java.util.Locale.ROOT) + "-generated-backend";
        Map<String, Object> model = Map.of("basePackage", descriptor.basePackage(),
                "artifactId", artifactId, "contractId", descriptor.contractId(),
                "contractVersion", descriptor.contractVersion(), "contractHash", descriptor.contractResourceSha256(),
                "enversAudited", descriptor.enversAudited());
        Map<String, byte[]> emitted = new TreeMap<>();
        emitted.put("backend/pom.xml", template("runtime-pom.xml.ftl", model));
        emitted.put("backend/src/main/resources/application.yml", template("runtime-application.yml.ftl", model));
        emitted.put(applicationPath, template("runtime-application.java.ftl", model));
        emitted.put("README.md", template("runtime-readme.md.ftl", model));
        emitted.put(contractPath, contract.clone());
        return validate(plan, required, emitted);
    }

    static Map<String, byte[]> validate(GenerationFilePlan plan, Set<String> required, Map<String, byte[]> emitted) {
        if (plan == null || required == null || emitted == null || !required.equals(emitted.keySet()))
            throw new IllegalArgumentException("Runtime scaffold differs from required subset");
        Map<String, Owner> owners = new TreeMap<>();
        plan.entries().forEach(entry -> owners.put(entry.path(), entry.owner()));
        Map<String, byte[]> frozen = new TreeMap<>();
        emitted.forEach((path, bytes) -> {
            GenerationArtifactStore.safeRelative(path);
            if (owners.get(path) != Owner.DETERMINISTIC || bytes == null)
                throw new IllegalArgumentException("Unplanned or incorrectly owned runtime scaffold path");
            frozen.put(path, bytes.clone());
        });
        return Collections.unmodifiableMap(frozen);
    }

    private byte[] template(String name, Map<String, Object> model) {
        try {
            StringWriter writer = new StringWriter();
            templates.getTemplate("templates/complete-backend/" + name).process(model, writer);
            return writer.toString().replace("\r\n", "\n").replace('\r', '\n').getBytes(StandardCharsets.UTF_8);
        } catch (Exception ex) {
            throw new IllegalArgumentException("Could not render backend runtime asset: " + name, ex);
        }
    }

    private static byte[] classpathBytes(String path) {
        try (InputStream input = CompleteBackendRuntimeRenderer.class.getResourceAsStream(path)) {
            if (input == null) throw new IllegalArgumentException("Selected contract resource is missing");
            return input.readAllBytes();
        } catch (java.io.IOException ex) {
            throw new IllegalArgumentException("Could not read selected contract resource", ex);
        }
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (java.security.NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
    }
}
