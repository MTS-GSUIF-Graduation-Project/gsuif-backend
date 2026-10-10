package eg.mts.gsuif.validator;

import eg.mts.gsuif.dto.CreateMetadataVersionRequest;
import eg.mts.gsuif.entity.*;
import eg.mts.gsuif.exception.MetadataValidationException;
import eg.mts.gsuif.repository.*;
import eg.mts.gsuif.service.impl.MetadataVersionServiceImpl;
import eg.mts.gsuif.security.EntityPermissionChecker;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.ObjectMapper;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import static eg.mts.gsuif.validator.BusinessRuleTestSupport.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.any;

class MetadataBusinessPersistenceTest {
    private static final UUID OLD = new UUID(0, 100), NEXT = new UUID(0, 101);

    private static class Harness implements AutoCloseable {
        final GsuifPageRepository pages = mock(GsuifPageRepository.class);
        final MetadataVersionRepository versions = mock(MetadataVersionRepository.class);
        final ObjectMapper mapper = spy(new ObjectMapper());
        final GsuifPage page = new GsuifPage();
        final AnnotationConfigApplicationContext context = context(pages);
        Harness(boolean extraRules) {
            GsuifProject project = new GsuifProject();
            ReflectionTestUtils.setField(project, "id", PROJECT);
            ReflectionTestUtils.setField(page, "id", PAGE);
            page.setProject(project);
            page.setCurrentMetadataVersionId(OLD);
            when(pages.findByIdWithLock(PAGE)).thenReturn(Optional.of(page));
            when(versions.findFirstByPageIdOrderByVersionDesc(PAGE)).thenReturn(Optional.empty());
            when(versions.save(any(MetadataVersion.class))).thenAnswer(call -> {
                MetadataVersion v = call.getArgument(0);
                ReflectionTestUtils.setField(v, "id", NEXT);
                return v;
            });
            context.registerBean(ObjectMapper.class, () -> mapper);
            context.registerBean(MetadataVersionRepository.class, () -> versions);
            context.registerBean(EntityPermissionChecker.class, () -> mock(EntityPermissionChecker.class));
            context.registerBean(MetadataVersionServiceImpl.class);
            if (extraRules) {
                MetadataBusinessDiscoveryTest.register(context, "testA", "TestA", true, new AtomicInteger());
                MetadataBusinessDiscoveryTest.register(context, "testB", "TestB", true, new AtomicInteger());
            }
            context.refresh();
        }
        MetadataVersionServiceImpl service() { return context.getBean(MetadataVersionServiceImpl.class); }
        @Override public void close() { context.close(); }
        void unchanged() {
            verify(versions, never()).save(any());
            verify(pages, never()).save(any());
            assertThat(page.getCurrentMetadataVersionId()).isEqualTo(OLD);
        }
    }

    @ParameterizedTest @ValueSource(ints = {4_999_999, 5_000_000, 5_000_001})
    void measuresExactStoredUtf8AndSerializesOnce(int bytes) {
        try (var h = new Harness(false)) {
            var input = h.mapper.readTree(sized(bytes, false).toString());
            String expected = new ObjectMapper().writeValueAsString(input);
            assertThat(utf8(expected)).isEqualTo(bytes);
            var request = new CreateMetadataVersionRequest("  1.0.0  ", input);
            if (bytes > 5_000_000) {
                var failure = catchThrowableOfType(() -> h.service().create(PAGE, request), MetadataValidationException.class);
                assertThat(failure).isNotNull();
                assertThat(failure.getErrors()).containsKey("$.snapshot");
                assertThat(failure.getErrors().get("$.snapshot")).contains(SIZE);
                h.unchanged();
            } else {
                h.service().create(PAGE, request);
                var captured = org.mockito.ArgumentCaptor.forClass(MetadataVersion.class);
                verify(h.versions).save(captured.capture());
                assertThat(captured.getValue().getSnapshot()).isEqualTo(expected);
                assertThat(utf8(captured.getValue().getSnapshot())).isEqualTo(bytes);
                assertThat(h.page.getCurrentMetadataVersionId()).isEqualTo(NEXT);
            }
            verify(h.mapper, times(1)).writeValueAsString(input);
        }
    }

    @Test void countsPersistenceSerializerRepresentationRatherThanNodeToString() {
        try (var h = new Harness(false)) {
            var input = h.mapper.readTree(sized(5_000_000, false).toString());
            // A legal JSON whitespace byte emitted by the persistence serializer crosses the boundary.
            doReturn(input.toString() + " ").when(h.mapper).writeValueAsString(input);
            var failure = catchThrowableOfType(() -> h.service().create(PAGE,
                    new CreateMetadataVersionRequest("1.0.0", input)), MetadataValidationException.class);
            assertThat(failure).isNotNull();
            assertThat(failure.getErrors().get("$.snapshot")).contains(SIZE);
            h.unchanged();
            verify(h.mapper, times(1)).writeValueAsString(input);
        }
    }

    @Test void duplicateAndOversizeAreAggregatedBeforeAnySave() {
        try (var h = new Harness(false)) {
            var input = h.mapper.readTree(sized(5_000_001, true).toString());
            var failure = catchThrowableOfType(() -> h.service().create(PAGE,
                    new CreateMetadataVersionRequest("1.0.0", input)), MetadataValidationException.class);
            assertThat(failure).isNotNull();
            assertThat(failure.getErrors().get("$.snapshot")).contains(SIZE);
            assertThat(failure.getErrors().get("$.snapshot.components[1].id")).contains(COMPONENT);
            h.unchanged();
        }
    }

    @Test void sameFieldMessagesFromDiscoveredRulesSurviveServiceGrouping() {
        try (var h = new Harness(true)) {
            var failure = catchThrowableOfType(() -> h.service().create(PAGE,
                    new CreateMetadataVersionRequest("1.0.0", h.mapper.readTree(snapshot(0, 1, "GET").toString()))),
                    MetadataValidationException.class);
            assertThat(failure).isNotNull();
            assertThat(failure.getErrors().get("$.snapshot")).contains("TestA", "TestB");
            h.unchanged();
        }
    }

    @Test void componentIdsCanRecurAcrossPagesAndHistoricalVersions() {
        try (var h = new Harness(false)) {
            var input = h.mapper.readTree(snapshot(1, 1, "GET").toString());
            var request = new CreateMetadataVersionRequest("1.0.0", input);
            var first = h.service().create(PAGE, request);
            MetadataVersion previous = new MetadataVersion();
            previous.setVersion(1);
            previous.setSnapshot(input.toString());
            when(h.versions.findFirstByPageIdOrderByVersionDesc(PAGE)).thenReturn(Optional.of(previous));
            assertThat(h.service().create(PAGE, request).version()).isEqualTo(2);
            UUID anotherPage = new UUID(0, 999);
            when(h.pages.findByIdWithLock(anotherPage)).thenReturn(Optional.of(h.page));
            when(h.versions.findFirstByPageIdOrderByVersionDesc(anotherPage)).thenReturn(Optional.empty());
            assertThat(h.service().create(anotherPage, request).version()).isEqualTo(1);
            assertThat(first.version()).isEqualTo(1);
            assertThat(previous.getVersion()).isEqualTo(1);
            assertThat(previous.getSnapshot()).isEqualTo(input.toString());
        }
    }
}
