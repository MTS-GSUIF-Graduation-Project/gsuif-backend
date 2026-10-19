package eg.mts.gsuif.generation;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/** Immutable provenance for the generated POM's compile dependencies, never the host classpath. */
final class BackendCompileClasspath {
    enum Reason { INVALID_MANIFEST, MISSING_ARTIFACT, CHANGED_ARTIFACT, TIMEOUT }
    static final class Failed extends RuntimeException {
        private final Reason reason;
        Failed(Reason reason) { super(reason.name()); this.reason = reason; }
        Reason reason() { return reason; }
    }
    record Artifact(String groupId, String artifactId, String version, String classifier,
                    Path file, String sha256) {
        Artifact {
            if (groupId == null || artifactId == null || version == null || classifier == null
                    || file == null || sha256 == null || !sha256.matches("[0-9a-f]{64}"))
                throw new Failed(Reason.INVALID_MANIFEST);
            file = file.toAbsolutePath().normalize();
        }
        String coordinates() {
            return groupId + ":" + artifactId + ":" + version
                    + (classifier.isEmpty() ? "" : ":" + classifier);
        }
    }

    private final String pomSha256;
    private final List<Artifact> artifacts;

    BackendCompileClasspath(String pomSha256, List<Artifact> artifacts) {
        if (pomSha256 == null || !pomSha256.matches("[0-9a-f]{64}") || artifacts == null
                || artifacts.size() > 300) throw new Failed(Reason.INVALID_MANIFEST);
        Set<String> names = new TreeSet<>();
        for (Artifact artifact : artifacts)
            if (artifact == null || !names.add(artifact.coordinates())) throw new Failed(Reason.INVALID_MANIFEST);
        this.pomSha256 = pomSha256;
        this.artifacts = List.copyOf(artifacts.stream()
                .sorted(Comparator.comparing(Artifact::coordinates)).toList());
    }

    static BackendCompileClasspath fromRepository(byte[] pom, Path repository, List<Path> resolved) throws IOException {
        return fromRepository(pom, repository, resolved, Long.MAX_VALUE);
    }

    static BackendCompileClasspath fromRepository(byte[] pom, Path repository, List<Path> resolved,
                                                   long deadlineNanos) throws IOException {
        if (pom == null || repository == null || resolved == null || resolved.isEmpty()
                || resolved.size() > 300) throw new Failed(Reason.INVALID_MANIFEST);
        Path root = repository.toRealPath();
        List<Artifact> artifacts = new ArrayList<>();
        long total = 0;
        for (Path entry : resolved) {
            checkDeadline(deadlineNanos);
            if (!Files.isRegularFile(entry)) throw new Failed(Reason.MISSING_ARTIFACT);
            Path file = entry.toRealPath();
            if (!file.startsWith(root)) throw new Failed(Reason.INVALID_MANIFEST);
            Path relative = root.relativize(file);
            if (relative.getNameCount() < 4) throw new Failed(Reason.INVALID_MANIFEST);
            String artifactId = relative.getName(relative.getNameCount() - 3).toString();
            String version = relative.getName(relative.getNameCount() - 2).toString();
            String filename = relative.getFileName().toString();
            String prefix = artifactId + "-" + version;
            if (!filename.endsWith(".jar") || !filename.startsWith(prefix)
                    || !(filename.equals(prefix + ".jar") || filename.startsWith(prefix + "-")))
                throw new Failed(Reason.INVALID_MANIFEST);
            String classifier = filename.equals(prefix + ".jar") ? ""
                    : filename.substring(prefix.length() + 1, filename.length() - 4);
            StringBuilder group = new StringBuilder();
            for (int i = 0; i < relative.getNameCount() - 3; i++) {
                if (i > 0) group.append('.');
                group.append(relative.getName(i));
            }
            if (!group.toString().matches("[A-Za-z0-9_.-]+")
                    || !artifactId.matches("[A-Za-z0-9_.-]+")
                    || !version.matches("[A-Za-z0-9_.-]+")
                    || !classifier.matches("[A-Za-z0-9_.-]*")) throw new Failed(Reason.INVALID_MANIFEST);
            total = Math.addExact(total, Files.size(file));
            if (total > 512L * 1024 * 1024) throw new Failed(Reason.INVALID_MANIFEST);
            artifacts.add(new Artifact(group.toString(), artifactId, version, classifier, file,
                    hash(file, deadlineNanos)));
        }
        checkDeadline(deadlineNanos);
        return new BackendCompileClasspath(hash(pom), artifacts);
    }

    String pomSha256() { return pomSha256; }
    List<Artifact> artifacts() { return artifacts; }

    List<String> stage(byte[] pom, Path libraryDirectory) throws IOException {
        if (pom == null || !pomSha256.equals(hash(pom))) throw new Failed(Reason.INVALID_MANIFEST);
        Files.createDirectories(libraryDirectory);
        List<String> staged = new ArrayList<>();
        for (Artifact artifact : artifacts) {
            if (!Files.isRegularFile(artifact.file())) throw new Failed(Reason.MISSING_ARTIFACT);
            Path destination = libraryDirectory.resolve("lib" + staged.size() + ".jar");
            Files.copy(artifact.file(), destination);
            if (!artifact.sha256().equals(hash(destination))) throw new Failed(Reason.CHANGED_ARTIFACT);
            staged.add("/input/lib/" + destination.getFileName());
        }
        return List.copyOf(staged);
    }

    static String hash(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (NoSuchAlgorithmException ex) { throw new IllegalStateException("SHA-256 unavailable"); }
    }
    static String hash(Path file) throws IOException {
        return hash(file, Long.MAX_VALUE);
    }
    private static String hash(Path file, long deadlineNanos) throws IOException {
        try (var stream = Files.newInputStream(file)) {
            var digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[8192];
            for (int count; (count = stream.read(buffer)) != -1;) {
                checkDeadline(deadlineNanos);
                digest.update(buffer, 0, count);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException ex) { throw new IllegalStateException("SHA-256 unavailable"); }
    }
    private static void checkDeadline(long deadlineNanos) {
        if (System.nanoTime() >= deadlineNanos) throw new Failed(Reason.TIMEOUT);
    }
}
