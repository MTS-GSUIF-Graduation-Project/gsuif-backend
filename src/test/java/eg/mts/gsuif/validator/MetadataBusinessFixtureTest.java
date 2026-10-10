package eg.mts.gsuif.validator;

import eg.mts.gsuif.repository.GsuifPageRepository;
import org.junit.jupiter.api.Test;
import java.util.UUID;
import static eg.mts.gsuif.validator.BusinessRuleTestSupport.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class MetadataBusinessFixtureTest {
    @Test void replayMinimalDuplicateComponent() throws Exception {
        try (var stream = getClass().getResourceAsStream("/metadata/business-rules/duplicate-component.json")) {
            assertThat(stream).isNotNull();
            var snapshot = JSON.readTree(stream);
            assertThat(new MetadataSchemaValidator().validate("1.0.0", snapshot)).isEmpty();
            try (var c = context(mock(GsuifPageRepository.class))) {
                c.refresh();
                rejects(rule(c, COMPONENT), snapshotContext(snapshot), COMPONENT, "$.snapshot.components[1].id");
            }
        }
    }

    @Test void replayMinimalRouteContext() throws Exception {
        try (var stream = getClass().getResourceAsStream("/metadata/business-rules/route-context.json")) {
            assertThat(stream).isNotNull();
            var fixture = JSON.readTree(stream);
            UUID project = UUID.fromString(fixture.get("project").asText());
            String route = fixture.get("route").asText();
            var repo = mock(GsuifPageRepository.class);
            when(repo.existsByProjectIdAndRoute(project, route)).thenReturn(fixture.get("existingInProject").asBoolean());
            try (var c = context(repo)) {
                c.refresh();
                rejects(rule(c, ROUTE), pageContext(project, null, route), ROUTE, "route");
            }
        }
    }

    @Test void emptyCollectionsAndNullRouteAreAccepted() throws Exception {
        try (var c = context(mock(GsuifPageRepository.class))) {
            c.refresh();
            Object validator = c.getBean(type("MetadataBusinessValidator"));
            assertThat(errors(validator, snapshotContext(JSON.readTree("{\"components\":[],\"apiBindings\":[]}")))).isEmpty();
            assertThat(errors(validator, pageContext(PROJECT, null, null))).isEmpty();
        }
    }
}
