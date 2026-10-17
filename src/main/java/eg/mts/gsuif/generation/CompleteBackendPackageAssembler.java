package eg.mts.gsuif.generation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import eg.mts.gsuif.generation.GenerationCapabilityPreflight.OutputMode;
import eg.mts.gsuif.generation.GenerationFilePlan.Owner;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/** Internal, byte-preserving BACKEND assembly; it does not publish or record a run. */
final class CompleteBackendPackageAssembler {
    private static final String MANIFEST = "generation-manifest.json";
    private final CompleteBackendRuntimeRenderer runtime;

    record Provenance(String providerId, String providerVersion, String modelId, boolean mocked) {
        Provenance {
            for (String value : List.of(providerId, providerVersion, modelId)) {
                if (value == null || !value.matches("[A-Za-z0-9][A-Za-z0-9._/-]{0,99}"))
                    throw new IllegalArgumentException("Invalid bounded model provenance");
            }
        }
    }

    static final class Assembled {
        private final Map<String, byte[]> files;

        private Assembled(Map<String, byte[]> files) { this.files = copy(files); }
        Map<String, byte[]> files() { return copy(files); }
        byte[] file(String path) {
            byte[] bytes = files.get(path);
            return bytes == null ? null : bytes.clone();
        }
    }

    CompleteBackendPackageAssembler(CompleteBackendRuntimeRenderer runtime) {
        this.runtime = java.util.Objects.requireNonNull(runtime);
    }

    Assembled assemble(BackendModelInput input, BackendModelOutputValidator.Accepted model,
            Provenance provenance) {
        requireInvocation(input, model, provenance);
        var descriptor = input.descriptor();
        var plan = input.plan();
        var definitions = new CompleteBackendDefinitionRenderer().render(descriptor, plan);
        var runtimeFiles = runtime.render(descriptor, plan);
        String contractPath = "contracts/" + descriptor.contractId() + "-" + descriptor.contractVersion() + ".yaml";
        if (!java.util.Arrays.equals(input.contract(), runtimeFiles.get(contractPath))
                || !java.util.Arrays.equals(input.pom(), runtimeFiles.get("backend/pom.xml")))
            throw new IllegalArgumentException("Rendered model inputs differ from assembled bytes");
        if (!input.handoff().specification().equals(definitions.handoff().specification()))
            throw new IllegalArgumentException("Model definition handoff differs from assembly");
        var rendered = definitions.handoff().definitions().stream().collect(java.util.stream.Collectors.toMap(
                RenderedModelHandoff.Definition::path, definition -> definition));
        for (var definition : input.handoff().definitions()) {
            var current = rendered.get(definition.path());
            if (current == null || !current.sha256().equals(definition.sha256())
                    || !java.util.Arrays.equals(current.bytes(), definition.bytes()))
                throw new IllegalArgumentException("Model definition bytes differ from assembly");
        }
        return combine(input, model, provenance, List.of(definitions.files(), runtimeFiles,
                new CompleteBackendApiRenderer().render(descriptor, plan),
                new CompleteBackendInitialMigrationRenderer().render(descriptor, plan),
                new CompleteBackendErrorRenderer().render(descriptor, plan),
                new CompleteBackendSecurityRenderer().render(descriptor, plan),
                new CompleteBackendBootstrapRenderer().render(descriptor, plan),
                new CompleteBackendAuditRenderer().render(descriptor, plan),
                new CompleteBackendObservabilityRenderer().render(descriptor, plan),
                new CompleteBackendOpenApiRenderer().render(descriptor, plan)));
    }

    /** Package-private seam for checking cross-renderer output before any file is returned. */
    static Assembled combine(BackendModelInput input, BackendModelOutputValidator.Accepted model,
            Provenance provenance, List<Map<String, byte[]>> rendered) {
        requireInvocation(input, model, provenance);
        if (rendered == null) throw new IllegalArgumentException("Missing backend renderer output");
        Map<String, Owner> planned = new HashMap<>();
        input.plan().entries().forEach(entry -> planned.put(entry.path(), entry.owner()));
        TreeMap<String, byte[]> files = new TreeMap<>();
        Set<String> aliases = new HashSet<>();
        for (Map<String, byte[]> portion : rendered) {
            if (portion == null) throw new IllegalArgumentException("Missing backend renderer output");
            for (var entry : portion.entrySet())
                add(files, aliases, planned, entry.getKey(), entry.getValue(), Owner.DETERMINISTIC);
        }
        add(files, aliases, planned, model.path(), model.source(), Owner.MODEL);
        Set<String> expected = new HashSet<>(planned.keySet());
        expected.remove(MANIFEST);
        if (!expected.equals(files.keySet())) throw new IllegalArgumentException("Backend package differs from file plan");
        String contractPath = "contracts/" + input.descriptor().contractId() + "-"
                + input.descriptor().contractVersion() + ".yaml";
        if (!sha256(files.get(contractPath)).equals(input.descriptor().contractResourceSha256())
                || !java.util.Arrays.equals(files.get(contractPath), input.contract())
                || !java.util.Arrays.equals(files.get("backend/pom.xml"), input.pom()))
            throw new IllegalArgumentException("Selected model input bytes differ from package");
        for (var definition : input.handoff().definitions()) {
            byte[] bytes = files.get(definition.path());
            if (bytes == null || !sha256(bytes).equals(definition.sha256())
                    || !java.util.Arrays.equals(bytes, definition.bytes()))
                throw new IllegalArgumentException("Model handoff differs from package");
        }
        add(files, aliases, planned, MANIFEST, manifest(input, provenance, files, planned), Owner.ASSEMBLER);
        if (!planned.keySet().equals(files.keySet())) throw new IllegalArgumentException("Incomplete backend package");
        return new Assembled(files);
    }

    private static void requireInvocation(BackendModelInput input, BackendModelOutputValidator.Accepted model,
            Provenance provenance) {
        if (input == null || model == null || provenance == null || input.descriptor().mode() != OutputMode.BACKEND)
            throw new IllegalArgumentException("Assembly requires one BACKEND invocation");
        if (!input.plan().equals(GenerationFilePlan.forDescriptor(input.descriptor())))
            throw new IllegalArgumentException("File plan differs from frozen descriptor");
        if (!model.belongsTo(input) || !input.writablePath().equals(model.path()))
            throw new IllegalArgumentException("Model output belongs to another invocation");
    }

    private static void add(Map<String, byte[]> files, Set<String> aliases, Map<String, Owner> planned,
            String path, byte[] bytes, Owner owner) {
        GenerationArtifactStore.safeRelative(path);
        if (planned.get(path) != owner || bytes == null)
            throw new IllegalArgumentException("Unplanned or incorrectly owned backend path");
        if (!aliases.add(path.toLowerCase(Locale.ROOT)) || files.putIfAbsent(path, bytes.clone()) != null)
            throw new IllegalArgumentException("Duplicate backend output path");
    }

    private static byte[] manifest(BackendModelInput input, Provenance provenance,
            Map<String, byte[]> files, Map<String, Owner> planned) {
        ObjectNode root = new ObjectMapper().createObjectNode();
        root.put("formatVersion", 1);
        root.put("outputMode", "BACKEND");
        root.put("metadataVersionId", input.descriptor().metadataVersionId() == null
                ? null : input.descriptor().metadataVersionId().toString());
        root.put("metadataVersionNumber", input.descriptor().metadataVersionNumber());
        ObjectNode contract = root.putObject("contract");
        contract.put("id", input.descriptor().contractId());
        contract.put("version", input.descriptor().contractVersion());
        contract.put("sha256", input.descriptor().contractResourceSha256());
        ObjectNode provider = root.putObject("provider");
        provider.put("id", provenance.providerId());
        provider.put("version", provenance.providerVersion());
        provider.put("model", provenance.modelId());
        provider.put("mocked", provenance.mocked());
        ArrayNode listed = root.putArray("files");
        files.forEach((path, bytes) -> {
            ObjectNode item = listed.addObject();
            item.put("path", path);
            item.put("owner", planned.get(path).name());
            item.put("bytes", bytes.length);
            item.put("sha256", sha256(bytes));
        });
        try { return new ObjectMapper().writeValueAsBytes(root); }
        catch (com.fasterxml.jackson.core.JsonProcessingException ex) {
            throw new IllegalStateException("Could not create backend manifest", ex);
        }
    }

    private static String sha256(byte[] bytes) {
        if (bytes == null) throw new IllegalArgumentException("Missing required backend bytes");
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (NoSuchAlgorithmException ex) { throw new IllegalStateException(ex); }
    }

    private static Map<String, byte[]> copy(Map<String, byte[]> source) {
        TreeMap<String, byte[]> frozen = new TreeMap<>();
        source.forEach((path, bytes) -> frozen.put(path, bytes.clone()));
        return Collections.unmodifiableMap(frozen);
    }
}
