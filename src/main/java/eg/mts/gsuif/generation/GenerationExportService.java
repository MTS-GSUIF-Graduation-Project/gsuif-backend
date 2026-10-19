package eg.mts.gsuif.generation;

import com.fasterxml.jackson.databind.ObjectMapper;
import eg.mts.gsuif.entity.GenerationRunStatus;
import eg.mts.gsuif.exception.ResourceNotFoundException;
import eg.mts.gsuif.repository.GeneratedArtifactRepository;
import eg.mts.gsuif.repository.GsuifProjectRepository;
import eg.mts.gsuif.security.EntityPermissionChecker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.zip.ZipInputStream;
import java.util.zip.CRC32;

/** Project settings live outside immutable metadata snapshots. */
@Service
public class GenerationExportService {
    private static final Logger log = LoggerFactory.getLogger(GenerationExportService.class);
    private final Path root;
    private final ObjectMapper mapper = new ObjectMapper();
    private final GenerationRunService runs;
    private final GeneratedArtifactRepository artifacts;
    private final GsuifProjectRepository projects;
    private final EntityPermissionChecker permissionChecker;
    private final Map<String, ExportDestination> destinations = Map.of(
            "LOCAL_FOLDER", new LocalFolderExportDestination(),
            "ZIP", new ZipExportDestination(),
            "CLOUD_STUB", new CloudStubExportDestination());

    public GenerationExportService(@Value("${gsuif.generation.export-root:var/exports}") String root,
            GenerationRunService runs, GeneratedArtifactRepository artifacts, GsuifProjectRepository projects,
            EntityPermissionChecker permissionChecker) {
        this.root = Path.of(root).toAbsolutePath().normalize();
        this.runs = runs;
        this.artifacts = artifacts;
        this.projects = projects;
        this.permissionChecker = permissionChecker;
    }

    /** Location relative to this project's export directory. */
    public record DestinationConfig(String type, String path) { }
    public record ExportLocation(UUID runId, UUID projectId, String destination, String location,
            String downloadUrl) {
        public ExportLocation(UUID runId, UUID projectId, String destination, String location) {
            this(runId, projectId, destination, location, null);
        }
    }
    public record DownloadedExport(String fileName, byte[] bytes) { }

    public DestinationConfig configure(UUID projectId, String name, DestinationConfig config) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        permissionChecker.requireEntityAccess("GsuifProject", authentication);
        safeName(name);
        if (!projects.existsById(projectId)) throw new ResourceNotFoundException("Project not found");
        destinationFor(config);
        configuredPath(config);
        Path file = configurationFile(projectId, name);
        try {
            checkedDirectory(file.getParent());
            if (Files.exists(file, LinkOption.NOFOLLOW_LINKS)) {
                if (Files.isSymbolicLink(file)) throw new IOException("Unsafe destination configuration");
                mapper.writeValue(file.toFile(), config);
            } else mapper.writeValue(file.toFile(), config);
            return config;
        } catch (IOException ex) { throw new IllegalStateException("Could not save destination configuration", ex); }
    }

    @Transactional(readOnly = true)
    public ExportLocation export(UUID runId, String name) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        permissionChecker.requireEntityAccess("GsuifProject", authentication);
        permissionChecker.requireEntityAccess("GsuifPage", authentication);
        permissionChecker.requireEntityAccess("MetadataVersion", authentication);
        safeName(name);
        var run = runs.findById(runId).orElseThrow(() -> new ResourceNotFoundException("Generation run not found"));
        if (run.getStatus() != GenerationRunStatus.SUCCESS)
            throw new IllegalStateException("Only committed successful runs can be exported");
        UUID projectId = run.getMetadataVersion().getPage().getProject().getId();
        DestinationConfig config = readConfiguration(projectId, name);
        var destination = destinationFor(config);
        Map<String, byte[]> saved = new LinkedHashMap<>();
        for (var artifact : artifacts.findAllByGenerationRunIdOrderByRelativePathAsc(runId)) {
            String path = artifact.getRelativePath();
            GenerationArtifactStore.safeRelative(path);
            saved.put(path, runs.readArtifact(runId, path));
        }
        // Verify the manifest and detect a missing registration before writing anything.
        var stored = runs.readResult(run);
        if (stored.artifacts().size() != saved.size() || saved.isEmpty())
            throw new IllegalStateException("Run artifact registry differs from storage");
        Path base = root.resolve(projectId.toString()).resolve(configuredPath(config));
        Path target = base.resolve(runId + (destination.type().equals("ZIP") ? ".zip" : ""));
        Path staging = base.resolve(".pending-" + UUID.randomUUID());
        try {
            checkedDirectory(base);
            if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Export already exists");
            destination.export(staging, saved);
            Files.move(staging, target);
            log.info("Exported generation run {} for project {} to {} at {}", runId, projectId, name, target);
            return new ExportLocation(runId, projectId, name, target.toUri().toString(),
                    destination.type().equals("ZIP") ? downloadUrl(runId, name) : null);
        } catch (IOException ex) {
            deleteIncomplete(staging);
            log.warn("Export failed for run {} to {}: {}", runId, name, ex.getMessage());
            throw new IllegalStateException("Could not export run: " + ex.getMessage(), ex);
        }
    }

    @Transactional(readOnly = true)
    public DownloadedExport download(UUID runId, String name) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        permissionChecker.requireEntityAccess("GsuifProject", authentication);
        permissionChecker.requireEntityAccess("GsuifPage", authentication);
        permissionChecker.requireEntityAccess("MetadataVersion", authentication);
        safeName(name);
        var run = runs.findById(runId).orElseThrow(() -> new ResourceNotFoundException("Generation run not found"));
        if (run.getStatus() != GenerationRunStatus.SUCCESS)
            throw new IllegalStateException("Only committed successful runs can be downloaded");
        UUID projectId = run.getMetadataVersion().getPage().getProject().getId();
        DestinationConfig config = readConfiguration(projectId, name);
        if (!"ZIP".equals(destinationFor(config).type()))
            throw new ResourceNotFoundException("ZIP export not found");
        Path file = root.resolve(projectId.toString()).resolve(configuredPath(config)).resolve(runId + ".zip");
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS))
            throw new ResourceNotFoundException("ZIP export not found");
        try {
            requireNoLinks(file);
            byte[] zip = Files.readAllBytes(file);
            var verified = runs.readResult(run);
            Map<String, byte[]> expected = new LinkedHashMap<>();
            for (var artifact : verified.artifacts()) {
                String path = GenerationArtifactStore.safeRelative(artifact.relativePath()).toString().replace('\\', '/');
                if (expected.putIfAbsent(path, artifact.bytes()) != null)
                    throw new IllegalStateException("Run contains duplicate artifact paths");
            }
            verifyZip(zip, expected);
            return new DownloadedExport(runId + ".zip", zip);
        } catch (IOException ex) {
            throw new IllegalStateException("Could not verify ZIP export", ex);
        }
    }

    private static String downloadUrl(UUID runId, String name) {
        return "/api/v1/generation/runs/" + runId + "/exports/" + name + "/download";
    }

    private void requireNoLinks(Path file) throws IOException {
        Path current = root;
        if (Files.isSymbolicLink(current)) throw new IOException("Export root is a symbolic link");
        for (Path part : root.relativize(file)) {
            current = current.resolve(part);
            if (Files.isSymbolicLink(current)) throw new IOException("Export path is a symbolic link");
        }
    }

    private static void verifyZip(byte[] zip, Map<String, byte[]> expected) throws IOException {
        // ZipExportDestination emits an archive without a comment. Reject appended bytes
        // and a replaced central directory as well as changed entry contents.
        int end = zip.length - 22;
        if (end < 0 || zip[end] != 'P' || zip[end + 1] != 'K'
                || zip[end + 2] != 5 || zip[end + 3] != 6
                || unsignedShort(zip, end + 4) != 0 || unsignedShort(zip, end + 6) != 0
                || unsignedShort(zip, end + 8) != expected.size()
                || unsignedShort(zip, end + 10) != expected.size()
                || unsignedShort(zip, end + 20) != 0
                || unsignedInt(zip, end + 16) + unsignedInt(zip, end + 12) != end)
            throw new IllegalStateException("ZIP export differs from verified run");
        verifyCentralDirectory(zip, (int) unsignedInt(zip, end + 16), end, expected);
        Set<String> seen = new HashSet<>();
        try (var input = new ZipInputStream(new ByteArrayInputStream(zip))) {
            java.util.zip.ZipEntry entry;
            while ((entry = input.getNextEntry()) != null) {
                byte[] bytes = expected.get(entry.getName());
                if (entry.isDirectory() || bytes == null || !seen.add(entry.getName()))
                    throw new IllegalStateException("ZIP export differs from verified run");
                byte[] actual = input.readNBytes(bytes.length + 1);
                if (!java.util.Arrays.equals(bytes, actual) || input.read() != -1)
                    throw new IllegalStateException("ZIP export differs from verified run");
                input.closeEntry();
            }
        }
        if (seen.size() != expected.size() || seen.isEmpty())
            throw new IllegalStateException("ZIP export differs from verified run");
    }

    private static void verifyCentralDirectory(byte[] zip, int cursor, int end,
            Map<String, byte[]> expected) {
        Set<String> seen = new HashSet<>();
        while (cursor < end) {
            if (!signature(zip, cursor, 0x02014b50) || cursor + 46 > end)
                throw new IllegalStateException("ZIP export differs from verified run");
            int nameLength = unsignedShort(zip, cursor + 28);
            int extraLength = unsignedShort(zip, cursor + 30);
            int commentLength = unsignedShort(zip, cursor + 32);
            long next = (long) cursor + 46 + nameLength + extraLength + commentLength;
            if (next > end || extraLength != 0 || commentLength != 0
                    || unsignedShort(zip, cursor + 34) != 0)
                throw new IllegalStateException("ZIP export differs from verified run");
            String name = new String(zip, cursor + 46, nameLength, StandardCharsets.UTF_8);
            byte[] expectedBytes = expected.get(name);
            if (expectedBytes == null || !seen.add(name)
                    || unsignedInt(zip, cursor + 24) != expectedBytes.length)
                throw new IllegalStateException("ZIP export differs from verified run");
            CRC32 crc = new CRC32();
            crc.update(expectedBytes);
            long crcValue = crc.getValue();
            long compressedSize = unsignedInt(zip, cursor + 20);
            int local = (int) unsignedInt(zip, cursor + 42);
            if (unsignedInt(zip, cursor + 16) != crcValue
                    || !signature(zip, local, 0x04034b50)
                    || local > cursor - 30
                    || unsignedShort(zip, cursor + 4) != unsignedShort(zip, local + 4)
                    || unsignedShort(zip, cursor + 6) != unsignedShort(zip, local + 4)
                    || unsignedShort(zip, local + 6) != unsignedShort(zip, cursor + 8)
                    || unsignedShort(zip, local + 8) != unsignedShort(zip, cursor + 10)
                    || (unsignedShort(zip, cursor + 8) & 0x0008) == 0
                    || unsignedShort(zip, cursor + 10) != 8
                    || unsignedShort(zip, cursor + 12) != unsignedShort(zip, local + 10)
                    || unsignedShort(zip, cursor + 14) != unsignedShort(zip, local + 12)
                    || unsignedShort(zip, cursor + 36) != 0
                    || unsignedInt(zip, cursor + 38) != 0
                    || unsignedShort(zip, local + 26) != nameLength
                    || unsignedShort(zip, local + 28) != 0
                    || (long) local + 30 + nameLength > cursor)
                throw new IllegalStateException("ZIP export differs from verified run");
            for (int i = 0; i < nameLength; i++) {
                if (zip[local + 30 + i] != zip[cursor + 46 + i])
                    throw new IllegalStateException("ZIP export differs from verified run");
            }
            long descriptor = (long) local + 30 + nameLength + compressedSize;
            if (descriptor + 16 > cursor || !signature(zip, (int) descriptor, 0x08074b50)
                    || unsignedInt(zip, (int) descriptor + 4) != crcValue
                    || unsignedInt(zip, (int) descriptor + 8) != compressedSize
                    || unsignedInt(zip, (int) descriptor + 12) != expectedBytes.length)
                throw new IllegalStateException("ZIP export differs from verified run");
            cursor = (int) next;
        }
        if (cursor != end || seen.size() != expected.size())
            throw new IllegalStateException("ZIP export differs from verified run");
    }

    private static boolean signature(byte[] bytes, int offset, int value) {
        return offset >= 0 && offset <= bytes.length - 4
                && unsignedInt(bytes, offset) == Integer.toUnsignedLong(value);
    }

    private static int unsignedShort(byte[] bytes, int offset) {
        return (bytes[offset] & 0xff) | (bytes[offset + 1] & 0xff) << 8;
    }

    private static long unsignedInt(byte[] bytes, int offset) {
        return Integer.toUnsignedLong(unsignedShort(bytes, offset)
                | unsignedShort(bytes, offset + 2) << 16);
    }

    private DestinationConfig readConfiguration(UUID projectId, String name) {
        Path file = configurationFile(projectId, name);
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS))
            throw new ResourceNotFoundException("Export destination not configured");
        try { return mapper.readValue(file.toFile(), DestinationConfig.class); }
        catch (IOException ex) { throw new IllegalStateException("Could not read destination configuration", ex); }
    }

    private Path configurationFile(UUID projectId, String name) {
        return root.resolve("config").resolve(projectId.toString()).resolve(name + ".json");
    }

    private ExportDestination destinationFor(DestinationConfig config) {
        if (config == null || config.type() == null || config.type().isBlank())
            throw new GenerationValidationException(List.of("destination.type: unsupported value"));
        ExportDestination destination = destinations.get(config.type());
        if (destination == null) throw new GenerationValidationException(List.of("destination.type: unsupported value"));
        return destination;
    }

    private static Path configuredPath(DestinationConfig config) {
        String value = config.path();
        if (value == null || value.isBlank() || value.indexOf('\\') >= 0 ||
                !value.matches("[A-Za-z0-9_-]+(/[A-Za-z0-9_-]+)*"))
            throw new GenerationValidationException(List.of("destination.path: must be a project-relative directory"));
        return Path.of(value);
    }

    private void checkedDirectory(Path directory) throws IOException {
        Files.createDirectories(root);
        if (Files.isSymbolicLink(root)) throw new IOException("Export root is a symbolic link");
        Path current = root;
        for (Path part : root.relativize(directory)) {
            current = current.resolve(part);
            if (Files.isSymbolicLink(current)) throw new IOException("Export path is a symbolic link");
            Files.createDirectories(current);
        }
    }

    private static void safeName(String name) {
        if (name == null || !name.matches("[A-Za-z0-9][A-Za-z0-9_-]{0,63}"))
            throw new GenerationValidationException(List.of("destination: invalid name"));
    }

    private static void deleteIncomplete(Path target) {
        if (!Files.exists(target, LinkOption.NOFOLLOW_LINKS)) return;
        try (var files = Files.walk(target)) {
            for (Path file : files.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(file);
        } catch (IOException ex) {
            log.warn("Could not remove incomplete export at {}", target, ex);
        }
    }
}
