package eg.mts.gsuif.generation;

import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.List;
import java.util.Comparator;

/** Versioned, explicit mapping for the approved packaged CRUD contracts. */
final class PackagedContractRegistry {
    enum CrudAction { CREATE, READ, LIST, UPDATE, DELETE }

    record Entry(String id, String version, String resource, boolean fullUpdate,
                 Map<CrudAction, String> operations) {
        Entry {
            operations = Map.copyOf(operations);
        }

        String operation(CrudAction action) { return operations.get(action); }
    }

    private static final String RESOURCE = "/generation/packaged-contracts.yaml";
    private final Map<String, Map<String, Entry>> contracts;

    PackagedContractRegistry() { this(RESOURCE); }

    @SuppressWarnings("unchecked")
    PackagedContractRegistry(String registryResource) {
        try (InputStream input = getClass().getResourceAsStream(registryResource)) {
            if (input == null) throw new IllegalStateException("missing " + registryResource);
            Object parsed = new Yaml().load(input);
            if (!(parsed instanceof Map<?, ?> root) || !(root.get("contracts") instanceof Map<?, ?> raw))
                throw new IllegalStateException("invalid " + registryResource);
            Map<String, Map<String, Entry>> byId = new LinkedHashMap<>();
            for (var contract : raw.entrySet()) {
                if (!(contract.getKey() instanceof String id) || id.isBlank()
                        || !(contract.getValue() instanceof Map<?, ?> versions) || versions.isEmpty())
                    throw new IllegalStateException("invalid contract in " + registryResource);
                Map<String, Entry> byVersion = new LinkedHashMap<>();
                for (var versioned : versions.entrySet()) {
                    if (!(versioned.getKey() instanceof String version) || !version.matches("[0-9]+\\.[0-9]+\\.[0-9]+")
                            || !(versioned.getValue() instanceof Map<?, ?> details)
                            || !(details.get("resource") instanceof String resource)
                            || !resource.matches("/openapi/[a-z0-9-]+\\.yaml")
                            || !(details.get("operations") instanceof Map<?, ?> actions)
                            || !(details.get("updateMode") instanceof String updateMode)
                            || !(updateMode.equals("FULL") || updateMode.equals("LEGACY_OPTIONAL")))
                        throw new IllegalStateException("invalid versioned contract in " + registryResource);
                    EnumMap<CrudAction, String> mapped = new EnumMap<>(CrudAction.class);
                    for (var action : actions.entrySet()) {
                        CrudAction key = CrudAction.valueOf(String.valueOf(action.getKey()));
                        if (!(action.getValue() instanceof String operationId)
                                || !GenerationContextBuilder.identifier(operationId, "[a-z][A-Za-z0-9]*"))
                            throw new IllegalStateException("invalid CRUD operation in " + registryResource);
                        mapped.put(key, operationId);
                    }
                    if (mapped.size() != CrudAction.values().length
                            || mapped.values().stream().distinct().count() != CrudAction.values().length)
                        throw new IllegalStateException("five distinct CRUD operations required in " + registryResource);
                    byVersion.put(version, new Entry(id, version, resource, updateMode.equals("FULL"), mapped));
                }
                byId.put(id, Map.copyOf(byVersion));
            }
            contracts = Map.copyOf(byId);
        } catch (Exception ex) {
            throw new IllegalStateException("cannot load " + registryResource, ex);
        }
    }

    Entry find(String id, String version) {
        if (id == null || version == null) return null;
        return contracts.getOrDefault(id, Map.of()).get(version);
    }

    List<Entry> entries() {
        return contracts.values().stream().flatMap(versions -> versions.values().stream())
                .sorted(Comparator.comparing(Entry::id).thenComparing(Entry::version))
                .toList();
    }

    boolean containsId(String id) { return id != null && contracts.containsKey(id); }

    String resource(String id, String version) {
        Entry entry = find(id, version);
        return entry == null ? null : entry.resource();
    }
}
