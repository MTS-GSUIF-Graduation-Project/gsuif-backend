package eg.mts.gsuif.service;

import eg.mts.gsuif.dto.CreatePageRequest;
import eg.mts.gsuif.dto.PageDto;
import eg.mts.gsuif.dto.PagedBody;
import eg.mts.gsuif.dto.UpdatePageRequest;
import eg.mts.gsuif.entity.GsuifPage;
import eg.mts.gsuif.entity.GsuifProject;
import eg.mts.gsuif.exception.DuplicateResourceException;
import eg.mts.gsuif.exception.PageDeletionException;
import eg.mts.gsuif.exception.ResourceNotFoundException;
import eg.mts.gsuif.repository.GsuifPageRepository;
import eg.mts.gsuif.repository.GsuifProjectRepository;
import eg.mts.gsuif.repository.MetadataVersionRepository;
import eg.mts.gsuif.service.impl.GsuifPageServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class GsuifPageServiceTest {

    @Mock
    private GsuifProjectRepository projectRepository;

    @Mock
    private GsuifPageRepository pageRepository;

    @Mock
    private MetadataVersionRepository metadataVersionRepository;

    private GsuifPageService pageService;

    private UUID projectId;
    private UUID pageId;

    @BeforeEach
    void setUp() {
        pageService = new GsuifPageServiceImpl(projectRepository, pageRepository, metadataVersionRepository);
        projectId = UUID.randomUUID();
        pageId = UUID.randomUUID();
    }

    // ── create ────────────────────────────────────────────────────────────────

    @Test
    void create_whenNameUnique_savesAndReturnsDto() {
        when(projectRepository.existsById(projectId)).thenReturn(true);
        when(pageRepository.existsByProjectIdAndName(projectId, "Home")).thenReturn(false);

        GsuifProject project = new GsuifProject();
        when(projectRepository.getReferenceById(projectId)).thenReturn(project);

        GsuifPage saved = buildPage(pageId, project, "Home", "/home");
        when(pageRepository.save(any(GsuifPage.class))).thenReturn(saved);

        PageDto result = pageService.create(projectId, new CreatePageRequest("Home", "/home"));

        assertThat(result.name()).isEqualTo("Home");
        assertThat(result.route()).isEqualTo("/home");
        verify(pageRepository).save(any(GsuifPage.class));
    }

    @Test
    void create_whenProjectNotFound_throwsResourceNotFoundException() {
        when(projectRepository.existsById(projectId)).thenReturn(false);

        assertThatThrownBy(() -> pageService.create(projectId, new CreatePageRequest("Home", null)))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("Project not found with id: " + projectId);

        verify(pageRepository, never()).save(any());
    }

    @Test
    void create_whenNameDuplicate_throwsDuplicateResourceExceptionWithFieldName() {
        when(projectRepository.existsById(projectId)).thenReturn(true);
        when(pageRepository.existsByProjectIdAndName(projectId, "Home")).thenReturn(true);

        assertThatThrownBy(() -> pageService.create(projectId, new CreatePageRequest("Home", null)))
                .isInstanceOf(DuplicateResourceException.class)
                .hasMessageContaining("Home")
                .extracting("fieldName").isEqualTo("name");

        verify(pageRepository, never()).save(any());
    }

    @Test
    void create_whenRouteDuplicateWithinProject_throwsDuplicateResourceExceptionWithFieldRoute() {
        when(projectRepository.existsById(projectId)).thenReturn(true);
        when(pageRepository.existsByProjectIdAndName(projectId, "Home")).thenReturn(false);
        when(pageRepository.existsByProjectIdAndRoute(projectId, "/home")).thenReturn(true);

        assertThatThrownBy(() -> pageService.create(projectId, new CreatePageRequest("Home", "/home")))
                .isInstanceOf(DuplicateResourceException.class)
                .hasMessageContaining("/home")
                .extracting("fieldName").isEqualTo("route");

        verify(pageRepository, never()).save(any());
    }

    @Test
    void create_whenRouteNull_doesNotCheckRouteUniqueness() {
        when(projectRepository.existsById(projectId)).thenReturn(true);
        when(pageRepository.existsByProjectIdAndName(projectId, "Home")).thenReturn(false);

        GsuifProject project = new GsuifProject();
        when(projectRepository.getReferenceById(projectId)).thenReturn(project);

        GsuifPage saved = buildPage(pageId, project, "Home", null);
        when(pageRepository.save(any(GsuifPage.class))).thenReturn(saved);

        pageService.create(projectId, new CreatePageRequest("Home", null));

        verify(pageRepository, never()).existsByProjectIdAndRoute(any(), any());
    }

    // ── getById ───────────────────────────────────────────────────────────────

    @Test
    void getById_whenExists_returnsDto() {
        when(projectRepository.existsById(projectId)).thenReturn(true);

        GsuifProject project = new GsuifProject();
        GsuifPage page = buildPage(pageId, project, "Contact", "/contact");
        when(pageRepository.findByIdAndProjectId(pageId, projectId)).thenReturn(Optional.of(page));

        PageDto result = pageService.getById(projectId, pageId);

        assertThat(result.name()).isEqualTo("Contact");
        assertThat(result.route()).isEqualTo("/contact");
    }

    @Test
    void getById_whenPageBelongsToDifferentProject_throwsResourceNotFoundException() {
        when(projectRepository.existsById(projectId)).thenReturn(true);
        when(pageRepository.findByIdAndProjectId(pageId, projectId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> pageService.getById(projectId, pageId))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("Page not found with id: " + pageId);
    }

    // ── getAll ────────────────────────────────────────────────────────────────

    @Test
    void getAll_returnsPagedBody() {
        when(projectRepository.existsById(projectId)).thenReturn(true);

        GsuifProject project = new GsuifProject();
        GsuifPage page = buildPage(pageId, project, "About", "/about");
        Pageable pageable = PageRequest.of(0, 10);

        when(pageRepository.findAllByProjectId(any(UUID.class), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(page), pageable, 1));

        PagedBody<PageDto> result = pageService.getAll(projectId, pageable);

        assertThat(result.totalElements()).isEqualTo(1);
        assertThat(result.data()).hasSize(1);
        assertThat(result.data().get(0).name()).isEqualTo("About");
    }

    // ── update ────────────────────────────────────────────────────────────────

    @Test
    void update_whenValid_updatesNameAndRoute() {
        when(projectRepository.existsById(projectId)).thenReturn(true);

        GsuifProject project = new GsuifProject();
        GsuifPage page = buildPage(pageId, project, "Old Name", "/old");
        when(pageRepository.findByIdAndProjectId(pageId, projectId)).thenReturn(Optional.of(page));
        when(pageRepository.existsByProjectIdAndNameAndIdNot(projectId, "New Name", pageId)).thenReturn(false);
        when(pageRepository.existsByProjectIdAndRouteAndIdNot(projectId, "/new", pageId)).thenReturn(false);
        when(pageRepository.save(page)).thenReturn(page);

        PageDto result = pageService.update(projectId, pageId, new UpdatePageRequest("New Name", "/new"));

        assertThat(result.name()).isEqualTo("New Name");
        assertThat(result.route()).isEqualTo("/new");
    }

    @Test
    void update_whenRouteSetToNull_clearsRoute() {
        when(projectRepository.existsById(projectId)).thenReturn(true);

        GsuifProject project = new GsuifProject();
        GsuifPage page = buildPage(pageId, project, "Page A", "/route-a");
        when(pageRepository.findByIdAndProjectId(pageId, projectId)).thenReturn(Optional.of(page));
        when(pageRepository.save(page)).thenReturn(page);

        pageService.update(projectId, pageId, new UpdatePageRequest("Page A", null));

        // route uniqueness check must NOT fire when route is null
        verify(pageRepository, never()).existsByProjectIdAndRouteAndIdNot(any(), any(), any());
        assertThat(page.getRoute()).isNull();
    }

    @Test
    void update_excludesCurrentPage_inNameCheck() {
        when(projectRepository.existsById(projectId)).thenReturn(true);

        GsuifProject project = new GsuifProject();
        GsuifPage page = buildPage(pageId, project, "Old", null);
        when(pageRepository.findByIdAndProjectId(pageId, projectId)).thenReturn(Optional.of(page));
        when(pageRepository.existsByProjectIdAndNameAndIdNot(projectId, "New", pageId)).thenReturn(false);
        when(pageRepository.save(page)).thenReturn(page);

        pageService.update(projectId, pageId, new UpdatePageRequest("New", null));

        verify(pageRepository).existsByProjectIdAndNameAndIdNot(projectId, "New", pageId);
    }

    @Test
    void update_excludesCurrentPage_inRouteCheck() {
        when(projectRepository.existsById(projectId)).thenReturn(true);

        GsuifProject project = new GsuifProject();
        GsuifPage page = buildPage(pageId, project, "Page", "/r");
        when(pageRepository.findByIdAndProjectId(pageId, projectId)).thenReturn(Optional.of(page));
        when(pageRepository.existsByProjectIdAndRouteAndIdNot(projectId, "/new-r", pageId)).thenReturn(false);
        when(pageRepository.save(page)).thenReturn(page);

        pageService.update(projectId, pageId, new UpdatePageRequest("Page", "/new-r"));

        verify(pageRepository).existsByProjectIdAndRouteAndIdNot(projectId, "/new-r", pageId);
    }

    @Test
    void update_whenNameDuplicate_throwsDuplicateResourceException() {
        when(projectRepository.existsById(projectId)).thenReturn(true);

        GsuifProject project = new GsuifProject();
        GsuifPage page = buildPage(pageId, project, "Old", null);
        when(pageRepository.findByIdAndProjectId(pageId, projectId)).thenReturn(Optional.of(page));
        when(pageRepository.existsByProjectIdAndNameAndIdNot(projectId, "Taken", pageId)).thenReturn(true);

        assertThatThrownBy(() -> pageService.update(projectId, pageId, new UpdatePageRequest("Taken", null)))
                .isInstanceOf(DuplicateResourceException.class)
                .extracting("fieldName").isEqualTo("name");

        verify(pageRepository, never()).save(any());
    }

    // ── delete ────────────────────────────────────────────────────────────────

    @Test
    void delete_whenNoMetadataVersions_deletesSuccessfully() {
        when(projectRepository.existsById(projectId)).thenReturn(true);

        GsuifProject project = new GsuifProject();
        GsuifPage page = buildPage(pageId, project, "Home", null);
        when(pageRepository.findByIdAndProjectId(pageId, projectId)).thenReturn(Optional.of(page));
        when(metadataVersionRepository.existsByPageId(pageId)).thenReturn(false);

        pageService.delete(projectId, pageId);

        verify(pageRepository).delete(page);
    }

    @Test
    void delete_whenMetadataVersionsExist_throwsPageDeletionException() {
        when(projectRepository.existsById(projectId)).thenReturn(true);

        GsuifProject project = new GsuifProject();
        GsuifPage page = buildPage(pageId, project, "Home", null);
        when(pageRepository.findByIdAndProjectId(pageId, projectId)).thenReturn(Optional.of(page));
        when(metadataVersionRepository.existsByPageId(pageId)).thenReturn(true);

        assertThatThrownBy(() -> pageService.delete(projectId, pageId))
                .isInstanceOf(PageDeletionException.class)
                .hasMessageContaining("metadata versions");

        verify(pageRepository, never()).delete(any());
    }

    @Test
    void delete_whenPageBelongsToDifferentProject_throwsResourceNotFoundException() {
        when(projectRepository.existsById(projectId)).thenReturn(true);
        when(pageRepository.findByIdAndProjectId(pageId, projectId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> pageService.delete(projectId, pageId))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("Page not found with id: " + pageId);

        verify(pageRepository, never()).delete(any());
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private GsuifPage buildPage(UUID id, GsuifProject project, String name, String route) {
        GsuifPage page = new GsuifPage();
        // Reflectively set the id via the protected setter inherited from entity pattern
        try {
            var method = GsuifPage.class.getDeclaredMethod("setId", UUID.class);
            method.setAccessible(true);
            method.invoke(page, id);
        } catch (Exception e) {
            throw new RuntimeException("Could not set page id", e);
        }
        page.setProject(project);
        page.setName(name);
        page.setRoute(route);
        return page;
    }
}
