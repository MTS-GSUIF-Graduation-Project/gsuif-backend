package eg.mts.gsuif.generation;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Set;

/** Grants the non-root compiler read access only to disposable source and library inputs. */
final class PosixCompileStaging {
    private PosixCompileStaging() { }

    static void makeContainerReadable(Path staged) throws IOException {
        if (!Files.getFileStore(staged).supportsFileAttributeView("posix")) return;
        Set<PosixFilePermission> root = PosixFilePermissions.fromString("rwx--x--x");
        Set<PosixFilePermission> directory = PosixFilePermissions.fromString("rwxr-xr-x");
        Set<PosixFilePermission> file = PosixFilePermissions.fromString("rw-r--r--");
        try (var paths = Files.walk(staged)) {
            for (Path path : paths.toList()) {
                if (path.equals(staged)) continue;
                if (path.startsWith(staged.resolve("docker-config"))) continue;
                Files.setPosixFilePermissions(path, Files.isDirectory(path) ? directory : file);
            }
        }
        Path dockerConfig = staged.resolve("docker-config");
        if (Files.isDirectory(dockerConfig))
            Files.setPosixFilePermissions(dockerConfig, PosixFilePermissions.fromString("rwx------"));
        Files.setPosixFilePermissions(staged, root);
    }
}
