package eg.mts.gsuif.generation;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Binds a BACKEND attempt to exact selected inputs, deterministic bytes and server provider/build settings. */
final class BackendAttemptFingerprint {
    private BackendAttemptFingerprint() { }

    static String of(String requestFingerprint, BackendModelInput input, List<Map<String, byte[]>> deterministic,
            CompleteBackendPackageAssembler.Provenance provenance, String buildImage) {
        try {
            MessageDigest hash = MessageDigest.getInstance("SHA-256");
            add(hash, "backend-attempt-v1");
            add(hash, requestFingerprint);
            add(hash, "BACKEND");
            add(hash, provenance.providerId());
            add(hash, provenance.providerVersion());
            add(hash, provenance.modelId());
            add(hash, Boolean.toString(provenance.mocked()));
            add(hash, buildImage);
            if (input == null) {
                add(hash, "preparation-unavailable");
                return HexFormat.of().formatHex(hash.digest());
            }
            add(hash, input.contract());
            add(hash, input.pom());
            for (var entry : input.plan().entries()) {
                add(hash, entry.path()); add(hash, entry.owner().name());
            }
            for (var definition : input.handoff().definitions()) {
                add(hash, definition.path()); add(hash, definition.bytes());
            }
            TreeMap<String, byte[]> files = new TreeMap<>();
            for (var portion : deterministic) for (var entry : portion.entrySet()) {
                if (files.putIfAbsent(entry.getKey(), entry.getValue()) != null)
                    throw new IllegalArgumentException("Duplicate deterministic backend path");
            }
            files.forEach((path, bytes) -> { add(hash, path); add(hash, bytes); });
            return HexFormat.of().formatHex(hash.digest());
        } catch (NoSuchAlgorithmException ex) { throw new IllegalStateException(ex); }
    }

    private static void add(MessageDigest hash, String value) {
        add(hash, value == null ? new byte[0] : value.getBytes(StandardCharsets.UTF_8));
    }

    private static void add(MessageDigest hash, byte[] value) {
        hash.update(ByteBuffer.allocate(Integer.BYTES).putInt(value.length).array());
        hash.update(value);
    }
}
