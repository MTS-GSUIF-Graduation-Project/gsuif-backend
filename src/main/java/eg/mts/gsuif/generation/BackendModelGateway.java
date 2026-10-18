package eg.mts.gsuif.generation;

/** Injectable boundary; only the production implementation reads server-side provider configuration. */
interface BackendModelGateway {
    CompleteBackendPackageAssembler.Provenance provenance();
    BackendModelOutputValidator.Accepted generate(BackendModelInput input);
}
