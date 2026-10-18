package eg.mts.gsuif.generation;

import org.springframework.stereotype.Component;

/** OpenRouter is a server setting, never a client-selectable request field. */
@Component
final class ConfiguredOpenRouterBackendGateway implements BackendModelGateway {
    @Override public CompleteBackendPackageAssembler.Provenance provenance() {
        return new CompleteBackendPackageAssembler.Provenance(
                OpenRouterBackendModelAdapter.PROVIDER_ID,
                OpenRouterBackendModelAdapter.PROVIDER_API_VERSION,
                OpenRouterBackendModelAdapter.MODEL, false);
    }

    @Override public BackendModelOutputValidator.Accepted generate(BackendModelInput input) {
        String image = System.getenv("GSUIF_BACKEND_COMPILER_IMAGE_ID");
        String key = System.getenv("OPENROUTER_API_KEY");
        String model = System.getenv("OPENROUTER_MODEL");
        if (image == null || image.isBlank() || key == null || key.isBlank()
                || !OpenRouterBackendModelAdapter.MODEL.equals(model))
            throw new OpenRouterBackendModelAdapter.Failed(OpenRouterBackendModelAdapter.Failure.CONFIG);
        try { return OpenRouterBackendModelAdapter.local(image).generate(input, key, model); }
        catch (IllegalArgumentException | BackendMavenDependencyProvisioner.Failed ex) {
            throw new OpenRouterBackendModelAdapter.Failed(OpenRouterBackendModelAdapter.Failure.DEPENDENCY_CONFIG);
        }
    }
}
