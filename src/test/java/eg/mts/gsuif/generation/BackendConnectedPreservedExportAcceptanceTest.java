package eg.mts.gsuif.generation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Set;
import java.util.zip.ZipFile;

import static org.junit.jupiter.api.Assertions.*;

/** Continues only the preserved public export; no Spring context, gateway or generation call. */
final class BackendConnectedPreservedExportAcceptanceTest {
    private static final String ZIP_SHA256 = "c331cbf9c82f9d46579e249e97f4e9ad0e1821f5ef358e6374be5a4de58b486f";
    private static final String SOURCE_SHA256 = "839ebda02170a50c600a58c44db5324f27dfd68b7d361583e74eede201533786";
    private static final String MANIFEST_SHA256 = "205f7aefffb2523d2ca1fcec9c5c4000b26c56a8cbf9d401c83fe6137da9950b";
    private static final String RUN_ID = "e2d2b1e6-e235-4d0d-a678-8f9451e58abc";
    private static final String SOURCE_SNAPSHOT = "6f2b5c11f008224e905adc218fad697a87da03c1bc88714b20e7d632709cf0b8";
    private static final String SOURCE_PATH = "backend/src/main/java/com/example/inventory/service/StockUnitServiceImpl.java";
    private static final String CONTRACT_PATH = "contracts/stock-unit-api-3.0.0.yaml";

    @Test
    @EnabledIfSystemProperty(named = "gsuif.test.preserved-export-continuation", matches = "true")
    void preservedPublicExportBuildsAndRunsWithoutProvider() throws Exception {
        assertTrue(System.getenv("OPENROUTER_API_KEY") == null
                || System.getenv("OPENROUTER_API_KEY").isBlank(), "PROVIDER_CREDENTIAL_PRESENT");
        Path workspace = Path.of("").toAbsolutePath().normalize();
        Path original = Path.of(required("gsuif.test.preserved-export-original-dir"))
                .toAbsolutePath().normalize();
        Path evidence = Path.of(required("gsuif.test.preserved-export-evidence-dir"))
                .toAbsolutePath().normalize();
        assertTrue(Files.isDirectory(original), "ORIGINAL_EVIDENCE_MISSING");
        assertTrue(evidence.startsWith(workspace.resolve("review-packages")), "EVIDENCE_OUTSIDE_REVIEW_FOLDER");
        assertFalse(evidence.startsWith(workspace.resolve("target")), "EVIDENCE_IN_BUILD_OUTPUT");
        assertTrue(Files.isDirectory(evidence.getParent()), "EVIDENCE_PARENT_MISSING");
        assertFalse(Files.exists(evidence), "CONTINUATION_EVIDENCE_ALREADY_EXISTS");
        Files.createDirectory(evidence);

        String stage = "PRESERVED_ZIP";
        String failureCode = "NONE";
        String scratchCleanup = "not-created";
        Path scratch = null;
        try {
            Path zip = original.resolve("exact-public-export.zip");
            String originalResult = Files.readString(original.resolve("connected-result.txt"));
            assertTrue(originalResult.contains("runId=" + RUN_ID + "\n"), "ORIGINAL_RUN_ID_CHANGED");
            assertTrue(originalResult.contains("sourceSnapshotSha256=" + SOURCE_SNAPSHOT + "\n"),
                    "ORIGINAL_SOURCE_SNAPSHOT_CHANGED");
            assertEquals(ZIP_SHA256, sha256(Files.readAllBytes(zip)), "PRESERVED_ZIP_CHANGED");
            assertEquals(ZIP_SHA256, Files.readString(original.resolve("exact-public-export.sha256")).trim());
            byte[] originalSource = Files.readAllBytes(original.resolve(SOURCE_PATH));
            assertEquals(SOURCE_SHA256, sha256(originalSource), "PRESERVED_SOURCE_CHANGED");
            assertEquals(SOURCE_SHA256, Files.readString(original.resolve("accepted-source.sha256")).trim());

            Path parent = workspace.resolve("target/connected-preserved-export-temp");
            Files.createDirectories(parent);
            assertTrue(Files.isDirectory(parent), "EXTRACTION_PARENT_MISSING");
            scratch = Files.createTempDirectory(parent, "attempt-");
            assertTrue(Files.isDirectory(scratch), "EXTRACTION_SCRATCH_MISSING");
            Path extracted = Files.createDirectory(scratch.resolve("exact-public-export"));
            Set<String> archivePaths = extract(zip, extracted);

            stage = "EXPORTED_INTEGRITY";
            byte[] manifestBytes = Files.readAllBytes(extracted.resolve("generation-manifest.json"));
            assertEquals(MANIFEST_SHA256, sha256(manifestBytes), "PRESERVED_MANIFEST_CHANGED");
            JsonNode manifest = new ObjectMapper().readTree(manifestBytes);
            assertEquals("BACKEND", manifest.path("outputMode").asText());
            assertEquals("stock-unit-api", manifest.path("contract").path("id").asText());
            assertEquals("3.0.0", manifest.path("contract").path("version").asText());
            assertEquals("openrouter", manifest.path("provider").path("id").asText());
            assertEquals("qwen/qwen3-coder", manifest.path("provider").path("model").asText());
            assertFalse(manifest.path("provider").path("mocked").asBoolean(true));
            verifyExactFileSet(extracted, archivePaths, manifest);
            byte[] contract = Files.readAllBytes(extracted.resolve(CONTRACT_PATH));
            assertEquals(manifest.path("contract").path("sha256").asText(), sha256(contract));
            try (InputStream selected = getClass().getResourceAsStream("/openapi/stock-unit-api-paged.yaml")) {
                assertNotNull(selected, "SELECTED_CONTRACT_RESOURCE_MISSING");
                assertArrayEquals(selected.readAllBytes(), contract, "SELECTED_CONTRACT_BYTES_CHANGED");
            }
            byte[] source = Files.readAllBytes(extracted.resolve(SOURCE_PATH));
            assertArrayEquals(originalSource, source, "ACCEPTED_SOURCE_CHANGED_IN_EXPORT");
            assertEquals(SOURCE_SHA256, sha256(source));
            Files.createDirectories(evidence.resolve(SOURCE_PATH).getParent());
            Files.write(evidence.resolve(SOURCE_PATH), source, StandardOpenOption.CREATE_NEW);
            Files.writeString(evidence.resolve("accepted-source.sha256"), SOURCE_SHA256 + "\n",
                    StandardCharsets.US_ASCII, StandardOpenOption.CREATE_NEW);
            Files.writeString(evidence.resolve("preserved-export.sha256"), ZIP_SHA256 + "\n",
                    StandardCharsets.US_ASCII, StandardOpenOption.CREATE_NEW);

            JsonNode provider = manifest.path("provider");
            var provenance = new CompleteBackendPackageAssembler.Provenance(
                    provider.path("id").asText(), provider.path("version").asText(),
                    provider.path("model").asText(), false);
            stage = "ISOLATED_RUNTIME";
            new IsolatedStockUnitContainerAcceptance(scratch).verifyPreservedExport(
                    extracted, SOURCE_PATH, source, provenance, evidence, true);
            stage = "COMPLETE";
        } catch (Exception | AssertionError failure) {
            failureCode = failure instanceof AssertionError ? "ASSERTION"
                    : failure instanceof java.io.IOException ? "FILESYSTEM" : "UNEXPECTED";
            throw failure;
        } finally {
            if (scratch != null) {
                try (var paths = Files.walk(scratch)) {
                    for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList())
                        Files.deleteIfExists(path);
                    scratchCleanup = "confirmed";
                } catch (java.io.IOException failure) {
                    scratchCleanup = "unconfirmed";
                }
            }
            Files.writeString(evidence.resolve("continuation-result.txt"),
                    "originalGeneration=SUCCESS\noriginalRunId=" + RUN_ID + "\nstage=" + stage
                            + "\nfailureCode=" + failureCode
                            + "\nzipSha256=" + ZIP_SHA256 + "\nsourceSha256=" + SOURCE_SHA256
                            + "\nscratchCleanup=" + scratchCleanup + "\n",
                    StandardCharsets.US_ASCII, StandardOpenOption.CREATE_NEW);
        }
        assertEquals("confirmed", scratchCleanup, "SCRATCH_CLEANUP_UNCONFIRMED");
    }

    private static Set<String> extract(Path zip, Path extracted) throws Exception {
        Set<String> names = new HashSet<>();
        Set<String> aliases = new HashSet<>();
        try (ZipFile archive = new ZipFile(zip.toFile())) {
            var entries = archive.entries();
            while (entries.hasMoreElements()) {
                var entry = entries.nextElement();
                if (entry.isDirectory()) continue;
                Path relative = GenerationArtifactStore.safeRelative(entry.getName());
                String path = relative.toString().replace('\\', '/');
                assertEquals(entry.getName(), path, "NONCANONICAL_EXPORT_PATH");
                assertTrue(names.add(path), "DUPLICATE_EXPORT_PATH");
                assertTrue(aliases.add(path.toLowerCase(Locale.ROOT)), "CASE_ALIAS_EXPORT_PATH");
                Path target = extracted.resolve(relative).normalize();
                assertTrue(target.startsWith(extracted), "UNSAFE_EXPORT_PATH");
                Files.createDirectories(target.getParent());
                try (InputStream stream = archive.getInputStream(entry)) {
                    Files.write(target, stream.readAllBytes(), StandardOpenOption.CREATE_NEW);
                }
            }
        }
        assertTrue(names.contains("generation-manifest.json"), "MANIFEST_MISSING");
        return names;
    }

    private static void verifyExactFileSet(Path extracted, Set<String> archivePaths, JsonNode manifest)
            throws Exception {
        assertTrue(manifest.path("files").isArray(), "MANIFEST_FILES_MISSING");
        Set<String> listed = new HashSet<>();
        for (JsonNode entry : manifest.path("files")) {
            String path = entry.path("path").asText();
            assertTrue(listed.add(path), "DUPLICATE_MANIFEST_PATH");
            Path file = extracted.resolve(GenerationArtifactStore.safeRelative(path));
            assertEquals(entry.path("sha256").asText(), sha256(Files.readAllBytes(file)),
                    "MANIFEST_FILE_HASH_MISMATCH: " + path);
        }
        listed.add("generation-manifest.json");
        assertEquals(listed, archivePaths, "EXPORTED_FILE_SET_MISMATCH");
        try (var files = Files.walk(extracted)) {
            Set<String> actual = new HashSet<>(files.filter(Files::isRegularFile)
                    .map(extracted::relativize).map(path -> path.toString().replace('\\', '/')).toList());
            assertEquals(archivePaths, actual, "EXTRACTED_FILE_SET_MISMATCH");
        }
        assertEquals(SOURCE_SHA256, manifestSourceHash(manifest), "MANIFEST_SOURCE_HASH_MISMATCH");
    }

    private static String manifestSourceHash(JsonNode manifest) {
        for (JsonNode entry : manifest.path("files"))
            if (SOURCE_PATH.equals(entry.path("path").asText())) {
                assertEquals("MODEL", entry.path("owner").asText());
                return entry.path("sha256").asText();
            }
        fail("MODEL_SOURCE_MISSING_FROM_MANIFEST");
        return "";
    }

    private static String sha256(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    private static String required(String name) {
        String value = System.getProperty(name);
        if (value == null || value.isBlank()) throw new IllegalArgumentException("MISSING_" + name);
        return value;
    }
}
