package eg.mts.gsuif.validator;

import net.jqwik.api.*;
import eg.mts.gsuif.repository.GsuifPageRepository;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static eg.mts.gsuif.validator.BusinessRuleTestSupport.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class MetadataBusinessValidatorPropertiesTest {
    // Scoped by the engine harness; normal host discovery always uses the real rules.
    static final ThreadLocal<String> ENGINE_MUTANT = new ThreadLocal<>();
    static final ThreadLocal<List<Object>> ENGINE_SAMPLES = new ThreadLocal<>();
    @Provide Arbitrary<String> methods() { return Arbitraries.of("GET", "POST", "PUT", "DELETE"); }
    @Provide Arbitrary<String> invalidMethods() {
        return Arbitraries.oneOf(Arbitraries.of("PATCH", "get", " GET", "GET ", "", "HEAD", "OPTIONS"),
                Arbitraries.strings().ascii().ofMaxLength(16)
                        .filter(s -> !Set.of("GET", "POST", "PUT", "DELETE").contains(s)));
    }
    @Provide Arbitrary<Integer> counts() { return Arbitraries.integers().between(0, 8); }
    @Provide Arbitrary<String> routes() {
        return Arbitraries.strings().alpha().ofMinLength(1).ofMaxLength(30).map(s -> "/" + s);
    }

    @Property(tries = 40, seed = "51001")
    void everyApplicableRuleAcceptsValidInputs(@ForAll long salt, @ForAll("counts") int count,
                                             @ForAll("methods") String method,
                                             @ForAll("routes") String route) {
        var s = snapshot(count, salt, method);
        assertThat(new MetadataSchemaValidator().validate("1.0.0", s)).isEmpty();
        assertThat(utf8(s.toString())).isLessThan(5_000_000);
        var repo = mock(GsuifPageRepository.class);
        try (var c = context(repo)) {
            c.refresh();
            var validator = c.getBean(type("MetadataBusinessValidator"));
            assertThat(errors(validator, snapshotContext(s))).isEmpty();
            assertThat(errors(validator, pageContext(PROJECT, null, route))).isEmpty();
            assertThat(errors(validator, pageContext(PROJECT, PAGE, null))).isEmpty();
            for (String name : List.of(COMPONENT, METHOD, SIZE))
                assertThat(errors(rule(c, name), snapshotContext(s))).isEmpty();
            assertThat(errors(rule(c, ROUTE), pageContext(PROJECT, null, route))).isEmpty();
        }
    }

    @Property(tries = 40, seed = "51002")
    void duplicateIdsAreRejectedAndAlwaysAcceptingMutantIsKilled(@ForAll long salt) {
        var s = duplicate(salt);
        assertThat(new MetadataSchemaValidator().validate("1.0.0", s)).isEmpty();
        try (var c = context(mock(GsuifPageRepository.class))) {
            c.refresh();
            targetedOracleAndMutant(rule(c, COMPONENT), snapshotContext(s), COMPONENT,
                    "$.snapshot.components[1].id");
        }
    }

    @Property(tries = 50, seed = "51003")
    void methodRuleRejectsNonmembersWithoutStructuralValidator(@ForAll("invalidMethods") String method) {
        try (var c = context(mock(GsuifPageRepository.class))) {
            c.refresh();
            targetedOracleAndMutant(rule(c, METHOD), snapshotContext(snapshot(0, 1, method)),
                    METHOD, "$.snapshot.apiBindings[0].httpMethod");
        }
    }

    @Property(tries = 30, seed = "51004")
    void routesAreProjectScopedAndExcludeCurrentPage(@ForAll("routes") String route, @ForAll long salt) {
        UUID project = new UUID(salt, 1), other = new UUID(salt, 2), page = new UUID(salt, 3);
        var repo = mock(GsuifPageRepository.class);
        when(repo.existsByProjectIdAndRoute(project, route)).thenReturn(true);
        when(repo.existsByProjectIdAndRouteAndIdNot(project, route, page)).thenReturn(false);
        try (var c = context(repo)) {
            c.refresh();
            Object rule = rule(c, ROUTE);
            targetedOracleAndMutant(rule, pageContext(project, null, route), ROUTE, "route");
            assertThat(errors(rule, pageContext(other, null, route))).isEmpty();
            assertThat(errors(rule, pageContext(project, page, route))).isEmpty();
            assertThat(errors(rule, pageContext(project, page, null))).isEmpty();
            when(repo.existsByProjectIdAndRouteAndIdNot(project, route, page)).thenReturn(true);
            rejects(rule, pageContext(project, page, route), ROUTE, "route");
        }
    }

    @Property(tries = 3, seed = "51005")
    void exactSizeBoundaryAndSizeMutant(@ForAll @net.jqwik.api.constraints.IntRange(min = -1, max = 1) int delta) {
        var s = sized(5_000_000 + delta, false);
        try (var c = context(mock(GsuifPageRepository.class))) {
            c.refresh();
            if (delta <= 0) assertThat(errors(rule(c, SIZE), snapshotContext(s))).isEmpty();
            else targetedOracleAndMutant(rule(c, SIZE), snapshotContext(s), SIZE, "$.snapshot");
        }
    }

    @Example void explicitMethodRegressions() {
        for (String method : List.of("PATCH", "get", " GET", "GET ", ""))
            methodRuleRejectsNonmembersWithoutStructuralValidator(method);
        for (String method : List.of("GET", "POST", "PUT", "DELETE"))
            everyApplicableRuleAcceptsValidInputs(0, 0, method, "/");
    }

    @Example void allThreeSizeBoundariesAreMandatory() {
        for (int delta : List.of(-1, 0, 1)) exactSizeBoundaryAndSizeMutant(delta);
    }

    @Example void aggregatesIndependentBusinessErrorsDeterministically() {
        var s = sized(5_000_001, true);
        ((com.fasterxml.jackson.databind.node.ObjectNode) s.get("apiBindings").get(0)).put("httpMethod", "PATCH");
        try (var c = context(mock(GsuifPageRepository.class))) {
            c.refresh();
            var validator = c.getBean(type("MetadataBusinessValidator"));
            var context = snapshotContext(s);
            rejects(validator, context, COMPONENT, "$.snapshot.components[1].id");
            rejects(validator, context, SIZE, "$.snapshot");
            rejects(validator, context, METHOD, "$.snapshot.apiBindings[0].httpMethod");
            var first = errors(validator, context);
            assertThat(first).isEqualTo(errors(validator, context));
            assertThat(first).extracting(MetadataSchemaValidator.ValidationError::path).isSorted();
        }
    }

    // The identical negative-property oracle must fail for a real always-accepting rule.
    // Structural validation is deliberately absent from this call path.
    private void targetedOracleAndMutant(Object real, Object context, String name, String path) {
        Object mutant = Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{type("MetadataBusinessRule")}, (p, m, a) -> switch (m.getName()) {
                    case "name" -> name;
                    case "supports" -> true;
                    case "validate" -> List.of();
                    case "toString" -> "AlwaysAccepting(" + name + ")";
                    case "hashCode" -> System.identityHashCode(p);
                    case "equals" -> p == a[0];
                    default -> throw new AssertionError(m);
                });
        if (ENGINE_SAMPLES.get() != null) ENGINE_SAMPLES.get().add(context);
        rejects(name.equals(ENGINE_MUTANT.get()) ? mutant : real, context, name, path);
        assertThatThrownBy(() -> rejects(mutant, context, name, path)).isInstanceOf(AssertionError.class);
    }
}
