package eg.mts.gsuif.generation;

import eg.mts.gsuif.generation.GenerationContext.Target;
import eg.mts.gsuif.generation.GenerationSpecification.SupportedRole;
import freemarker.template.Configuration;
import freemarker.template.TemplateExceptionHandler;

import java.io.InputStream;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Comparator;

/** Phase 1 provider. Rendering is fully buffered; callers own publication and run persistence. */
public final class TemplateOnlyProvider implements AICodeGenerationProvider {
    private static final List<String> SUPPORT = List.of(
            "eg/mts/gsuif/dto/ApiResponse.java", "eg/mts/gsuif/dto/PagedBody.java",
            "eg/mts/gsuif/entity/AuditableEntity.java", "eg/mts/gsuif/audit/AuditorAwareImpl.java",
            "eg/mts/gsuif/config/JpaAuditingConfig.java");
    private final GenerationContextBuilder builder;
    private final Configuration templates = new Configuration(Configuration.VERSION_2_3_34);

    public TemplateOnlyProvider(GenerationContextBuilder builder) {
        this.builder = builder;
        templates.setClassForTemplateLoading(getClass(), "/");
        templates.setDefaultEncoding("UTF-8");
        templates.setTemplateExceptionHandler(TemplateExceptionHandler.RETHROW_HANDLER);
        templates.setLogTemplateExceptions(false);
    }

    @Override public String getProviderName() { return "TemplateOnlyProvider"; }

    @Override public boolean isAvailable() { return true; }

    @Override public GenerationResult generate(GenerationContext supplied) {
        if (supplied == null) throw new GenerationValidationException(List.of("context: required"));
        GenerationContext context = builder.build(supplied.metadataVersion(), supplied.specification(), supplied.targets(), supplied.framework());
        return generateValidated(context, supplied.catalog());
    }

    GenerationResult generateValidated(GenerationContext context) {
        return generateValidated(context, context.catalog());
    }

    private GenerationResult generateValidated(GenerationContext context, GenerationCatalog suppliedCatalog) {
        GenerationCatalog catalog = context.catalog();
        if (suppliedCatalog == null || !suppliedCatalog.version().equals(catalog.version())
                || !suppliedCatalog.entity().equals(catalog.entity())
                || !suppliedCatalog.controller().equals(catalog.controller())
                || !suppliedCatalog.angularTypescript().equals(catalog.angularTypescript())
                || !suppliedCatalog.angularHtml().equals(catalog.angularHtml())
                || !suppliedCatalog.providerVersion().equals(catalog.providerVersion())
                || !suppliedCatalog.standardRefs().equals(catalog.standardRefs()))
            throw new GenerationValidationException(List.of("context.catalog: selected catalog differs from packaged standards"));
        GenerationSpecification spec = context.specification();
        Map<String, Object> model = new LinkedHashMap<>(context.templateModel());
        Map<String, Object> api = new LinkedHashMap<>((Map<String, Object>) context.templateModel().get("api"));
        Map<String, String> expressions = new HashMap<>();
        for (String key : context.targets().contains(Target.CONTROLLER)
                ? List.of("createMethod", "getMethod", "listMethod", "updateMethod", "deleteMethod") : List.<String>of()) {
            String operation = (String) api.get(key);
            List<SupportedRole> roles = spec.operationRoles().get(operation).stream().sorted(Comparator.comparing(Enum::name)).toList();
            if (roles == null || roles.isEmpty()) throw new GenerationValidationException(List.of("specification.operationRoles." + operation + ": required"));
            expressions.put(key, roles.size() == 1 ? "hasAuthority('" + roles.get(0) + "')" :
                    "hasAnyAuthority('" + roles.get(0) + "','" + roles.get(1) + "')");
        }
        api.put("operationRoles", expressions);
        model.put("api", api);
        String root = "src/main/java/" + spec.basePackage().replace('.', '/') + "/";
        List<GenerationResult.Artifact> pending = new ArrayList<>();
        try {
            if (context.targets().contains(Target.ENTITY))
                pending.add(artifact(root + "entity/" + spec.entity().className() + ".java", render(catalog.entity().path(), model), catalog.entity().version(), catalog.version()));
            if (context.targets().contains(Target.CONTROLLER)) {
                pending.add(artifact(root + "controller/" + spec.entity().className() + "Controller.java", render(catalog.controller().path(), model), catalog.controller().version(), catalog.version()));
            }
            if (context.targets().contains(Target.ENTITY) || context.targets().contains(Target.CONTROLLER))
                for (String source : SUPPORT) pending.add(artifact("src/main/java/" + source, resource("/consumer-support/" + source), catalog.providerVersion(), catalog.version()));
            if (context.targets().contains(Target.ANGULAR)) renderAngular(context, pending, catalog);
        } catch (GenerationValidationException ex) { throw ex; }
        catch (Exception ex) { throw new GenerationValidationException(List.of("render: " + ex.getMessage())); }
        return new GenerationResult(pending, List.of(),
                context.targets().contains(Target.ENTITY) || context.targets().contains(Target.CONTROLLER)
                        ? ConsumerBuildContract.phaseOne() : null);
    }

    private void renderAngular(GenerationContext context, List<GenerationResult.Artifact> output, GenerationCatalog catalog) throws Exception {
        Map<String, Object> model = new AngularModelPreparer().prepare(context);
        String kebab = (String) model.get("kebab");
        String base = "src/app/generated/" + kebab;
        output.add(artifact(base + ".component.ts", render(catalog.angularTypescript().path(), model), catalog.angularTypescript().version(), catalog.version()));
        output.add(artifact(base + ".component.html", render(catalog.angularHtml().path(), model), catalog.angularHtml().version(), catalog.version()));
        output.add(artifact("src/app/generated/angular-visibility.ts", render("templates/freemarker/angular-visibility.ts.ftl", model), catalog.angularTypescript().version(), catalog.version()));
    }

    private String render(String path, Map<String, Object> model) throws Exception {
        StringWriter writer = new StringWriter();
        templates.getTemplate(path).process(model, writer);
        return writer.toString();
    }

    private static String resource(String path) throws Exception {
        try (InputStream in = TemplateOnlyProvider.class.getResourceAsStream(path)) {
            if (in == null) throw new IllegalStateException("missing support source: " + path);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static GenerationResult.Artifact artifact(String path, String content, String templateVersion, String catalogVersion) throws Exception {
        if (path.startsWith("/") || path.contains("..") || path.contains("\\") || path.contains(":"))
            throw new GenerationValidationException(List.of("artifact.relativePath: unsafe path"));
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
        return new GenerationResult.Artifact(path, bytes, java.util.HexFormat.of().formatHex(digest), templateVersion, catalogVersion);
    }
}
