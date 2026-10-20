package eg.mts.gsuif.generation;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.JarFile;

import static org.junit.jupiter.api.Assertions.*;

/**
 * SCRUM-50 Editable Code Test on the real final output (explicit opt-in, like
 * {@link CompleteBackendStandaloneBuildTest}): open a generated file, change one method by hand,
 * and build the extracted backend with Maven while no GSUIF or generator code is present.
 */
class FinalOutputEditableBuildTest {
    private static final String BEFORE = "\"%%\" + label + \"%%\"";
    private static final String AFTER = "\"%\" + label + \"%\"";

    @TempDir Path scratch;

    @Test
    void handEditedRealOutputBuildsWithoutTheGenerator() throws Exception {
        CompleteBackendStandaloneBuildTest.requireOptIn();
        var output = FinalGeneratedOutput.load();
        assertEquals(FinalGeneratedOutput.ZIP_SHA256, output.zipSha256());
        Path extracted = Files.createDirectory(scratch.resolve("final-output"));
        output.extractTo(extracted);

        // A maintainer simplifies the LIKE pattern in list(). In SQL LIKE "%%" matches exactly what "%"
        // matches, so this is a behavior-preserving, one-line hand edit (not a bug fix); nothing else changes.
        Path service = extracted.resolve(FinalGeneratedOutput.SERVICE_IMPL_PATH);
        String original = Files.readString(service);
        String edited = CompleteBackendStandaloneBuildTest.editOneMethod(original, "list", BEFORE, AFTER);
        Files.writeString(service, edited);
        assertEquals(1, changedLines(original, edited), "Exactly one line in one method is hand-edited");
        for (var file : output.files().entrySet())
            if (!file.getKey().equals(FinalGeneratedOutput.SERVICE_IMPL_PATH))
                assertArrayEquals(file.getValue(), Files.readAllBytes(extracted.resolve(file.getKey())), file.getKey());

        Path backend = extracted.resolve("backend");
        CompleteBackendStandaloneBuildTest.assertGeneratorRuntimeAbsent(backend);
        Path jar = CompleteBackendStandaloneBuildTest.packageExecutableJar(backend,
                scratch.resolve("final-output-maven-build.log"), "final-output-hand-edited",
                Boolean.getBoolean("gsuif.test.maven-offline"));

        try (JarFile built = new JarFile(jar.toFile())) {
            String classes = "BOOT-INF/classes/com/example/inventory/";
            assertNull(built.getEntry("BOOT-INF/classes/eg/mts/gsuif/generation/GenerationResult.class"));
            assertTrue(built.stream().noneMatch(entry -> entry.getName().startsWith("BOOT-INF/classes/eg/mts/gsuif/")),
                    "No GSUIF class may be packaged");
            assertTrue(built.stream().noneMatch(entry -> entry.getName().matches(
                    "BOOT-INF/lib/(freemarker|javaparser|jmix|openapi-generator).*")), "No generator library may be packaged");
            byte[] compiled = built.getInputStream(built.getEntry(classes + "service/StockUnitServiceImpl.class")).readAllBytes();
            String bytecode = new String(compiled, StandardCharsets.ISO_8859_1);
            // javac compiles string concatenation to an invokedynamic recipe; \u0001 marks the label argument.
            assertTrue(bytecode.contains("%\u0001%"), "Hand-edited pattern must be in compiled bytecode");
            assertFalse(bytecode.contains("%%\u0001%%"), "Original generated pattern must be gone");
            assertEquals("com.example.inventory.Application",
                    built.getManifest().getMainAttributes().getValue("Start-Class"));
        }
    }

    private static long changedLines(String before, String after) {
        var left = before.lines().toList();
        var right = after.lines().toList();
        assertEquals(left.size(), right.size());
        long changed = 0;
        for (int i = 0; i < left.size(); i++) if (!left.get(i).equals(right.get(i))) changed++;
        return changed;
    }
}
