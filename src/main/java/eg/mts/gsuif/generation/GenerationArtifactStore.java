package eg.mts.gsuif.generation;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Durable, run-isolated source files. A run ID and relative path are the stable storage reference. */
@Component
public class GenerationArtifactStore {
    private static final String MANIFEST = ".generation-result.json";
    private final Path root;
    private final ObjectMapper mapper = new ObjectMapper();

    @Autowired
    public GenerationArtifactStore(@Value("${gsuif.generation.artifact-root:var/generated-artifacts}") String root) {
        this(Path.of(root));
    }

    public GenerationArtifactStore(Path root) {
        this.root = root.toAbsolutePath().normalize();
    }

    public void save(UUID runId, String inputFingerprint, GenerationResult result) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()
                || !TransactionSynchronizationManager.isSynchronizationActive())
            throw new IllegalStateException("Artifact storage requires the generation-run transaction");
        Path destination = root.resolve(runId.toString());
        boolean created = false;
        try {
            Files.createDirectories(root);
            if (Files.isSymbolicLink(root)) throw new IOException("Artifact root is a symbolic link");
            Files.createDirectory(destination);
            created = true;
            Set<String> paths = new HashSet<>();
            List<Entry> entries = new ArrayList<>();
            for (GenerationResult.Artifact artifact : result.artifacts()) {
                Path relative = safeRelative(artifact.relativePath());
                if (!paths.add(relative.toString())) throw new IOException("Duplicate artifact path");
                byte[] bytes = artifact.bytes();
                Path file = destination.resolve(relative);
                Files.createDirectories(file.getParent());
                Files.write(file, bytes, StandardOpenOption.CREATE_NEW);
                entries.add(new Entry(artifact.relativePath(), artifact.sha256(), digest(bytes),
                        artifact.templateVersion(), artifact.catalogVersion()));
            }
            mapper.writeValue(destination.resolve(MANIFEST).toFile(),
                    new Manifest(1, inputFingerprint, entries, result.diagnostics(), result.consumerBuild()));
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCompletion(int status) {
                    if (status != STATUS_COMMITTED) deleteTree(destination);
                }
            });
        } catch (IOException | RuntimeException ex) {
            if (created) deleteTree(destination);
            throw new IllegalStateException("Could not persist generated artifacts for run " + runId, ex);
        }
    }

    public GenerationResult read(UUID runId) {
        Path directory = runDirectory(runId);
        try {
            Path manifestPath = checkedStoredFile(directory, directory.resolve(MANIFEST));
            Manifest manifest = mapper.readValue(manifestPath.toFile(), Manifest.class);
            if (manifest.version() != 1) throw new IOException("Unsupported artifact manifest version");
            List<GenerationResult.Artifact> artifacts = new ArrayList<>();
            for (Entry entry : manifest.artifacts()) {
                Path file = directory.resolve(safeRelative(entry.relativePath()));
                byte[] bytes = Files.readAllBytes(checkedStoredFile(directory, file));
                if (!digest(bytes).equals(entry.contentSha256())) throw new IOException("Stored artifact content changed");
                artifacts.add(new GenerationResult.Artifact(entry.relativePath(), bytes, entry.sha256(),
                        entry.templateVersion(), entry.catalogVersion()));
            }
            return new GenerationResult(artifacts, manifest.diagnostics(), manifest.consumerBuild());
        } catch (IOException ex) {
            throw new IllegalStateException("Could not read generated artifacts for run " + runId, ex);
        }
    }

    public byte[] read(UUID runId, String relativePath) {
        Path directory = runDirectory(runId);
        Path file = directory.resolve(safeRelative(relativePath));
        try {
            return Files.readAllBytes(checkedStoredFile(directory, file));
        } catch (IOException ex) {
            throw new IllegalStateException("Could not read generated artifact", ex);
        }
    }

    private Path runDirectory(UUID runId) {
        Path directory = root.resolve(runId.toString());
        if (Files.isSymbolicLink(root) || Files.isSymbolicLink(directory)
                || !Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS))
            throw new IllegalStateException("Run artifact storage is missing or unsafe");
        return directory;
    }

    private static Path checkedStoredFile(Path directory, Path file) throws IOException {
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)
                || !file.toRealPath().startsWith(directory.toRealPath()))
            throw new IOException("Stored artifact is missing or outside its run directory");
        return file;
    }

    static Path safeRelative(String value) {
        if (value == null || value.isBlank() || value.length() > 1024 || value.indexOf('\\') >= 0
                || value.indexOf(':') >= 0 || value.startsWith("/"))
            throw new IllegalArgumentException("Unsafe artifact path");
        for (String part : value.split("/", -1))
            if (!part.matches("[A-Za-z0-9._-]+") || part.equals(".") || part.equals(".."))
                throw new IllegalArgumentException("Unsafe artifact path");
        Path path = Path.of(value);
        if (path.isAbsolute() || !path.normalize().equals(path) || path.toString().equals(MANIFEST))
            throw new IllegalArgumentException("Unsafe artifact path");
        return path;
    }

    private static String digest(byte[] bytes) {
        try {
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException ex) { throw new IllegalStateException(ex); }
    }

    private static void deleteTree(Path directory) {
        try (var files = Files.walk(directory)) {
            for (Path file : files.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(file);
        } catch (IOException ignored) {
            // A failed database transaction can leave an orphan, but never a committed reference.
        }
    }

    public record Entry(String relativePath, String sha256, String contentSha256,
            String templateVersion, String catalogVersion) { }
    public record Manifest(int version, String inputFingerprint, List<Entry> artifacts,
            List<String> diagnostics, ConsumerBuildContract consumerBuild) { }
}
