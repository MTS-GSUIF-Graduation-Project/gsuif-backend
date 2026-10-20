package eg.mts.gsuif.generation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import eg.mts.gsuif.generation.GenerationSpecification.FieldSpec;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;
import tools.jackson.databind.json.JsonMapper;

import javax.tools.ToolProvider;
import java.lang.reflect.Proxy;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;

/**
 * Contract-driven request-validation harness. Valid bodies and mutations are derived from the
 * selected contract's request schemas (plus the entity's persistence column lengths), not from a
 * hand-written per-entity list, so the same harness scores any generated package.
 */
final class ContractDrivenRequestHarness {
    static final List<String> FAMILIES = List.of("REQUIRED_FIELD", "WRONG_TYPE", "INVALID_ENUM",
            "DUPLICATE_VALUE", "BOUNDARY_VIOLATION", "PATTERN_MISMATCH");
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final JsonNodeFactory NODES = JsonNodeFactory.instance;
    private static final String ITEM_ID = "00000000-0000-0000-0000-000000000001";

    record Case(String family, String name, String method, String path, String body, boolean valid, String errorKey) { }

    record Outcome(int status, Set<String> errorKeys) { }

    @FunctionalInterface
    interface Endpoint { Outcome send(String method, String path, String body) throws Exception; }

    record FamilyScore(String family, int cases, int rejected, int correctKey) {
        boolean detected() { return cases > 0 && rejected == cases && correctKey == cases; }
    }

    record Report(String subject, Map<String, FamilyScore> families, List<String> wronglyRejected,
                  List<String> trace, List<String> notes) {
        Set<String> detected() {
            Set<String> detected = new LinkedHashSet<>();
            families.values().forEach(f -> { if (f.detected()) detected.add(f.family()); });
            return detected;
        }

        Set<String> applicable() {
            Set<String> applicable = new LinkedHashSet<>();
            families.values().forEach(f -> { if (f.cases() > 0) applicable.add(f.family()); });
            return applicable;
        }

        String render() {
            StringBuilder out = new StringBuilder("SCRUM-50 contract-driven validation score - ").append(subject).append('\n');
            out.append(String.format("%-20s %6s %9s %12s %9s%n", "family", "cases", "rejected", "correct-key", "detected"));
            for (FamilyScore family : families.values())
                out.append(String.format("%-20s %6d %9d %12d %9s%n", family.family(), family.cases(), family.rejected(),
                        family.correctKey(), family.cases() == 0 ? "n/a" : family.detected() ? "YES" : "NO"));
            out.append("families detected: ").append(detected().size()).append('/').append(applicable().size())
                    .append(" applicable ").append(detected()).append('\n');
            out.append("valid cases wrongly rejected: ").append(wronglyRejected).append('\n');
            notes.forEach(note -> out.append("note: ").append(note).append('\n'));
            out.append("cases:\n");
            trace.forEach(line -> out.append(line).append('\n'));
            return out.toString();
        }
    }

    private ContractDrivenRequestHarness() { }

    /** Derives the corpus for every request body operation in the contract. */
    static List<Case> derive(byte[] contractYaml, Map<String, FieldSpec> entityFields, List<String> notes) throws Exception {
        JsonNode contract = new YAMLMapper().readTree(contractYaml);
        JsonNode schemas = contract.path("components").path("schemas");
        List<Case> cases = new ArrayList<>();
        for (Iterator<Map.Entry<String, JsonNode>> paths = contract.path("paths").fields(); paths.hasNext(); ) {
            var path = paths.next();
            for (String verb : List.of("post", "put")) {
                JsonNode operation = path.getValue().path(verb);
                String ref = operation.path("requestBody").path("content").path("application/json").path("schema").path("$ref").asText("");
                if (ref.isEmpty()) continue;
                String schemaName = ref.substring(ref.lastIndexOf('/') + 1);
                String url = path.getKey().replace("{id}", ITEM_ID);
                cases.addAll(deriveForSchema(verb.toUpperCase(Locale.ROOT), url, schemaName, schemas, entityFields, notes));
            }
        }
        return cases;
    }

    private static List<Case> deriveForSchema(String method, String url, String schemaName, JsonNode schemas,
                                              Map<String, FieldSpec> entityFields, List<String> notes) {
        JsonNode schema = schemas.path(schemaName);
        Set<String> required = new LinkedHashSet<>();
        schema.path("required").forEach(item -> required.add(item.asText()));
        Map<String, JsonNode> properties = new LinkedHashMap<>();
        schema.path("properties").fields().forEachRemaining(entry -> properties.put(entry.getKey(), entry.getValue()));
        ObjectNode valid = NODES.objectNode();
        Map<String, JsonNode> resolved = new LinkedHashMap<>();
        for (var property : properties.entrySet()) {
            JsonNode field = resolve(property.getValue(), schemas);
            resolved.put(property.getKey(), field);
            valid.set(property.getKey(), validValue(schemaName + "." + property.getKey(), field,
                    entityFields.get(property.getKey()), notes));
        }
        List<Case> cases = new ArrayList<>();
        String label = method + " " + schemaName;
        cases.add(new Case("VALID", label + " valid body", method, url, valid.toString(), true, null));
        for (var entry : resolved.entrySet()) {
            String name = entry.getKey();
            JsonNode field = entry.getValue();
            boolean nullable = field.path("nullable").asBoolean(false) || properties.get(name).path("nullable").asBoolean(false);
            String type = field.path("type").asText("");
            String format = field.path("format").asText("");
            boolean isEnum = field.has("enum");
            boolean plainString = type.equals("string") && format.isEmpty() && !isEnum;
            if (nullable)
                cases.add(new Case("VALID", label + " " + name + "=null", method, url, with(valid, name, NODES.nullNode()), true, null));
            if (required.contains(name))
                cases.add(new Case("REQUIRED_FIELD", label + " omit " + name, method, url, without(valid, name), false, name));
            if (!nullable)
                cases.add(new Case("REQUIRED_FIELD", label + " " + name + "=null", method, url, with(valid, name, NODES.nullNode()), false, name));
            for (var wrong : wrongTypes(type, format, isEnum).entrySet())
                cases.add(new Case("WRONG_TYPE", label + " " + name + " as " + wrong.getKey(), method, url,
                        with(valid, name, wrong.getValue()), false, name));
            if (isEnum) {
                cases.add(new Case("INVALID_ENUM", label + " " + name + " undeclared value", method, url,
                        with(valid, name, NODES.textNode("NOT_A_DECLARED_VALUE")), false, name));
                cases.add(new Case("INVALID_ENUM", label + " " + name + " lower-case value", method, url,
                        with(valid, name, NODES.textNode(field.path("enum").get(0).asText().toLowerCase(Locale.ROOT))), false, name));
            }
            String member = JSON.valueToTree(name).toString() + ":" + valid.get(name).toString();
            String duplicated = "{" + member + "," + valid.toString().substring(1);
            cases.add(new Case("DUPLICATE_VALUE", label + " duplicate " + name, method, url, duplicated, false, name));
            if (plainString) {
                FieldSpec entity = entityFields.get(name);
                int column = entity == null || entity.columnLength() == null ? 255 : entity.columnLength();
                int max = field.has("maxLength") ? field.path("maxLength").asInt() : column;
                String sample = valid.get(name).asText();
                Pattern pattern = field.has("pattern") ? Pattern.compile(field.path("pattern").asText()) : null;
                String atMax = stretch(sample, max);
                if (pattern == null || pattern.matcher(atMax).find())
                    cases.add(new Case("VALID", label + " " + name + " length " + max, method, url,
                            with(valid, name, NODES.textNode(atMax)), true, null));
                cases.add(new Case("BOUNDARY_VIOLATION", label + " " + name + " length " + (max + 1)
                        + (field.has("maxLength") ? " (contract maxLength)" : " (column length)"), method, url,
                        with(valid, name, NODES.textNode(stretch(sample, max + 1))), false, name));
                if (field.has("minLength") && field.path("minLength").asInt() > 0)
                    cases.add(new Case("BOUNDARY_VIOLATION", label + " " + name + " length " + (field.path("minLength").asInt() - 1),
                            method, url, with(valid, name, NODES.textNode(sample.substring(0, field.path("minLength").asInt() - 1))), false, name));
                if (pattern != null) {
                    String mismatch = "!".repeat(Math.max(field.path("minLength").asInt(1), 1));
                    if (pattern.matcher(mismatch).find()) notes.add(label + "." + name + ": could not synthesize a pattern mismatch");
                    else cases.add(new Case("PATTERN_MISMATCH", label + " " + name + " violates pattern", method, url,
                            with(valid, name, NODES.textNode(mismatch)), false, name));
                }
            }
            if (type.equals("integer")) {
                if (field.has("minimum"))
                    cases.add(new Case("BOUNDARY_VIOLATION", label + " " + name + " = minimum-1", method, url,
                            with(valid, name, NODES.numberNode(field.path("minimum").asLong() - 1)), false, name));
                if (field.has("maximum"))
                    cases.add(new Case("BOUNDARY_VIOLATION", label + " " + name + " = maximum+1", method, url,
                            with(valid, name, NODES.numberNode(field.path("maximum").asLong() + 1)), false, name));
                if (format.equals("int32"))
                    cases.add(new Case("BOUNDARY_VIOLATION", label + " " + name + " beyond int32", method, url,
                            with(valid, name, NODES.numberNode(2147483648L)), false, name));
            }
        }
        return cases;
    }

    private static Map<String, JsonNode> wrongTypes(String type, String format, boolean isEnum) {
        Map<String, JsonNode> wrong = new LinkedHashMap<>();
        if (isEnum) { wrong.put("number (ordinal)", NODES.numberNode(1)); return wrong; }
        switch (type) {
            case "string" -> {
                wrong.put("number", NODES.numberNode(42));
                wrong.put("boolean", NODES.booleanNode(true));
                if (!format.isEmpty()) wrong.put("non-" + format + " string", NODES.textNode("not-a-" + format));
                if (format.equals("date") || format.equals("date-time")) wrong.put("epoch number", NODES.numberNode(20260131));
            }
            case "integer" -> {
                wrong.put("numeric string", NODES.textNode("5"));
                wrong.put("fraction", NODES.numberNode(1.5));
                wrong.put("boolean", NODES.booleanNode(true));
            }
            case "boolean" -> {
                wrong.put("string", NODES.textNode("true"));
                wrong.put("number", NODES.numberNode(1));
            }
            default -> { }
        }
        return wrong;
    }

    private static JsonNode validValue(String path, JsonNode field, FieldSpec entity, List<String> notes) {
        if (field.has("enum")) return NODES.textNode(field.path("enum").get(0).asText());
        if (field.has("example")) return JSON.valueToTree(field.get("example").isTextual() ? field.get("example").asText() : field.get("example"));
        String type = field.path("type").asText("");
        String format = field.path("format").asText("");
        return switch (type + ":" + format) {
            case "string:date" -> NODES.textNode("2026-01-31");
            case "string:date-time" -> NODES.textNode("2026-01-31T10:15:30");
            case "string:uuid" -> NODES.textNode(ITEM_ID);
            case "boolean:" -> NODES.booleanNode(true);
            case "integer:int32", "integer:int64" -> {
                long value = field.has("minimum") ? field.path("minimum").asLong()
                        : field.has("maximum") ? Math.min(1, field.path("maximum").asLong()) : 1;
                yield NODES.numberNode(value);
            }
            default -> {
                if (field.has("pattern")) notes.add(path + ": pattern without example; synthesized value may not match");
                yield NODES.textNode("A".repeat(Math.max(field.path("minLength").asInt(1), 1)));
            }
        };
    }

    private static String stretch(String sample, int length) {
        if (sample.isEmpty()) return "A".repeat(length);
        if (sample.length() >= length) return sample.substring(0, length);
        return sample + String.valueOf(sample.charAt(sample.length() - 1)).repeat(length - sample.length());
    }

    private static JsonNode resolve(JsonNode property, JsonNode schemas) {
        String ref = property.path("$ref").asText("");
        return ref.isEmpty() ? property : schemas.path(ref.substring(ref.lastIndexOf('/') + 1));
    }

    private static String with(ObjectNode valid, String name, JsonNode value) {
        ObjectNode copy = valid.deepCopy();
        copy.set(name, value);
        return copy.toString();
    }

    private static String without(ObjectNode valid, String name) {
        ObjectNode copy = valid.deepCopy();
        copy.remove(name);
        return copy.toString();
    }

    static Report run(String subject, List<Case> cases, List<String> notes, Endpoint endpoint) throws Exception {
        Map<String, int[]> counts = new LinkedHashMap<>();
        FAMILIES.forEach(family -> counts.put(family, new int[3]));
        List<String> wronglyRejected = new ArrayList<>();
        List<String> trace = new ArrayList<>();
        for (Case item : cases) {
            Outcome outcome = endpoint.send(item.method(), item.path(), item.body());
            trace.add(String.format("  %-6s %-62s -> HTTP %d errors=%s", item.valid() ? "ACCEPT" : "REJECT",
                    item.name(), outcome.status(), outcome.errorKeys()));
            if (item.valid()) {
                if (outcome.status() >= 300) wronglyRejected.add(item.name() + " -> " + outcome);
                continue;
            }
            int[] count = counts.get(item.family());
            count[0]++;
            if (outcome.status() == 400) count[1]++;
            if (outcome.status() == 400 && outcome.errorKeys().equals(Set.of(item.errorKey()))) count[2]++;
        }
        Map<String, FamilyScore> families = new LinkedHashMap<>();
        counts.forEach((family, count) -> families.put(family, new FamilyScore(family, count[0], count[1], count[2])));
        return new Report(subject, families, wronglyRejected, trace, List.copyOf(notes));
    }

    /** Compiles the generated request layer under {@code javaRoot} and returns a MockMvc for it. */
    static MockMvc compile(Path javaRoot, String basePackage, String entity, Path classes,
                           List<URLClassLoader> opened) throws Exception {
        List<String> files = new ArrayList<>();
        for (String directory : List.of("api", "controller", "dto", "exception"))
            try (var paths = Files.list(javaRoot.resolve(directory))) {
                paths.map(Path::toString).filter(path -> path.endsWith(".java")).forEach(files::add);
            }
        Path strict = javaRoot.resolve("config/RequestJsonStrictnessConfig.java");
        if (Files.exists(strict)) files.add(strict.toString());
        for (String single : List.of("service/" + entity + "Service.java", "logging/SafeFailureLogger.java", "aspect/Loggable.java"))
            files.add(javaRoot.resolve(single).toString());
        Files.createDirectories(classes);
        List<String> arguments = new ArrayList<>(List.of("-proc:none", "-parameters",
                "-classpath", System.getProperty("java.class.path"), "-d", classes.toString()));
        arguments.addAll(files);
        if (ToolProvider.getSystemJavaCompiler().run(null, null, null, arguments.toArray(String[]::new)) != 0)
            throw new AssertionError("Generated request layer does not compile: " + javaRoot);
        var loader = new URLClassLoader(new java.net.URL[]{classes.toUri().toURL()}, ContractDrivenRequestHarness.class.getClassLoader());
        opened.add(loader);
        Class<?> serviceType = loader.loadClass(basePackage + ".service." + entity + "Service");
        Object service = Proxy.newProxyInstance(loader, new Class<?>[]{serviceType}, (proxy, method, args) ->
                switch (method.getName()) {
                    case "toString" -> "StubService";
                    case "hashCode" -> 0;
                    case "equals" -> proxy == args[0];
                    default -> null;
                });
        Object controller = loader.loadClass(basePackage + ".controller." + entity + "Controller")
                .getConstructor(serviceType).newInstance(service);
        Object handler = loader.loadClass(basePackage + ".exception.GlobalExceptionHandler").getConstructor().newInstance();
        Class<?> strictConfig = Files.exists(strict) ? loader.loadClass(basePackage + ".config.RequestJsonStrictnessConfig") : null;
        var validator = new LocalValidatorFactoryBean();
        validator.afterPropertiesSet();
        return MockMvcBuilders.standaloneSetup(controller).setControllerAdvice(handler)
                .setMessageConverters(new JacksonJsonHttpMessageConverter(generatedAppJsonMapper(loader, strictConfig)))
                .setValidator(validator).build();
    }

    /** The JsonMapper the generated app gets: Boot's Jackson auto-configuration plus its own config classes. */
    static JsonMapper generatedAppJsonMapper(ClassLoader loader, Class<?> strictConfig) {
        AtomicReference<JsonMapper> mapper = new AtomicReference<>();
        var runner = new ApplicationContextRunner().withClassLoader(loader)
                .withConfiguration(AutoConfigurations.of(JacksonAutoConfiguration.class));
        if (strictConfig != null) runner = runner.withUserConfiguration(strictConfig);
        runner.run(context -> mapper.set(context.getBean(JsonMapper.class)));
        return mapper.get();
    }

    static Endpoint endpoint(MockMvc mvc) {
        return (method, path, body) -> {
            var response = mvc.perform(request(HttpMethod.valueOf(method), path)
                    .contentType(MediaType.APPLICATION_JSON).accept(MediaType.APPLICATION_JSON)
                    .content(body.getBytes(StandardCharsets.UTF_8))).andReturn().getResponse();
            Set<String> keys = new LinkedHashSet<>();
            String content = response.getContentAsString(StandardCharsets.UTF_8);
            if (response.getStatus() >= 400 && !content.isBlank()) {
                JsonNode envelope = JSON.readTree(content);
                if (envelope.path("statusCode").asInt() != response.getStatus())
                    throw new AssertionError("STD-01 envelope statusCode mismatch: " + content);
                envelope.path("errors").fieldNames().forEachRemaining(keys::add);
            }
            return new Outcome(response.getStatus(), keys);
        };
    }

    static void write(Report report, String file) throws Exception {
        System.out.print(report.render());
        Path directory = Files.createDirectories(Path.of("target", "generation-evidence"));
        Files.writeString(directory.resolve(file), report.render());
    }
}
