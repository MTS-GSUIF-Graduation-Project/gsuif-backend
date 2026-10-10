package eg.mts.gsuif.service;

import eg.mts.gsuif.dto.CreateProjectRequest;
import eg.mts.gsuif.dto.PagedBody;
import eg.mts.gsuif.dto.ProjectDto;
import eg.mts.gsuif.dto.UpdateProjectRequest;
import eg.mts.gsuif.entity.GsuifProject;
import eg.mts.gsuif.exception.DuplicateResourceException;
import eg.mts.gsuif.exception.ProjectDeletionException;
import eg.mts.gsuif.exception.ResourceNotFoundException;
import eg.mts.gsuif.repository.GsuifPageRepository;
import eg.mts.gsuif.repository.GsuifProjectRepository;
import eg.mts.gsuif.service.impl.GsuifProjectServiceImpl;
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
class GsuifProjectServiceTest {

    @Mock
    private GsuifProjectRepository projectRepository;

    @Mock
    private GsuifPageRepository pageRepository;

    private GsuifProjectService projectService;

    @BeforeEach
    void setUp() {
        projectService = new GsuifProjectServiceImpl(projectRepository, pageRepository);
    }

    @Test
    void create_whenNameUnique_savesAndReturnsDto() {
        CreateProjectRequest request = new CreateProjectRequest("   GSUIF Platform   ", "   Core Backend   ");
        GsuifProject entity = new GsuifProject();
        entity.setName("GSUIF Platform");
        entity.setDescription("Core Backend");

        when(projectRepository.existsByName("GSUIF Platform")).thenReturn(false);
        when(projectRepository.save(any(GsuifProject.class))).thenReturn(entity);

        ProjectDto result = projectService.create(request);

        assertThat(result.name()).isEqualTo("GSUIF Platform");
        assertThat(result.description()).isEqualTo("Core Backend");
        verify(projectRepository).save(any(GsuifProject.class));
    }

    @Test
    void create_whenNameExists_throwsDuplicateResourceExceptionWithFieldName() {
        CreateProjectRequest request = new CreateProjectRequest("Duplicate Project", "Description");
        when(projectRepository.existsByName("Duplicate Project")).thenReturn(true);

        assertThatThrownBy(() -> projectService.create(request))
                .isInstanceOf(DuplicateResourceException.class)
                .hasMessageContaining("Project with name 'Duplicate Project' already exists")
                .extracting("fieldName")
                .isEqualTo("name");

        verify(projectRepository, never()).save(any());
    }

    @Test
    void getById_whenExists_returnsDto() {
        UUID id = UUID.randomUUID();
        GsuifProject entity = new GsuifProject();
        entity.setName("Sample Project");

        when(projectRepository.findById(id)).thenReturn(Optional.of(entity));

        ProjectDto result = projectService.getById(id);

        assertThat(result.name()).isEqualTo("Sample Project");
    }

    @Test
    void getById_whenNotExists_throwsResourceNotFoundException() {
        UUID id = UUID.randomUUID();
        when(projectRepository.findById(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> projectService.getById(id))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("Project not found with id: " + id);
    }

    @Test
    void getAll_returnsPagedBody() {
        Pageable pageable = PageRequest.of(0, 10);
        GsuifProject entity = new GsuifProject();
        entity.setName("P1");

        when(projectRepository.findAll(any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(entity), pageable, 1));

        PagedBody<ProjectDto> result = projectService.getAll(pageable);

        assertThat(result.totalElements()).isEqualTo(1);
        assertThat(result.data()).hasSize(1);
        assertThat(result.data().get(0).name()).isEqualTo("P1");
    }

    @Test
    void update_whenValid_updatesAndReturnsDto() {
        UUID id = UUID.randomUUID();
        GsuifProject entity = new GsuifProject();
        entity.setName("Old Name");

        UpdateProjectRequest request = new UpdateProjectRequest("New Name", "New Desc");

        when(projectRepository.findById(id)).thenReturn(Optional.of(entity));
        when(projectRepository.existsByNameAndIdNot("New Name", id)).thenReturn(false);
        when(projectRepository.save(entity)).thenReturn(entity);

        ProjectDto result = projectService.update(id, request);

        assertThat(result.name()).isEqualTo("New Name");
        assertThat(result.description()).isEqualTo("New Desc");
    }

    @Test
    void delete_whenNoPages_deletesSuccessfully() {
        UUID id = UUID.randomUUID();
        when(projectRepository.existsById(id)).thenReturn(true);
        when(pageRepository.existsByProjectId(id)).thenReturn(false);

        projectService.delete(id);

        verify(projectRepository).deleteById(id);
    }

    @Test
    void delete_whenPagesExist_throwsProjectDeletionException() {
        UUID id = UUID.randomUUID();
        when(projectRepository.existsById(id)).thenReturn(true);
        when(pageRepository.existsByProjectId(id)).thenReturn(true);

        assertThatThrownBy(() -> projectService.delete(id))
                .isInstanceOf(ProjectDeletionException.class)
                .hasMessageContaining("contains existing pages");

        verify(projectRepository, never()).deleteById(any());
    }
}
