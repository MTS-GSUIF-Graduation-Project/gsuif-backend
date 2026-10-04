package eg.mts.gsuif.validator;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class BindingRelationshipsTest {
    private static final int CHAIN_LENGTH = 5_000;
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void acceptsLongAcyclicChainWithoutRecursion() {
        assertThat(BindingRelationships.validate(chain(false), "$.snapshot")).isEmpty();
    }

    @Test
    void rejectsCycleAtEndOfLongChainWithoutRecursion() {
        assertThat(BindingRelationships.validate(chain(true), "$.snapshot"))
                .anyMatch(error -> error.message().contains("cycle"));
    }

    private ObjectNode chain(boolean cycle) {
        ObjectNode record = mapper.createObjectNode();
        ArrayNode components = record.putArray("components");
        ArrayNode bindings = record.putArray("apiBindings");
        for (int i = 0; i < CHAIN_LENGTH; i++) {
            components.addObject().put("id", "component-" + i);
            if (i + 1 < CHAIN_LENGTH) {
                bindings.addObject()
                        .put("parentComponentId", "component-" + i)
                        .putArray("childComponentIds").add("component-" + (i + 1));
            }
        }
        if (cycle) {
            bindings.addObject()
                    .put("parentComponentId", "component-" + (CHAIN_LENGTH - 1))
                    .putArray("childComponentIds").add("component-0");
        }
        return record;
    }
}
