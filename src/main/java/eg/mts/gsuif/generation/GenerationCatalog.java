package eg.mts.gsuif.generation;

import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** The packaged component catalog is the authority for Phase 1 tool and template choices. */
public final class GenerationCatalog {
    public record Choice(String path, String version) { }

    private final String version;
    private final Choice entity;
    private final Choice controller;
    private final Choice angularTypescript;
    private final Choice angularHtml;
    private final String providerVersion;
    private final List<String> standardRefs;

    private GenerationCatalog(String version, Choice entity, Choice controller, Choice angularTypescript,
                              Choice angularHtml, String providerVersion, List<String> standardRefs) {
        this.version = version;
        this.entity = entity;
        this.controller = controller;
        this.angularTypescript = angularTypescript;
        this.angularHtml = angularHtml;
        this.providerVersion = providerVersion;
        this.standardRefs = List.copyOf(standardRefs);
    }

    public String version() { return version; }
    public Choice entity() { return entity; }
    public Choice controller() { return controller; }
    public Choice angularTypescript() { return angularTypescript; }
    public Choice angularHtml() { return angularHtml; }
    public String providerVersion() { return providerVersion; }
    public List<String> standardRefs() { return standardRefs; }

    public static GenerationCatalog load() {
        try (InputStream input = GenerationCatalog.class.getResourceAsStream("/components.yaml")) {
            if (input == null) throw invalid("packaged components.yaml is missing");
            return parse(new Yaml().load(input));
        } catch (GenerationValidationException ex) { throw ex; }
        catch (Exception ex) { throw invalid("cannot read packaged components.yaml: " + ex.getMessage()); }
    }

    static GenerationCatalog parse(Object source) {
        try {
            Map<?, ?> catalog = map(source, "catalog");
            String version = required(catalog.get("catalog_version"), "catalog_version");
            Object entries = catalog.get("components");
            if (!(entries instanceof List<?> components)) throw invalid("components must be a list");
            Map<?, ?> entity = component(components, "BE-02");
            Map<?, ?> crud = component(components, "BE-05");
            Map<?, ?> engine = component(components, "BE-13");
            Map<?, ?> entityGeneration = generation(entity, "BE-02", "FREEMARKER");
            Map<?, ?> crudGeneration = generation(crud, "BE-05", "OPENAPI_PLUS_FREEMARKER");
            Map<?, ?> engineGeneration = generation(engine, "BE-13", "HAND_WRITTEN");
            tool(entityGeneration, "BE-02", "Apache FreeMarker", "2.3.34");
            tool(crudGeneration, "BE-05", "openapi-generator-maven-plugin", "7.16.0");
            tool(engineGeneration, "BE-13", "TemplateOnlyProvider", null);
            supplement(crudGeneration, "BE-05");
            supplement(engineGeneration, "BE-13");
            Map<?, ?> entityTemplate = map(entityGeneration.get("template"), "BE-02.template");
            Map<?, ?> crudTemplate = map(crudGeneration.get("template"), "BE-05.template");
            String entityPath = checkedPath(entityTemplate.get("path"), "BE-02.template.path");
            Object artifacts = crudTemplate.get("artifacts");
            if (!(artifacts instanceof List<?> list)) throw invalid("BE-05.template.artifacts must be a list");
            String controllerPath = null;
            boolean override = false;
            for (Object entry : list) {
                Map<?, ?> artifact = map(entry, "BE-05.template.artifacts");
                String role = required(artifact.get("role"), "BE-05.template.artifacts.role");
                if (!Objects.equals(artifact.get("location_type"), "CLASSPATH")) throw invalid("BE-05 template must use CLASSPATH");
                String path = checkedPath(artifact.get("path"), "BE-05.template.artifacts.path");
                if (role.equals("CONTROLLER_IMPLEMENTATION")) controllerPath = path;
                if (role.equals("OPENAPI_OVERRIDE")) override = true;
            }
            if (!override || controllerPath == null) throw invalid("BE-05 requires OpenAPI override and controller template");
            Map<?, ?> angularTemplate = map(engineGeneration.get("template"), "BE-13.template");
            Object angularArtifacts = angularTemplate.get("artifacts");
            if (!(angularArtifacts instanceof List<?> angularList)) throw invalid("BE-13.template.artifacts must be a list");
            String typescriptPath = null;
            String htmlPath = null;
            for (Object entry : angularList) {
                Map<?, ?> artifact = map(entry, "BE-13.template.artifacts");
                if (!Objects.equals(artifact.get("location_type"), "CLASSPATH")) throw invalid("BE-13 template must use CLASSPATH");
                String path = checkedPath(artifact.get("path"), "BE-13.template.artifacts.path");
                String role = required(artifact.get("role"), "BE-13.template.artifacts.role");
                if (role.equals("ANGULAR_TYPESCRIPT") && typescriptPath == null) typescriptPath = path;
                else if (role.equals("ANGULAR_HTML") && htmlPath == null) htmlPath = path;
                else throw invalid("BE-13 template role is duplicate or unsupported");
            }
            if (typescriptPath == null || htmlPath == null) throw invalid("BE-13 requires both Angular templates");
            String angularVersion = required(angularTemplate.get("version"), "BE-13.template.version");
            return new GenerationCatalog(version,
                    new Choice(entityPath, required(entityTemplate.get("version"), "BE-02.template.version")),
                    new Choice(controllerPath, required(crudTemplate.get("version"), "BE-05.template.version")),
                    new Choice(typescriptPath, angularVersion), new Choice(htmlPath, angularVersion),
                    required(map(engineGeneration.get("tool"), "BE-13.tool").get("version"), "BE-13.tool.version"),
                    java.util.stream.Stream.of(entity, crud, engine)
                            .flatMap(item -> standardRefs(item).stream()).distinct().toList());
        } catch (GenerationValidationException ex) { throw ex; }
        catch (Exception ex) { throw invalid("cannot read packaged components.yaml: " + ex.getMessage()); }
    }

    private static Map<?, ?> component(List<?> components, String id) {
        for (Object item : components) {
            Map<?, ?> entry = map(item, "components entry");
            if (id.equals(entry.get("id"))) return entry;
        }
        throw invalid(id + " is missing");
    }

    private static Map<?, ?> generation(Map<?, ?> component, String id, String strategy) {
        Map<?, ?> choice = map(component.get("generation"), id + ".generation");
        if (!"CONFIRMED".equals(choice.get("decision_status")) || !strategy.equals(choice.get("strategy")))
            throw invalid(id + " generation choice is unsupported");
        if (!"CONFIRMED".equals(choice.get("implementation_status")))
            throw invalid(id + " generation implementation is not CONFIRMED");
        return choice;
    }

    private static List<String> standardRefs(Map<?, ?> component) {
        Object refs = map(map(component.get("generation"), "generation").get("validation_rules"),
                "generation.validation_rules").get("standard_refs");
        if (!(refs instanceof List<?> values)) throw invalid("generation.validation_rules.standard_refs must be a list");
        return values.stream().map(String::valueOf).toList();
    }

    private static void tool(Map<?, ?> generation, String id, String name, String version) {
        Map<?, ?> tool = map(generation.get("tool"), id + ".tool");
        if (!name.equals(tool.get("name")) || version != null && !version.equals(tool.get("version")))
            throw invalid(id + " tool choice is unsupported");
    }

    private static void supplement(Map<?, ?> generation, String id) {
        Map<?, ?> supplement = map(map(generation.get("tool"), id + ".tool").get("supplement"), id + ".tool.supplement");
        if (!"Apache FreeMarker".equals(supplement.get("name")) || !"2.3.34".equals(supplement.get("version")))
            throw invalid(id + " FreeMarker supplement is unsupported");
    }

    private static String checkedPath(Object value, String location) {
        String path = required(value, location);
        if (!path.startsWith("templates/") || path.contains("..") || path.contains("\\") || path.contains(":") || path.startsWith("/"))
            throw invalid(location + " must be a safe classpath template path");
        if (GenerationCatalog.class.getResource("/" + path) == null) throw invalid(location + " is missing from the classpath");
        return path;
    }

    private static Map<?, ?> map(Object value, String location) {
        if (value instanceof Map<?, ?> result) return result;
        throw invalid(location + " must be an object");
    }

    private static String required(Object value, String location) {
        if (value instanceof String result && !result.isBlank()) return result;
        throw invalid(location + " is required");
    }

    private static GenerationValidationException invalid(String detail) {
        return new GenerationValidationException(List.of("catalog: " + detail));
    }
}
