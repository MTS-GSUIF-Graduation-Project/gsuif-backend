package eg.mts.gsuif.generation;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

/** Exact, immutable definition bytes handed to a future prompt builder. */
final class RenderedModelHandoff {
    record Definition(String role, String path, byte[] bytes, String sha256) {
        Definition {
            bytes = bytes.clone();
            if (!sha256.equals(hash(bytes))) throw new IllegalArgumentException("Handoff definition hash mismatch");
        }
        @Override public byte[] bytes() { return bytes.clone(); }
    }

    private final ModelHandoffSpec specification;
    private final List<Definition> definitions;

    private RenderedModelHandoff(ModelHandoffSpec specification, List<Definition> definitions) {
        this.specification = specification;
        this.definitions = List.copyOf(definitions);
    }

    static RenderedModelHandoff from(ModelHandoffSpec specification, Map<String, byte[]> emitted) {
        List<Definition> rendered = new ArrayList<>();
        for (ModelHandoffSpec.Definition expected : specification.definitions()) {
            byte[] bytes = emitted.get(expected.path());
            if (bytes == null) throw new IllegalArgumentException("Missing handoff definition: " + expected.path());
            rendered.add(new Definition(expected.role(), expected.path(), bytes, hash(bytes)));
        }
        return new RenderedModelHandoff(specification, rendered);
    }

    ModelHandoffSpec specification() { return specification; }
    List<Definition> definitions() { return definitions; }

    private static String hash(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException ex) { throw new IllegalStateException(ex); }
    }
}
