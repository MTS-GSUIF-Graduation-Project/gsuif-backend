package eg.mts.gsuif.generation;

import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.util.Map;

/** The sole list of approved classpath contracts lives in packaged-contracts.yaml. */
final class PackagedContractRegistry {
    private static final String RESOURCE = "/generation/packaged-contracts.yaml";
    private final Map<String, String> paths;

    @SuppressWarnings("unchecked")
    PackagedContractRegistry() {
        try (InputStream input = getClass().getResourceAsStream(RESOURCE)) {
            if (input == null) throw new IllegalStateException("missing " + RESOURCE);
            Object parsed = new Yaml().load(input);
            if (!(parsed instanceof Map<?, ?> root) || !(root.get("contracts") instanceof Map<?, ?> raw))
                throw new IllegalStateException("invalid " + RESOURCE);
            for (var entry : raw.entrySet()) {
                if (!(entry.getKey() instanceof String id) || id.isBlank()
                        || !(entry.getValue() instanceof String path) || !path.matches("/openapi/[a-z0-9-]+\\.yaml"))
                    throw new IllegalStateException("invalid entry in " + RESOURCE);
            }
            paths = Map.copyOf((Map<String, String>) raw);
        } catch (Exception ex) {
            throw new IllegalStateException("cannot load " + RESOURCE, ex);
        }
    }

    String resource(String id) { return paths.get(id); }
}
