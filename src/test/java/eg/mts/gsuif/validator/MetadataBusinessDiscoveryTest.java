package eg.mts.gsuif.validator;

import eg.mts.gsuif.repository.GsuifPageRepository;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import static eg.mts.gsuif.validator.BusinessRuleTestSupport.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.mock;

class MetadataBusinessDiscoveryTest {
    @Test void discoversAdditionalBeansRunsAllApplicableRulesAndSkipsInapplicableRules() {
        AtomicInteger calls = new AtomicInteger();
        try (var c = context(mock(GsuifPageRepository.class))) {
            register(c, "testRuleB", "TestB", true, calls);
            register(c, "testRuleA", "TestA", true, calls);
            register(c, "inapplicableRule", "MustNotRun", false, calls);
            c.refresh();
            Object validator = c.getBean(type("MetadataBusinessValidator"));
            var input = snapshotContext(snapshot(0, 1, "GET"));
            var result = errors(validator, input);
            assertThat(calls).hasValue(2);
            assertThat(result).hasSize(2);
            assertThat(result).extracting(MetadataSchemaValidator.ValidationError::path)
                    .containsExactly("$.snapshot", "$.snapshot");
            assertThat(result).extracting(MetadataSchemaValidator.ValidationError::message)
                    .containsExactly("TestA: additional failure", "TestB: additional failure");
            assertThat(errors(validator, input)).isEqualTo(result);
            assertThat(calls).hasValue(4);
        }
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    static void register(AnnotationConfigApplicationContext c, String beanName, String name,
                         boolean applicable, AtomicInteger calls) {
        Class ruleType = type("MetadataBusinessRule");
        Object bean = Proxy.newProxyInstance(ruleType.getClassLoader(), new Class<?>[]{ruleType}, (p, m, a) -> switch (m.getName()) {
            case "name" -> name;
            case "supports" -> applicable;
            case "validate" -> {
                assertThat(applicable).as("Core must skip inapplicable rules").isTrue();
                calls.incrementAndGet();
                yield List.of(new MetadataSchemaValidator.ValidationError("$.snapshot", name + ": additional failure"));
            }
            case "toString" -> name;
            case "hashCode" -> System.identityHashCode(p);
            case "equals" -> p == a[0];
            default -> throw new AssertionError(m);
        });
        c.registerBean(beanName, ruleType, () -> bean);
    }
}
