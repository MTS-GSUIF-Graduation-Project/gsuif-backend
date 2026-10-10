package eg.mts.gsuif.validator;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import eg.mts.gsuif.repository.GsuifPageRepository;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import java.lang.reflect.InvocationTargetException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Internal test-first seam; absent production types fail assertions, never skip tests. */
public final class BusinessRuleTestSupport {
    public static final ObjectMapper JSON = new ObjectMapper();
    public static final UUID PROJECT = new UUID(0, 1), PAGE = new UUID(0, 2);
    public static final String COMPONENT = "ComponentIdUniqueness", ROUTE = "RouteUniqueness",
            METHOD = "SupportedHttpMethod", SIZE = "SnapshotSize";
    private static final String PREFIX = "eg.mts.gsuif.validator.";

    private BusinessRuleTestSupport() { }

    public static ObjectNode snapshot(int count, long salt, String method) {
        ObjectNode snapshot = JSON.createObjectNode();
        var components = snapshot.putArray("components");
        for (int i = 0; i < count; i++) {
            ObjectNode c = components.addObject();
            c.put("id", new UUID(salt, i).toString());
            c.put("type", "text-field");
            c.put("label", "é中\"\\\n");
            c.putObject("position").put("row", i).put("col", 0);
            c.putObject("size").put("width", 1).put("height", 1);
            c.put("visibility", true).put("disabled", false);
        }
        var binding = snapshot.putArray("apiBindings").addObject();
        binding.put("id", new UUID(salt, 99).toString());
        binding.put("httpMethod", method).put("endpointUrl", "/items");
        binding.putObject("headers");
        binding.putObject("requestMapping");
        binding.putObject("responseMapping");
        return snapshot;
    }

    public static ObjectNode duplicate(long salt) {
        ObjectNode s = snapshot(2, salt, "GET");
        ((ObjectNode) s.get("components").get(1)).put("id", s.get("components").get(0).get("id").asText());
        return s;
    }

    /** Five separately bounded strings avoid parser string limits. Byte count is independent. */
    public static ObjectNode sized(int bytes, boolean duplicate) {
        ObjectNode s = snapshot(5, 51, "GET");
        if (duplicate) ((ObjectNode) s.get("components").get(1))
                .put("id", s.get("components").get(0).get("id").asText());
        int remaining = bytes - utf8(s.toString());
        for (int i = 0; i < 5; i++) {
            ObjectNode c = (ObjectNode) s.get("components").get(i);
            int padding = remaining / (5 - i);
            c.put("label", c.get("label").asText() + "a".repeat(padding));
            remaining -= padding;
        }
        assertThat(utf8(s.toString())).isEqualTo(bytes);
        assertThat(new MetadataSchemaValidator().validate("1.0.0", s)).isEmpty();
        return s;
    }

    public static int utf8(String value) { return value.getBytes(StandardCharsets.UTF_8).length; }

    public static Class<?> type(String name) {
        try { return Class.forName(PREFIX + name); }
        catch (ClassNotFoundException e) { throw new AssertionError("Missing business-rule seam: " + name, e); }
    }

    public static Object invoke(Object receiver, String method, Class<?>[] types, Object... args) {
        try {
            Class<?> owner = receiver instanceof Class<?> c ? c : receiver.getClass();
            return owner.getMethod(method, types).invoke(receiver instanceof Class<?> ? null : receiver, args);
        } catch (InvocationTargetException e) {
            if (e.getCause() instanceof RuntimeException r) throw r;
            if (e.getCause() instanceof Error error) throw error;
            throw new AssertionError(e.getCause());
        } catch (ReflectiveOperationException e) { throw new AssertionError("Business seam: " + method, e); }
    }

    public static Object snapshotContext(JsonNode s) {
        return invoke(type("MetadataValidationContext"), "snapshot",
                new Class<?>[]{JsonNode.class, String.class}, s, s.toString());
    }

    public static Object pageContext(UUID project, UUID page, String route) {
        return invoke(type("MetadataValidationContext"), "page",
                new Class<?>[]{UUID.class, UUID.class, String.class}, project, page, route);
    }

    public static AnnotationConfigApplicationContext context(GsuifPageRepository repository) {
        var c = new AnnotationConfigApplicationContext();
        c.registerBean(GsuifPageRepository.class, () -> repository);
        c.scan(PREFIX.substring(0, PREFIX.length() - 1));
        return c;
    }

    public static Object rule(AnnotationConfigApplicationContext c, String name) {
        return c.getBeansOfType(type("MetadataBusinessRule")).values().stream()
                .filter(r -> name.equals(invoke(r, "name", new Class<?>[0])))
                .findFirst().orElseThrow(() -> new AssertionError("Undiscovered rule: " + name));
    }

    @SuppressWarnings("unchecked")
    public static List<MetadataSchemaValidator.ValidationError> errors(Object validator, Object context) {
        return (List<MetadataSchemaValidator.ValidationError>) invoke(validator, "validate",
                new Class<?>[]{type("MetadataValidationContext")}, context);
    }

    public static void rejects(Object rule, Object context, String name, String path) {
        assertThat(errors(rule, context)).anySatisfy(e -> {
            assertThat(e.path()).isEqualTo(path);
            assertThat(e.message()).contains(name);
        });
    }
}
