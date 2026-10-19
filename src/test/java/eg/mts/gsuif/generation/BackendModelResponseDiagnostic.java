package eg.mts.gsuif.generation;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;
import java.nio.file.attribute.PosixFileAttributeView;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.EnumSet;
import java.util.HexFormat;
import java.util.List;

/** Test-only, explicit capture of bounded model content immediately before validation. */
final class BackendModelResponseDiagnostic {
    static final String RESPONSE_FILE = "model-content.bin";
    static final String HASH_FILE = "model-content.sha256";
    static final String NORMALIZATION_FILE = "normalization-code.txt";
    private static final int MAX_CONTENT_BYTES = 65536;

    static final class Failed extends RuntimeException {
        Failed() { super("RESPONSE_DIAGNOSTIC_FAILED"); }
    }

    private final Path directory;
    private boolean captured;

    private BackendModelResponseDiagnostic(Path directory) { this.directory = directory; }

    static BackendModelResponseDiagnostic openLive(Path requested) {
        try {
            Path directory = externalLocation(requested, false);
            createPrivateDirectory(directory);
            return new BackendModelResponseDiagnostic(directory);
        } catch (IOException | RuntimeException ex) { throw new Failed(); }
    }

    // Mock tests use JUnit's disposable directory; only openLive is reachable from live transport.
    static BackendModelResponseDiagnostic openMock(Path directory) {
        try {
            createPrivateDirectory(directory);
            return new BackendModelResponseDiagnostic(directory);
        } catch (IOException | RuntimeException ex) { throw new Failed(); }
    }

    synchronized void capture(byte[] content) {
        if (captured || content == null || content.length > MAX_CONTENT_BYTES) throw new Failed();
        captured = true;
        try {
            Path response = createPrivateFile(directory.resolve(RESPONSE_FILE));
            Files.write(response, content, StandardOpenOption.WRITE);
            Path hash = createPrivateFile(directory.resolve(HASH_FILE));
            Files.writeString(hash, sha256(content) + "\n", StandardCharsets.US_ASCII, StandardOpenOption.WRITE);
            if (!java.util.Arrays.equals(content, readVerified(directory))) throw new Failed();
        } catch (IOException | RuntimeException ex) { throw new Failed(); }
    }

    synchronized void recordNormalization(OpenRouterBackendModelAdapter.Normalization code) {
        if (!captured || code == null) throw new Failed();
        try {
            Path file = createPrivateFile(directory.resolve(NORMALIZATION_FILE));
            Files.writeString(file, code.name() + "\n", StandardCharsets.US_ASCII,
                    StandardOpenOption.WRITE);
        } catch (IOException | RuntimeException ex) { throw new Failed(); }
    }

    static byte[] readPrivate(Path requested) {
        try { return readVerified(externalLocation(requested, true)); }
        catch (IOException | RuntimeException ex) { throw new Failed(); }
    }

    static byte[] readVerified(Path directory) throws IOException {
        Path response = directory.resolve(RESPONSE_FILE);
        Path hash = directory.resolve(HASH_FILE);
        if (Files.isSymbolicLink(directory) || Files.isSymbolicLink(response) || Files.isSymbolicLink(hash)
                || !Files.isRegularFile(response, LinkOption.NOFOLLOW_LINKS)
                || !Files.isRegularFile(hash, LinkOption.NOFOLLOW_LINKS)
                || Files.size(response) > MAX_CONTENT_BYTES || Files.size(hash) > 65)
            throw new Failed();
        requirePrivate(directory);
        requirePrivate(response);
        requirePrivate(hash);
        byte[] bytes = Files.readAllBytes(response);
        String expected = Files.readString(hash, StandardCharsets.US_ASCII).trim();
        if (!expected.matches("[0-9a-f]{64}") || !expected.equals(sha256(bytes))) throw new Failed();
        return bytes;
    }

    private static Path externalLocation(Path requested, boolean existing) throws IOException {
        if (requested == null) throw new Failed();
        Path absolute = requested.toAbsolutePath().normalize();
        Path parent = absolute.getParent();
        if (parent == null || !Files.isDirectory(parent)) throw new Failed();
        Path resolved = parent.toRealPath().resolve(absolute.getFileName());
        if (Files.exists(resolved, LinkOption.NOFOLLOW_LINKS) != existing
                || Files.isSymbolicLink(resolved)) throw new Failed();
        Path temporary = Path.of(System.getProperty("java.io.tmpdir")).toRealPath();
        if (resolved.startsWith(temporary)) throw new Failed();
        for (Path ancestor = resolved; ancestor != null; ancestor = ancestor.getParent()) {
            if (Files.exists(ancestor.resolve(".git"), LinkOption.NOFOLLOW_LINKS)
                    || "target".equalsIgnoreCase(String.valueOf(ancestor.getFileName()))) throw new Failed();
        }
        if (existing) {
            if (!Files.isDirectory(resolved, LinkOption.NOFOLLOW_LINKS)) throw new Failed();
            requirePrivate(resolved);
        }
        return resolved;
    }

    private static void createPrivateDirectory(Path directory) throws IOException {
        if (Files.getFileAttributeView(directory.getParent(), PosixFileAttributeView.class) != null)
            Files.createDirectory(directory, PosixFilePermissions.asFileAttribute(
                    PosixFilePermissions.fromString("rwx------")));
        else Files.createDirectory(directory);
        restrictToOwner(directory, true);
    }

    private static Path createPrivateFile(Path file) throws IOException {
        if (Files.getFileAttributeView(file.getParent(), PosixFileAttributeView.class) != null)
            Files.createFile(file, PosixFilePermissions.asFileAttribute(
                    PosixFilePermissions.fromString("rw-------")));
        else Files.createFile(file);
        restrictToOwner(file, false);
        return file;
    }

    private static void restrictToOwner(Path path, boolean directory) throws IOException {
        var posix = Files.getFileAttributeView(path, PosixFileAttributeView.class);
        if (posix != null) {
            posix.setPermissions(PosixFilePermissions.fromString(directory ? "rwx------" : "rw-------"));
            return;
        }
        var acl = Files.getFileAttributeView(path, AclFileAttributeView.class);
        if (acl == null) throw new Failed();
        acl.setAcl(List.of(AclEntry.newBuilder().setType(AclEntryType.ALLOW)
                .setPrincipal(Files.getOwner(path))
                .setPermissions(EnumSet.allOf(AclEntryPermission.class)).build()));
    }

    private static void requirePrivate(Path path) throws IOException {
        var posix = Files.getFileAttributeView(path, PosixFileAttributeView.class);
        if (posix != null) {
            String permissions = PosixFilePermissions.toString(posix.readAttributes().permissions());
            if (!permissions.substring(3).equals("------")) throw new Failed();
            return;
        }
        var acl = Files.getFileAttributeView(path, AclFileAttributeView.class);
        var owner = Files.getOwner(path);
        if (acl == null || acl.getAcl().stream().anyMatch(entry -> entry.type() == AclEntryType.ALLOW
                && !entry.principal().equals(owner))) throw new Failed();
    }

    private static String sha256(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (NoSuchAlgorithmException ex) { throw new Failed(); }
    }
}
