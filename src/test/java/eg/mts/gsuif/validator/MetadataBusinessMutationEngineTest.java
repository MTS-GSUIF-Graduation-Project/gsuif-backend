package eg.mts.gsuif.validator;

import net.jqwik.api.Property;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.platform.engine.*;
import org.junit.platform.engine.discovery.DiscoverySelectors;
import java.util.*;
import static org.assertj.core.api.Assertions.assertThat;

/** Host-run engine checks: expected property failures are captured as engine results. */
class MetadataBusinessMutationEngineTest {
    @ParameterizedTest(name = "{0}: normal, mutant, seed replay, restored")
    @CsvSource({
            "ComponentIdUniqueness, duplicateIdsAreRejectedAndAlwaysAcceptingMutantIsKilled",
            "SupportedHttpMethod, methodRuleRejectsNonmembersWithoutStructuralValidator",
            "RouteUniqueness, routesAreProjectScopedAndExcludeCurrentPage",
            "SnapshotSize, exactSizeBoundaryAndSizeMutant"
    })
    void engineKillsEachReplacementAndReplaysItsSeed(String rule, String property) {
        assertPassed(run(property, null));
        Run killed = run(property, rule);
        assertKilled(killed);
        Run replay = run(property, rule);
        assertKilled(replay);
        assertThat(replay.samples()).as("Same pinned seed reproduces generated and shrunk inputs")
                .isNotEmpty().isEqualTo(killed.samples());
        assertPassed(run(property, null));
    }

    private void assertPassed(Run run) {
        assertThat(run.results()).hasSize(1);
        assertThat(run.results().getFirst().getStatus()).isEqualTo(TestExecutionResult.Status.SUCCESSFUL);
    }

    private void assertKilled(Run run) {
        assertThat(run.results()).hasSize(1);
        assertThat(run.results().getFirst().getStatus()).isEqualTo(TestExecutionResult.Status.FAILED);
        assertThat(run.results().getFirst().getThrowable()).isPresent();
        assertThat(run.results().getFirst().getThrowable().orElseThrow()).isInstanceOf(AssertionError.class);
        assertThat(run.samples()).isNotEmpty();
    }

    private record Run(List<TestExecutionResult> results, List<Object> samples) { }

    private Run run(String property, String mutant) {
        var propertyClass = MetadataBusinessValidatorPropertiesTest.class;
        var method = Arrays.stream(propertyClass.getDeclaredMethods())
                .filter(candidate -> candidate.getName().equals(property)).findFirst().orElseThrow();
        var selector = DiscoverySelectors.selectMethod(propertyClass, method);
        ConfigurationParameters parameters = new ConfigurationParameters() {
            // Do not let a saved previous failure change the pinned-seed replay.
            public Optional<String> get(String key) {
                return switch (key) {
                    case "jqwik.database" -> Optional.of("");
                    // Keep expected mutant-failure reports inside this engine listener.
                    case "jqwik.reporting.usejunitplatform" -> Optional.of("true");
                    default -> Optional.empty();
                };
            }
            public Optional<Boolean> getBoolean(String key) {
                return "jqwik.reporting.usejunitplatform".equals(key) ? Optional.of(true) : Optional.empty();
            }
            public Set<String> keySet() { return Set.of("jqwik.database", "jqwik.reporting.usejunitplatform"); }
        };
        EngineDiscoveryRequest request = new EngineDiscoveryRequest() {
            public <T extends DiscoverySelector> List<T> getSelectorsByType(Class<T> type) {
                return type.isInstance(selector) ? List.of(type.cast(selector)) : List.of();
            }
            public <T extends DiscoveryFilter<?>> List<T> getFiltersByType(Class<T> type) { return List.of(); }
            public ConfigurationParameters getConfigurationParameters() { return parameters; }
        };
        TestEngine engine = ServiceLoader.load(TestEngine.class).stream().map(ServiceLoader.Provider::get)
                .filter(candidate -> candidate.getId().equals("jqwik")).findFirst().orElseThrow();
        List<TestExecutionResult> results = new ArrayList<>();
        List<Object> samples = new ArrayList<>();
        EngineExecutionListener listener = new EngineExecutionListener() {
            @Override public void reportingEntryPublished(TestDescriptor descriptor,
                    org.junit.platform.engine.reporting.ReportEntry entry) {
                // Expected mutant failures are asserted below; avoid printing their full reports.
            }
            @Override public void executionFinished(TestDescriptor descriptor, TestExecutionResult result) {
                if (descriptor.isTest()) results.add(result);
                else assertThat(result.getStatus()).as(descriptor.getDisplayName())
                        .isEqualTo(TestExecutionResult.Status.SUCCESSFUL);
            }
        };
        MetadataBusinessValidatorPropertiesTest.ENGINE_MUTANT.set(mutant);
        MetadataBusinessValidatorPropertiesTest.ENGINE_SAMPLES.set(samples);
        try {
            var descriptor = engine.discover(request, UniqueId.forEngine("jqwik"));
            engine.execute(ExecutionRequest.create(descriptor, listener, parameters));
            System.out.printf("SCRUM-51 property=%s seed=%s mutant=%s status=%s recordedSamples=%s%n",
                    property, method.getAnnotation(Property.class).seed(), mutant,
                    results.stream().map(TestExecutionResult::getStatus).toList(), samples.size());
            return new Run(List.copyOf(results), List.copyOf(samples));
        } finally {
            MetadataBusinessValidatorPropertiesTest.ENGINE_MUTANT.remove();
            MetadataBusinessValidatorPropertiesTest.ENGINE_SAMPLES.remove();
        }
    }
}
