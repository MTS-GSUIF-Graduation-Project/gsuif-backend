package eg.mts.gsuif.generation;

import org.springframework.stereotype.Component;

/** Injectable final validation boundary; tests may substitute a labelled mock. */
interface BackendFinalBuild {
    String imageIdentity();
    void validate(CompleteBackendPackageAssembler.Assembled assembled);
}

@Component
final class ConfiguredBackendFinalBuild implements BackendFinalBuild {
    @Override public String imageIdentity() {
        String image = System.getenv("GSUIF_BACKEND_BUILD_IMAGE_ID");
        return image == null ? "unconfigured" : image;
    }

    @Override public void validate(CompleteBackendPackageAssembler.Assembled assembled) {
        BackendFinalPackageValidator.local().validate(assembled);
    }
}
