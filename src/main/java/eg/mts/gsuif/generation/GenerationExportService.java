package eg.mts.gsuif.generation;

import com.fasterxml.jackson.databind.ObjectMapper;
import eg.mts.gsuif.exception.ResourceNotFoundException;
import eg.mts.gsuif.repository.GeneratedArtifactRepository;
import eg.mts.gsuif.repository.GsuifProjectRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.Comparator;

/** Project settings live outside immutable metadata snapshots. */
@Service
public class GenerationExportService {
    private static final Logger log = LoggerFactory.getLogger(GenerationExportService.class);
    private final Path root;
    private final ObjectMapper mapper = new ObjectMapper();
    private final GenerationRunService runs;
    private final GeneratedArtifactRepository artifacts;
    private final GsuifProjectRepository projects;
    private final Map<String, ExportDestination> destinations = Map.of(
            "LOCAL_FOLDER", new LocalFolderExportDestination(),
            "ZIP", new ZipExportDestination(),
            "CLOUD_STUB", new CloudStubExportDestination());

    public GenerationExportService(@Value("${gsuif.generation.export-root:var/exports}") String root,
            GenerationRunService runs, GeneratedArtifactRepository artifacts, GsuifProjectRepository projects) {
        this.root = Path.of(root).toAbsolutePath().normalize();
        this.runs = runs;
        this.artifacts = artifacts;
        this.projects = projects;
    }

    /** Location relative to this project's export directory. */
    public record DestinationConfig(String type, String path) { }
    public record ExportLocation(UUID runId, UUID projectId, String destination, String location) { }

    public DestinationConfig configure(UUID projectId, String name, DestinationConfig config) {
        safeName(name);
        if (!projects.existsById(projectId)) throw new ResourceNotFoundException("Project not found");
        if (config == null || !destinations.containsKey(config.type()))
            throw new GenerationValidationException(List.of("destination.type: unsupported value"));
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
        safeName(name);
        var run = runs.findById(runId).orElseThrow(() -> new ResourceNotFoundException("Generation run not found"));
        UUID projectId = run.getMetadataVersion().getPage().getProject().getId();
        DestinationConfig config = readConfiguration(projectId, name);
        var destination = destinations.get(config.type());
        if (destination == null) throw new GenerationValidationException(List.of("destination.type: unsupported value"));
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
            return new ExportLocation(runId, projectId, name, target.toUri().toString());
        } catch (IOException ex) {
            deleteIncomplete(staging);
            log.warn("Export failed for run {} to {}: {}", runId, name, ex.getMessage());
            throw new IllegalStateException("Could not export run: " + ex.getMessage(), ex);
        }
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
