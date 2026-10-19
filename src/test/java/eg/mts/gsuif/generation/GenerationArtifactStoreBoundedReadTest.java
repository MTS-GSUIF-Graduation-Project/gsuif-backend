package eg.mts.gsuif.generation;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class GenerationArtifactStoreBoundedReadTest {
    @TempDir Path directory;

    @Test void boundedSnapshotRejectsTooManyBytesFilesAndTampering() throws Exception {
        var store = new GenerationArtifactStore(directory);
        UUID run = UUID.randomUUID();
        byte[] source = "class Safe {}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        var result = new GenerationResult(List.of(new GenerationResult.Artifact(
                "src/Safe.java", source, BackendPackageIntegrity.hash(source), "1", "1")), List.of());
        var staged = store.stage(run, "fingerprint", result);
        Files.move(staged.directory(), directory.resolve(run.toString()));

        assertThrows(IllegalStateException.class,
                () -> store.readVerified(run, "fingerprint", source.length - 1, 1));
        assertThrows(IllegalStateException.class,
                () -> store.readVerified(run, "fingerprint", source.length, 0));
        assertArrayEquals(source, store.readVerified(run, "fingerprint", source.length, 1)
                .artifacts().getFirst().bytes());
        assertArrayEquals(source, store.readVerified(run, "fingerprint", Long.MAX_VALUE, 1)
                .artifacts().getFirst().bytes());

        Files.writeString(directory.resolve(run.toString()).resolve("src/Safe.java"), "changed");
        assertThrows(IllegalStateException.class,
                () -> store.readVerified(run, "fingerprint", source.length, 1));
    }
}
