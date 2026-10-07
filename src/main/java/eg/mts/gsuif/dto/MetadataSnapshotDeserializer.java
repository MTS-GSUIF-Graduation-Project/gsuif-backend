package eg.mts.gsuif.dto;

import tools.jackson.core.JsonParser;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectReader;
import tools.jackson.databind.ValueDeserializer;
import tools.jackson.databind.json.JsonMapper;

/** Preserves snapshot numbers before structural validation, without changing other DTOs. */
public final class MetadataSnapshotDeserializer extends ValueDeserializer<JsonNode> {
    // A property-local reader is needed: changing the application's mapper would
    // change number handling in unrelated DTOs. Reuse the incoming parser so its
    // stream constraints still apply; no JSON text is reparsed or rounded first.
    private static final ObjectReader READER = JsonMapper.builder()
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            // This reader consumes one property; the enclosing DTO reader checks
            // trailing tokens after the complete request, not after snapshot.
            .disable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .build().readerFor(JsonNode.class);

    @Override
    public JsonNode deserialize(JsonParser parser, DeserializationContext context) {
        return READER.readTree(parser);
    }
}
