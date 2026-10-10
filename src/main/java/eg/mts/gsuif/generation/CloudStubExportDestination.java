package eg.mts.gsuif.generation;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;

/** Named extension point; cloud delivery needs a provider before it can be used. */
public final class CloudStubExportDestination implements ExportDestination {
    @Override public String type() { return "CLOUD_STUB"; }
    @Override public Path export(Path destination, Map<String, byte[]> artifacts) throws IOException {
        throw new IOException("Cloud export is not configured");
    }
}
