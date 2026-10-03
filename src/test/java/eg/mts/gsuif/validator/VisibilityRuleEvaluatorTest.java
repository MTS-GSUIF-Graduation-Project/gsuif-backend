package eg.mts.gsuif.validator;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.util.Set;
import static org.assertj.core.api.Assertions.assertThat;

class VisibilityRuleEvaluatorTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void evaluatesNestedPermissionRoleAndFieldPredicates() throws Exception {
        JsonNode rule = mapper.readTree("""
                {"op":"AND","rules":[{"op":"permission","value":"users:read"},
                {"op":"OR","rules":[{"op":"role","value":"admin"},{"op":"field","field":"status","equals":"ACTIVE"}]},
                {"op":"NOT","rule":{"op":"role","value":"suspended"}}]}
                """);
        var allowed = new VisibilityRuleEvaluator.Context(Set.of("users:read"), Set.of("viewer"), mapper.readTree("{\"status\":\"ACTIVE\"}"));
        assertThat(VisibilityRuleEvaluator.evaluate(rule, allowed)).isTrue();
        assertThat(VisibilityRuleEvaluator.evaluate(rule, new VisibilityRuleEvaluator.Context(Set.of(), Set.of("viewer"), allowed.fields()))).isFalse();
        assertThat(VisibilityRuleEvaluator.evaluate(rule, new VisibilityRuleEvaluator.Context(Set.of("users:read"), Set.of("suspended"), allowed.fields()))).isFalse();
    }

    @Test
    void invalidAndMissingContextRemainFalseBeneathNotAndOr() throws Exception {
        for (String child : new String[]{"{\"op\":\"unsupported\"}", "{\"op\":\"field\",\"field\":\"missing\",\"equals\":1}"}) {
            JsonNode rule = mapper.readTree("{\"op\":\"NOT\",\"rule\":" + child + "}");
            assertThat(VisibilityRuleEvaluator.evaluate(rule, new VisibilityRuleEvaluator.Context(Set.of(), Set.of(), mapper.readTree("{}")))).isFalse();
            JsonNode or = mapper.readTree("{\"op\":\"OR\",\"rules\":[{\"op\":\"role\",\"value\":\"admin\"}," + child + "]}");
            assertThat(VisibilityRuleEvaluator.evaluate(or, new VisibilityRuleEvaluator.Context(Set.of(), Set.of("admin"), mapper.readTree("{}")))).isFalse();
        }
        JsonNode notRole = mapper.readTree("{\"op\":\"NOT\",\"rule\":{\"op\":\"role\",\"value\":\"admin\"}}");
        assertThat(VisibilityRuleEvaluator.evaluate(notRole, new VisibilityRuleEvaluator.Context(Set.of(), null, null))).isFalse();
        assertThat(VisibilityRuleEvaluator.evaluate(notRole, null)).isFalse();
    }
}
