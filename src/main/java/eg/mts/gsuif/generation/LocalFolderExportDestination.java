package eg.mts.gsuif.generation;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Map;

public final class LocalFolderExportDestination implements ExportDestination {
    @Override public String type() { return "LOCAL_FOLDER"; }

    @Override public Path export(Path destination, Map<String, byte[]> artifacts) throws IOException {
        Files.createDirectory(destination);
        for (var artifact : artifacts.entrySet()) {
            Path file = destination.resolve(GenerationArtifactStore.safeRelative(artifact.getKey()));
            Files.createDirectories(file.getParent());
            Files.write(file, artifact.getValue(), StandardOpenOption.CREATE_NEW);
        }
        return destination;
    }
}
