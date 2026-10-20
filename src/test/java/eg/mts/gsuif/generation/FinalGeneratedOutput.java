package eg.mts.gsuif.generation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Collections;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Frozen copy of a real complete-backend package produced by a previous generation run
 * (OpenRouter provider, not mocked). SCRUM-50 evidence is asserted against this final output,
 * not only against template-only fixtures.
 */
final class FinalGeneratedOutput {
    static final String RESOURCE = "/generation/final-output/stock-unit-backend-6b1f398c.zip";
    /** SHA-256 of the original ZIP the run produced; the test copy must stay byte-identical. */
    static final String ZIP_SHA256 = "6aebabb622cd9ed4afd9fb79e758a117e4dc8ed328a558b3bbd27a42ab192d18";
    static final String BASE_PACKAGE = "com.example.inventory";
    static final String ENTITY = "StockUnit";
    static final String JAVA_ROOT = "backend/src/main/java/com/example/inventory/";
    static final String CONTROLLER_PATH = JAVA_ROOT + "controller/StockUnitController.java";
    static final String SERVICE_IMPL_PATH = JAVA_ROOT + "service/StockUnitServiceImpl.java";
    static final String CONTRACT_PATH = "contracts/stock-unit-api-3.0.0.yaml";

    private final byte[] zip;
    private final Map<String, byte[]> files;
    private final JsonNode manifest;

    private FinalGeneratedOutput(byte[] zip, Map<String, byte[]> files, JsonNode manifest) {
        this.zip = zip;
        this.files = files;
        this.manifest = manifest;
    }

    static FinalGeneratedOutput load() throws IOException {
        byte[] zip;
        try (var input = FinalGeneratedOutput.class.getResourceAsStream(RESOURCE)) {
            if (input == null) throw new IOException("Missing final-output fixture " + RESOURCE);
            zip = input.readAllBytes();
        }
        Map<String, byte[]> files = new LinkedHashMap<>();
        try (var entries = new ZipInputStream(new ByteArrayInputStream(zip))) {
            for (ZipEntry entry; (entry = entries.getNextEntry()) != null; ) {
                if (entry.isDirectory()) continue;
                String name = entry.getName().replace('\\', '/');
                if (name.startsWith("/") || name.contains("../"))
                    throw new IOException("Unsafe ZIP entry " + name);
                if (files.put(name, entries.readAllBytes()) != null)
                    throw new IOException("Duplicate ZIP entry " + name);
            }
        }
        JsonNode manifest = new ObjectMapper().readTree(files.get("generation-manifest.json"));
        return new FinalGeneratedOutput(zip, Collections.unmodifiableMap(files), manifest);
    }

    String zipSha256() { return sha256(zip); }

    Map<String, byte[]> files() { return files; }

    JsonNode manifest() { return manifest; }

    byte[] bytes(String path) {
        byte[] bytes = files.get(path);
        if (bytes == null) throw new IllegalArgumentException("Not in final output: " + path);
        return bytes;
    }

    String text(String path) { return new String(bytes(path), StandardCharsets.UTF_8); }

    /** Operation IDs from the shipped OpenAPI contract, keyed by HTTP verb and path. */
    Map<String, String> contractOperations() throws IOException {
        JsonNode paths = new YAMLMapper().readTree(bytes(CONTRACT_PATH)).path("paths");
        Map<String, String> operations = new LinkedHashMap<>();
        for (var path : paths.properties())
            for (var verb : path.getValue().properties())
                if (verb.getValue().has("operationId"))
                    operations.put(verb.getKey().toUpperCase(java.util.Locale.ROOT) + " " + path.getKey(),
                            verb.getValue().path("operationId").asText());
        return operations;
    }

    /** CRUD controller shape derived from the shipped contract and package, not hard-coded names. */
    JavaStructuralGoldenRules.ControllerShape controllerShape() throws IOException {
        Map<String, String> operations = contractOperations();
        String create = null;
        Set<String> idMethods = new LinkedHashSet<>();
        for (var operation : operations.entrySet()) {
            if (operation.getKey().startsWith("POST ") && !operation.getKey().contains("{id}"))
                create = operation.getValue();
            if (operation.getKey().contains("{id}")) idMethods.add(operation.getValue());
        }
        return new JavaStructuralGoldenRules.ControllerShape(CONTROLLER_PATH, BASE_PACKAGE + ".controller",
                ENTITY + "Controller", ENTITY + "Service", ENTITY + "Api", create,
                Set.copyOf(operations.values()), Set.copyOf(idMethods), true);
    }

    void extractTo(Path directory) throws IOException {
        for (var entry : files.entrySet()) {
            Path destination = directory.resolve(entry.getKey()).normalize();
            if (!destination.startsWith(directory)) throw new IOException("Unsafe path " + entry.getKey());
            Files.createDirectories(destination.getParent());
            Files.write(destination, entry.getValue());
        }
    }

    static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
