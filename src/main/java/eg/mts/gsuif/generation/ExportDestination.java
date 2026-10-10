package eg.mts.gsuif.generation;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;

/** Writes one verified run to a configured destination. */
public interface ExportDestination {
    String type();
    Path export(Path destination, Map<String, byte[]> artifacts) throws IOException;
}
