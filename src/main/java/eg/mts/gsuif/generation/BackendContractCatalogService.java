package eg.mts.gsuif.generation;

import eg.mts.gsuif.dto.GenerationApiDtos;
import org.springframework.stereotype.Service;
import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Contract-level capability, without claiming compatibility with an unvalidated page. */
@Service
public final class BackendContractCatalogService {
    private final PackagedContractRegistry registry;
    private final PackagedContractLoader loader;

    public BackendContractCatalogService() {
        this.registry = new PackagedContractRegistry();
        this.loader = new PackagedContractLoader(registry);
    }

    public List<GenerationApiDtos.AvailableContract> list() {
        List<GenerationApiDtos.AvailableContract> available = new ArrayList<>();
        for (var entry : registry.entries()) {
            if (!entry.fullUpdate()) continue;
            List<String> errors = new ArrayList<>();
            var selected = loader.load(new GenerationSpecification.OpenApiSpec(
                    entry.id(), entry.version(), Map.of()), errors);
            if (selected == null || !errors.isEmpty()) continue;
            available.add(new GenerationApiDtos.AvailableContract(entry.id(), entry.version(),
                    title(entry), java.util.Arrays.stream(PackagedContractRegistry.CrudAction.values())
                            .map(entry::operation).toList(), "FULL", false));
        }
        return List.copyOf(available);
    }

    private String title(PackagedContractRegistry.Entry entry) {
        try (InputStream input = getClass().getResourceAsStream(entry.resource())) {
            if (input == null) throw new IllegalStateException("Registered contract is missing");
            Object parsed = new Yaml().load(input);
            if (parsed instanceof Map<?, ?> root && root.get("info") instanceof Map<?, ?> info) {
                Object label = info.containsKey("title") ? info.get("title") : info.get("label");
                if (label instanceof String text && !text.isBlank()) return text;
            }
            throw new IllegalStateException("Registered contract title is missing");
        } catch (java.io.IOException ex) {
            throw new IllegalStateException("Registered contract cannot be read", ex);
        }
    }
}
