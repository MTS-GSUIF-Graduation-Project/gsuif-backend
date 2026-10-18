package eg.mts.gsuif.generation;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import eg.mts.gsuif.entity.GeneratedArtifact;
import eg.mts.gsuif.entity.GenerationRun;
import eg.mts.gsuif.entity.GenerationRunStatus;
import eg.mts.gsuif.dto.GenerationApiDtos;

import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Checks a committed BACKEND run against both registry rows and the exported package manifest. */
final class BackendPackageIntegrity {
    static final String TOOL = OpenRouterBackendModelAdapter.PROVIDER_ID;
    private BackendPackageIntegrity() { }

    /** Call only after readResult has verified the committed registry, store and manifest. */
    static GenerationApiDtos.BackendPackage summary(GenerationRun run, GenerationResult result) {
        byte[] bytes = result.artifacts().stream()
                .filter(a -> "generation-manifest.json".equals(a.relativePath()))
                .findFirst().orElseThrow(() -> new IllegalStateException("Backend manifest missing")).bytes();
        if (!hash(bytes).equals(run.getPackageManifestSha256()))
            throw new IllegalStateException("Backend manifest differs from committed run");
        try {
            JsonNode manifest = new ObjectMapper().readTree(bytes);
            JsonNode contract = manifest.path("contract");
            boolean mocked = manifest.path("provider").path("mocked").asBoolean(true);
            return new GenerationApiDtos.BackendPackage(contract.path("id").asText(),
                    contract.path("version").asText(), run.getPackageManifestSha256(),
                    new GenerationApiDtos.ValidationScope(mocked
                            ? List.of("MODEL_OUTPUT_STRUCTURE")
                            : List.of("MODEL_OUTPUT_STRUCTURE", "ISOLATED_JAVA_COMPILATION", "STANDALONE_MAVEN_PACKAGE"),
                            mocked
                                    ? List.of("REAL_PROVIDER_RESPONSE", "ISOLATED_JAVA_COMPILATION",
                                            "STANDALONE_MAVEN_PACKAGE", "GENERATED_APP_RUNTIME", "CRUD_BEHAVIOR")
                                    : List.of("GENERATED_APP_RUNTIME", "CRUD_BEHAVIOR")));
        } catch (IOException ex) { throw new IllegalStateException("Backend manifest cannot be read"); }
    }

    static void verify(GenerationRun run, GenerationResult result, List<GeneratedArtifact> registered) {
        if (run.getStatus() != GenerationRunStatus.SUCCESS || !TOOL.equals(run.getToolName()))
            throw new IllegalStateException("Backend run is not a committed success");
        Map<String, byte[]> files = new HashMap<>();
        for (var artifact : result.artifacts()) {
            GenerationArtifactStore.safeRelative(artifact.relativePath());
            if (files.putIfAbsent(artifact.relativePath(), artifact.bytes()) != null)
                throw new IllegalStateException("Backend package contains duplicate paths");
            if (!hash(artifact.bytes()).equals(artifact.sha256()))
                throw new IllegalStateException("Backend package content hash differs");
        }
        Set<String> registeredPaths = new HashSet<>();
        for (var artifact : registered) {
            if (!registeredPaths.add(artifact.getRelativePath()))
                throw new IllegalStateException("Backend artifact registry has duplicate paths");
        }
        if (!registeredPaths.equals(files.keySet()))
            throw new IllegalStateException("Backend artifact registry differs from storage");
        byte[] manifestBytes = files.get("generation-manifest.json");
        if (manifestBytes == null) throw new IllegalStateException("Backend package manifest is missing");
        if (!hash(manifestBytes).equals(run.getPackageManifestSha256()))
            throw new IllegalStateException("Backend package manifest differs from committed run");
        try {
            ObjectMapper mapper = new ObjectMapper();
            JsonNode manifest;
            try (JsonParser parser = mapper.createParser(manifestBytes)) {
                parser.enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
                manifest = mapper.readTree(parser);
                if (parser.nextToken() != null) throw new IOException("Trailing manifest content");
            }
            if (manifest.path("formatVersion").asInt(-1) != 1
                    || !"BACKEND".equals(manifest.path("outputMode").asText())
                    || !run.getMetadataVersion().getId().toString().equals(manifest.path("metadataVersionId").asText())
                    || !TOOL.equals(manifest.path("provider").path("id").asText())
                    || !manifest.path("provider").path("mocked").isBoolean())
                throw new IOException("Backend manifest identity differs from run");
            String provenance = manifest.path("provider").path("version").asText() + "|"
                    + manifest.path("provider").path("model").asText();
            if (!provenance.equals(run.getToolVersion())) throw new IOException("Backend provider provenance differs");
            String contractPath = "contracts/" + manifest.path("contract").path("id").asText() + "-"
                    + manifest.path("contract").path("version").asText() + ".yaml";
            if (!files.containsKey(contractPath)
                    || !hash(files.get(contractPath)).equals(manifest.path("contract").path("sha256").asText()))
                throw new IOException("Backend selected contract differs from manifest");
            String resource = new PackagedContractRegistry().resource(
                    manifest.path("contract").path("id").asText(),
                    manifest.path("contract").path("version").asText());
            if (resource == null) throw new IOException("Backend contract is no longer registered");
            try (var selected = BackendPackageIntegrity.class.getResourceAsStream(resource)) {
                if (selected == null || !java.util.Arrays.equals(files.get(contractPath), selected.readAllBytes()))
                    throw new IOException("Backend selected contract differs from registry");
            }
            Set<String> listed = new HashSet<>();
            JsonNode entries = manifest.path("files");
            if (!entries.isArray()) throw new IOException("Backend file list is missing");
            for (JsonNode entry : entries) {
                String path = entry.path("path").asText();
                GenerationArtifactStore.safeRelative(path);
                byte[] bytes = files.get(path);
                if (!listed.add(path) || bytes == null || path.equals("generation-manifest.json")
                        || bytes.length != entry.path("bytes").asInt(-1)
                        || !hash(bytes).equals(entry.path("sha256").asText())
                        || !Set.of("DETERMINISTIC", "MODEL").contains(entry.path("owner").asText()))
                    throw new IOException("Backend manifest file differs from storage");
            }
            Set<String> expected = new HashSet<>(files.keySet());
            expected.remove("generation-manifest.json");
            if (!listed.equals(expected)) throw new IOException("Backend manifest file set differs from storage");
        } catch (IOException | IllegalArgumentException ex) {
            throw new IllegalStateException("Backend package integrity check failed");
        }
    }

    static String hash(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (NoSuchAlgorithmException ex) { throw new IllegalStateException(ex); }
    }
}
