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
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Map;
import java.util.TreeMap;
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

    /** A private, complete candidate. Publication is permitted only while completing its run. */
    public record Staged(UUID runId, String fingerprint, Path directory,
                         String privateManifestSha256, Map<String, String> fileHashes) {
        public Staged { fileHashes = Map.copyOf(fileHashes); }
    }

    public Staged stage(UUID runId, String fingerprint, GenerationResult result) {
        Objects.requireNonNull(runId);
        Objects.requireNonNull(fingerprint);
        Objects.requireNonNull(result);
        Path directory = root.resolve(".pending-" + UUID.randomUUID());
        try {
            Files.createDirectories(root);
            if (Files.isSymbolicLink(root)) throw new IOException("Unsafe artifact root");
            Files.createDirectory(directory);
            if (Files.getFileStore(directory).supportsFileAttributeView("posix"))
                Files.setPosixFilePermissions(directory,
                        java.nio.file.attribute.PosixFilePermissions.fromString("rwx------"));
            writeResult(directory, fingerprint, result, true);
            Map<String, String> hashes = new TreeMap<>();
            result.artifacts().forEach(artifact -> hashes.put(artifact.relativePath(), artifact.sha256()));
            return new Staged(runId, fingerprint, directory, digest(Files.readAllBytes(directory.resolve(MANIFEST))), hashes);
        } catch (IOException | RuntimeException ex) {
            deleteTree(directory);
            throw new IllegalStateException("Could not stage generated artifacts", ex);
        }
    }

    /** Atomic same-filesystem publication precedes DB commit; rollback removes the unreferenced directory. */
    public void publish(Staged staged) {
        requireTransaction();
        Objects.requireNonNull(staged);
        Path source = staged.directory();
        Path destination = root.resolve(staged.runId().toString());
        if (!source.getParent().equals(root) || !source.getFileName().toString().startsWith(".pending-")
                || Files.isSymbolicLink(source) || !Files.isDirectory(source, LinkOption.NOFOLLOW_LINKS))
            throw new IllegalStateException("Staged artifacts are missing or unsafe");
        boolean moved = false;
        try {
            verifyStaged(staged);
            if (Files.exists(destination, LinkOption.NOFOLLOW_LINKS))
                throw new IOException("Run artifact directory already exists");
            Files.move(source, destination, StandardCopyOption.ATOMIC_MOVE);
            moved = true;
            verifyPublished(destination, staged);
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCompletion(int status) {
                    if (status != STATUS_COMMITTED) deleteTree(destination);
                }
            });
        } catch (AtomicMoveNotSupportedException ex) {
            if (moved) deleteTree(destination);
            throw new IllegalStateException("Atomic artifact publication is unavailable", ex);
        } catch (IOException ex) {
            if (moved) deleteTree(destination);
            throw new IllegalStateException("Could not publish generated artifacts", ex);
        } catch (RuntimeException ex) {
            if (moved) deleteTree(destination);
            throw ex;
        }
    }

    private void verifyStaged(Staged staged) throws IOException {
        if (!digest(Files.readAllBytes(checkedStoredFile(staged.directory(), staged.directory().resolve(MANIFEST))))
                .equals(staged.privateManifestSha256())) throw new IOException("Staged manifest changed");
        Manifest manifest = mapper.readValue(staged.directory().resolve(MANIFEST).toFile(), Manifest.class);
        if (manifest.version() != 1 || !staged.fingerprint().equals(manifest.inputFingerprint())
                || manifest.artifacts().size() != staged.fileHashes().size())
            throw new IOException("Staged artifact manifest differs");
        Set<String> seen = new HashSet<>();
        for (Entry entry : manifest.artifacts()) {
            if (!seen.add(entry.relativePath()) || !Objects.equals(staged.fileHashes().get(entry.relativePath()), entry.sha256()))
                throw new IOException("Staged artifact set differs");
            Path file = staged.directory().resolve(safeRelative(entry.relativePath()));
            String actual = digest(Files.readAllBytes(checkedStoredFile(staged.directory(), file)));
            if (!actual.equals(entry.sha256()) || !actual.equals(entry.contentSha256()))
                throw new IOException("Staged artifact changed");
        }
    }

    private void verifyPublished(Path destination, Staged staged) throws IOException {
        if (!digest(Files.readAllBytes(checkedStoredFile(destination, destination.resolve(MANIFEST))))
                .equals(staged.privateManifestSha256())) throw new IOException("Published manifest changed");
        for (var entry : staged.fileHashes().entrySet()) {
            Path file = destination.resolve(safeRelative(entry.getKey()));
            if (!digest(Files.readAllBytes(checkedStoredFile(destination, file))).equals(entry.getValue()))
                throw new IOException("Published artifact changed");
        }
    }

    public void discard(Staged staged) {
        if (staged != null && staged.directory().getParent().equals(root)) deleteTree(staged.directory());
    }

    public void save(UUID runId, String inputFingerprint, GenerationResult result) {
        requireTransaction();
        Path destination = root.resolve(runId.toString());
        boolean created = false;
        try {
            Files.createDirectories(root);
            if (Files.isSymbolicLink(root)) throw new IOException("Artifact root is a symbolic link");
            Files.createDirectory(destination);
            created = true;
            writeResult(destination, inputFingerprint, result, false);
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

    private static void requireTransaction() {
        if (!TransactionSynchronizationManager.isActualTransactionActive()
                || !TransactionSynchronizationManager.isSynchronizationActive())
            throw new IllegalStateException("Artifact storage requires the generation-run transaction");
    }

    private void writeResult(Path destination, String inputFingerprint, GenerationResult result,
            boolean requireContentHash) throws IOException {
        Set<String> paths = new HashSet<>();
        List<Entry> entries = new ArrayList<>();
        for (GenerationResult.Artifact artifact : result.artifacts()) {
            Path relative = safeRelative(artifact.relativePath());
            if (!paths.add(relative.toString())) throw new IOException("Duplicate artifact path");
            byte[] bytes = artifact.bytes();
            if (requireContentHash && !digest(bytes).equals(artifact.sha256()))
                throw new IOException("Artifact hash differs from content");
            Path file = destination.resolve(relative);
            Files.createDirectories(file.getParent());
            Files.write(file, bytes, StandardOpenOption.CREATE_NEW);
            entries.add(new Entry(artifact.relativePath(), artifact.sha256(), digest(bytes),
                    artifact.templateVersion(), artifact.catalogVersion()));
        }
        mapper.writeValue(destination.resolve(MANIFEST).toFile(),
                new Manifest(1, inputFingerprint, entries, result.diagnostics(), result.consumerBuild()));
    }

    public GenerationResult read(UUID runId) { return readVerified(runId, null); }

    public GenerationResult readVerified(UUID runId, String expectedFingerprint) {
        return readVerified(runId, expectedFingerprint, Long.MAX_VALUE, Integer.MAX_VALUE);
    }

    /** Read a download-sized snapshot before response headers are sent. */
    public GenerationResult readVerified(UUID runId, String expectedFingerprint,
            long maxArtifactBytes, int maxFiles) {
        Path directory = runDirectory(runId);
        try {
            Path manifestPath = checkedStoredFile(directory, directory.resolve(MANIFEST));
            boolean bounded = maxArtifactBytes != Long.MAX_VALUE || maxFiles != Integer.MAX_VALUE;
            Manifest manifest;
            if (bounded) {
                byte[] manifestBytes;
                try (var input = Files.newInputStream(manifestPath)) {
                    manifestBytes = input.readNBytes(1024 * 1024 + 1);
                }
                if (manifestBytes.length > 1024 * 1024)
                    throw new IOException("Stored artifact manifest exceeds download limit");
                manifest = mapper.readValue(manifestBytes, Manifest.class);
            } else manifest = mapper.readValue(manifestPath.toFile(), Manifest.class);
            if (manifest.version() != 1) throw new IOException("Unsupported artifact manifest version");
            if (expectedFingerprint != null && !expectedFingerprint.equals(manifest.inputFingerprint()))
                throw new IOException("Stored attempt fingerprint differs from run");
            if (manifest.artifacts().size() > maxFiles)
                throw new IOException("Stored artifact count exceeds download limit");
            List<GenerationResult.Artifact> artifacts = new ArrayList<>();
            long remaining = maxArtifactBytes;
            for (Entry entry : manifest.artifacts()) {
                Path file = directory.resolve(safeRelative(entry.relativePath()));
                Path checked = checkedStoredFile(directory, file);
                if (Files.size(checked) > remaining)
                    throw new IOException("Stored artifacts exceed download limit");
                byte[] bytes;
                if (!bounded) bytes = Files.readAllBytes(checked);
                else try (var input = Files.newInputStream(checked)) {
                    bytes = input.readNBytes((int) Math.min(remaining + 1, (long) Integer.MAX_VALUE));
                }
                if (bytes.length > remaining)
                    throw new IOException("Stored artifacts exceed download limit");
                remaining -= bytes.length;
                if (!digest(bytes).equals(entry.contentSha256())
                        || expectedFingerprint != null && !digest(bytes).equals(entry.sha256()))
                    throw new IOException("Stored artifact content changed");
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
