package eg.mts.gsuif.generation;

import eg.mts.gsuif.generation.GenerationFilePlan.Owner;

import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;

/** Frozen, project-specific definitions and contract supplied to one backend model invocation. */
final class BackendModelInput {
    private final CompleteOutputDescriptor descriptor;
    private final GenerationFilePlan plan;
    private final RenderedModelHandoff handoff;
    private final byte[] contract;
    private final String writablePath;

    BackendModelInput(CompleteOutputDescriptor descriptor, GenerationFilePlan plan,
            RenderedModelHandoff handoff, byte[] contract) {
        if (descriptor == null || plan == null || handoff == null || contract == null)
            throw new IllegalArgumentException("Missing backend model input");
        if (descriptor.mode() == GenerationCapabilityPreflight.OutputMode.FRONTEND)
            throw new IllegalArgumentException("Backend model input requires backend output");
        if (!ModelHandoffSpec.from(descriptor, plan).equals(handoff.specification()))
            throw new IllegalArgumentException("Model handoff differs from selected descriptor");
        if (!sha256(contract).equals(descriptor.contractResourceSha256()))
            throw new IllegalArgumentException("Selected contract bytes differ from frozen descriptor");
        List<String> paths = plan.entries().stream().filter(entry -> entry.owner() == Owner.MODEL)
                .map(GenerationFilePlan.Entry::path).toList();
        if (paths.size() != 1 || !paths.equals(handoff.specification().writablePaths()))
            throw new IllegalArgumentException("Initial backend profile requires one exact model path");
        String expected = "backend/src/main/java/" + descriptor.basePackage().replace('.', '/')
                + "/service/" + descriptor.entityName() + "ServiceImpl.java";
        if (!paths.getFirst().equals(expected))
            throw new IllegalArgumentException("Model path differs from selected backend service");
        this.descriptor = descriptor;
        this.plan = plan;
        this.handoff = handoff;
        this.contract = contract.clone();
        this.writablePath = expected;
    }

    static BackendModelInput from(CompleteOutputDescriptor descriptor, GenerationFilePlan plan,
            CompleteBackendDefinitionRenderer.Rendered rendered, CompleteBackendRuntimeRenderer runtime) {
        String path = "contracts/" + descriptor.contractId() + "-" + descriptor.contractVersion() + ".yaml";
        return new BackendModelInput(descriptor, plan, rendered.handoff(), runtime.render(descriptor, plan).get(path));
    }

    CompleteOutputDescriptor descriptor() { return descriptor; }
    GenerationFilePlan plan() { return plan; }
    RenderedModelHandoff handoff() { return handoff; }
    byte[] contract() { return contract.clone(); }
    String writablePath() { return writablePath; }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (java.security.NoSuchAlgorithmException ex) { throw new IllegalStateException(ex); }
    }
}
