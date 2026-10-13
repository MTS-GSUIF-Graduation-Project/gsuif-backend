package eg.mts.gsuif.validator;

import eg.mts.gsuif.dto.*;
import eg.mts.gsuif.entity.*;
import eg.mts.gsuif.exception.*;
import eg.mts.gsuif.repository.*;
import eg.mts.gsuif.service.impl.GsuifPageServiceImpl;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.util.ReflectionTestUtils;
import java.sql.SQLException;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import static eg.mts.gsuif.validator.BusinessRuleTestSupport.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.any;

class RouteBusinessServiceTest {
    @Test void duplicateCreateAndUpdateDoNotSaveOrMutatePage() {
        var pages = mock(GsuifPageRepository.class);
        var projects = mock(GsuifProjectRepository.class);
        var versions = mock(MetadataVersionRepository.class);
        when(projects.existsById(PROJECT)).thenReturn(true);
        var page = new GsuifPage();
        page.setName("Existing"); page.setRoute("/old");
        ReflectionTestUtils.setField(page, "id", PAGE);
        when(pages.findByIdAndProjectId(PAGE, PROJECT)).thenReturn(Optional.of(page));
        when(pages.existsByProjectIdAndRoute(PROJECT, "/taken")).thenReturn(true);
        when(pages.existsByProjectIdAndRouteAndIdNot(PROJECT, "/taken", PAGE)).thenReturn(true);
        try (var c = context(pages)) {
            c.registerBean(GsuifProjectRepository.class, () -> projects);
            c.registerBean(MetadataVersionRepository.class, () -> versions);
            c.registerBean(GsuifPageServiceImpl.class);
            c.refresh();
            var service = c.getBean(GsuifPageServiceImpl.class);
            Throwable create = catchThrowable(() -> service.create(PROJECT, new CreatePageRequest("New", "/taken")));
            assertThat(errorMap(create).get("route")).contains(ROUTE);
            Throwable update = catchThrowable(() -> service.update(PROJECT, PAGE, new UpdatePageRequest("Changed", "/taken")));
            assertThat(errorMap(update).get("route")).contains(ROUTE);
            verify(pages, never()).save(any());
            assertThat(page.getName()).isEqualTo("Existing");
            assertThat(page.getRoute()).isEqualTo("/old");
            verifyNoInteractions(versions);
        }
    }

    @Test void pageServiceUsesDiscoveredRulesBeforeSaving() {
        var pages = mock(GsuifPageRepository.class);
        var projects = mock(GsuifProjectRepository.class);
        var versions = mock(MetadataVersionRepository.class);
        when(projects.existsById(PROJECT)).thenReturn(true);
        AtomicInteger calls = new AtomicInteger();
        try (var c = context(pages)) {
            c.registerBean(GsuifProjectRepository.class, () -> projects);
            c.registerBean(MetadataVersionRepository.class, () -> versions);
            c.registerBean(GsuifPageServiceImpl.class);
            MetadataBusinessDiscoveryTest.register(c, "additionalPageRule", "AdditionalPageRule", true, calls);
            c.refresh();
            Throwable failure = catchThrowable(() -> c.getBean(GsuifPageServiceImpl.class)
                    .create(PROJECT, new CreatePageRequest("New", null)));
            assertThat(errorMap(failure).values()).anySatisfy(message -> assertThat(message).contains("AdditionalPageRule"));
            assertThat(calls).hasValue(1);
            verify(pages, never()).save(any());
        }
    }

    @Test void routeDatabaseRaceKeeps400AndNamesRouteRuleWhileUnknownConstraintRemainsGeneric() {
        var handler = new GlobalExceptionHandler();
        var route = handler.handleDataIntegrityViolation(constraint("uk_gsuif_page_project_id_route"));
        assertThat(route.getStatusCode().value()).isEqualTo(400);
        assertThat(route.getBody()).isNotNull();
        assertThat(route.getBody().errors().get("route")).contains(ROUTE);
        assertThat(route.getBody().body()).isNull();
        var unknown = handler.handleDataIntegrityViolation(constraint("unrelated_constraint"));
        assertThat(unknown.getStatusCode().value()).isEqualTo(400);
        assertThat(unknown.getBody()).isNotNull();
        assertThat(unknown.getBody().errors()).isNull();
        assertThat(unknown.getBody().clientMessage()).doesNotContain(ROUTE);
    }

    private DataIntegrityViolationException constraint(String name) {
        return new DataIntegrityViolationException("race", new org.hibernate.exception.ConstraintViolationException(
                "duplicate", new SQLException("duplicate", "23505"), name));
    }
    private Map<String, String> errorMap(Throwable failure) {
        assertThat(failure).isNotNull();
        if (failure instanceof MetadataValidationException metadata) return metadata.getErrors();
        assertThat(failure).isInstanceOf(DuplicateResourceException.class);
        var duplicate = (DuplicateResourceException) failure;
        return Map.of(duplicate.getFieldName(), duplicate.getMessage());
    }
}
