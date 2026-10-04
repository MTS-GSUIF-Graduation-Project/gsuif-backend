package eg.mts.gsuif.generation;

import java.util.List;

public record GenerationResult(List<Artifact> artifacts, List<String> diagnostics, ConsumerBuildContract consumerBuild) {
    public GenerationResult { artifacts = List.copyOf(artifacts); diagnostics = List.copyOf(diagnostics); }

    public GenerationResult(List<Artifact> artifacts, List<String> diagnostics) {
        this(artifacts, diagnostics, null);
    }

    public record Artifact(String relativePath, byte[] bytes, String sha256,
            String templateVersion, String catalogVersion) {
        public Artifact { bytes = bytes.clone(); }
        @Override public byte[] bytes() { return bytes.clone(); }
    }
}
