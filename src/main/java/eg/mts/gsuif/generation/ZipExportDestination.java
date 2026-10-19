package eg.mts.gsuif.generation;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public final class ZipExportDestination implements ExportDestination {
    @Override public String type() { return "ZIP"; }

    @Override public Path export(Path destination, Map<String, byte[]> artifacts) throws IOException {
        try (var output = Files.newOutputStream(destination, StandardOpenOption.CREATE_NEW)) {
            write(output, artifacts);
        }
        return destination;
    }

    static void write(OutputStream output, Map<String, byte[]> artifacts) throws IOException {
        try (var zip = new ZipOutputStream(output)) {
            for (var artifact : artifacts.entrySet()) {
                zip.putNextEntry(new ZipEntry(GenerationArtifactStore.safeRelative(artifact.getKey()).toString().replace('\\', '/')));
                zip.write(artifact.getValue());
                zip.closeEntry();
            }
        } finally {
            // Closing a failing ZIP stream can itself fail before closing its delegate.
            output.close();
        }
    }
}
