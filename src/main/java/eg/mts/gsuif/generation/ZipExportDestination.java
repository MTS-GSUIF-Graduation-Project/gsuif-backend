package eg.mts.gsuif.generation;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public final class ZipExportDestination implements ExportDestination {
    @Override public String type() { return "ZIP"; }

    @Override public Path export(Path destination, Map<String, byte[]> artifacts) throws IOException {
        try (var zip = new ZipOutputStream(Files.newOutputStream(destination, StandardOpenOption.CREATE_NEW))) {
            for (var artifact : artifacts.entrySet()) {
                zip.putNextEntry(new ZipEntry(GenerationArtifactStore.safeRelative(artifact.getKey()).toString().replace('\\', '/')));
                zip.write(artifact.getValue());
                zip.closeEntry();
            }
        }
        return destination;
    }
}
