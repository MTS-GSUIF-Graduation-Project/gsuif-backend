package eg.mts.gsuif.service;


import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.JsonNodeFactory;
import eg.mts.gsuif.dto.CreateMetadataVersionRequest;
import eg.mts.gsuif.dto.MetadataVersionDto;
import eg.mts.gsuif.dto.PagedBody;
import eg.mts.gsuif.entity.GsuifPage;
import eg.mts.gsuif.entity.GsuifProject;
import eg.mts.gsuif.entity.MetadataVersion;
import eg.mts.gsuif.exception.ResourceNotFoundException;
import eg.mts.gsuif.repository.GsuifPageRepository;
import eg.mts.gsuif.repository.MetadataVersionRepository;
import eg.mts.gsuif.service.impl.MetadataVersionServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MetadataVersionServiceTest {

    @Mock
    private MetadataVersionRepository metadataVersionRepository;
    @Mock
    private GsuifPageRepository pageRepository;
    @Mock
    private ObjectMapper objectMapper;
    @Mock
    private eg.mts.gsuif.validator.MetadataSchemaValidator schemaValidator;

    private MetadataVersionServiceImpl metadataVersionService;

    private final UUID projectId = UUID.randomUUID();
    private final UUID pageId = UUID.randomUUID();
    private GsuifPage page;

    @BeforeEach
    void setUp() {
        metadataVersionService = new MetadataVersionServiceImpl(metadataVersionRepository, pageRepository, objectMapper, schemaValidator);

        GsuifProject project = new GsuifProject();
        org.springframework.test.util.ReflectionTestUtils.setField(project, "id", projectId);
        
        page = new GsuifPage();
        org.springframework.test.util.ReflectionTestUtils.setField(page, "id", pageId);
        page.setProject(project);
    }

    @Test
    void create_whenFirstVersion_assignsVersionOneAndUpdatesPagePointer() throws Exception {
        when(pageRepository.findByIdWithLock(pageId)).thenReturn(Optional.of(page));
        when(metadataVersionRepository.findFirstByPageIdOrderByVersionDesc(pageId)).thenReturn(Optional.empty());
        when(objectMapper.writeValueAsString(any())).thenReturn("{\"test\":1}");

        MetadataVersion savedVersion = new MetadataVersion();
        UUID newVersionId = UUID.randomUUID();
        org.springframework.test.util.ReflectionTestUtils.setField(savedVersion, "id", newVersionId);
        savedVersion.setPage(page);
        savedVersion.setVersion(1);
        savedVersion.setSchemaVersion("1.0");
        savedVersion.setSnapshot("{\"test\":1}");
        savedVersion.setCreatedAt(Instant.now());
        savedVersion.setCreatedBy("user");
        
        when(metadataVersionRepository.save(any(MetadataVersion.class))).thenReturn(savedVersion);
        when(objectMapper.readTree("{\"test\":1}")).thenReturn(JsonNodeFactory.instance.objectNode().put("test", 1));

        CreateMetadataVersionRequest request = new CreateMetadataVersionRequest("1.0", JsonNodeFactory.instance.objectNode().put("test", 1));
        MetadataVersionDto result = metadataVersionService.create(pageId, request);

        assertThat(result.version()).isEqualTo(1);
        assertThat(result.isCurrent()).isTrue();
        assertThat(result.projectId()).isEqualTo(projectId);
        
        ArgumentCaptor<MetadataVersion> captor = ArgumentCaptor.forClass(MetadataVersion.class);
        verify(metadataVersionRepository).save(captor.capture());
        
        MetadataVersion captured = captor.getValue();
        assertThat(captured.getVersion()).isEqualTo(1);
        assertThat(page.getCurrentMetadataVersionId()).isEqualTo(newVersionId);
        verify(pageRepository).save(page);
    }

    @Test
    void create_whenPreviousVersionsExist_incrementsVersionAndUpdatesPointer() throws Exception {
        when(pageRepository.findByIdWithLock(pageId)).thenReturn(Optional.of(page));
        
        MetadataVersion previous = new MetadataVersion();
        UUID oldId = UUID.randomUUID();
        org.springframework.test.util.ReflectionTestUtils.setField(previous, "id", oldId);
        previous.setVersion(3);
        previous.setSchemaVersion("old");
        previous.setSnapshot("{}");
        page.setCurrentMetadataVersionId(oldId);
        
        when(metadataVersionRepository.findFirstByPageIdOrderByVersionDesc(pageId)).thenReturn(Optional.of(previous));
        when(objectMapper.writeValueAsString(any())).thenReturn("{}");

        MetadataVersion savedVersion = new MetadataVersion();
        UUID newId = UUID.randomUUID();
        org.springframework.test.util.ReflectionTestUtils.setField(savedVersion, "id", newId);
        savedVersion.setPage(page);
        savedVersion.setVersion(4);
        savedVersion.setSchemaVersion("1.0");
        savedVersion.setSnapshot("{}");
        
        ArgumentCaptor<MetadataVersion> captor = ArgumentCaptor.forClass(MetadataVersion.class);
        when(metadataVersionRepository.save(any(MetadataVersion.class))).thenReturn(savedVersion);
        when(objectMapper.readTree("{}")).thenReturn(JsonNodeFactory.instance.objectNode());

        CreateMetadataVersionRequest request = new CreateMetadataVersionRequest("1.0", JsonNodeFactory.instance.objectNode());
        MetadataVersionDto result = metadataVersionService.create(pageId, request);

        verify(metadataVersionRepository).save(captor.capture());
        MetadataVersion insertedNew = captor.getValue();
        
        assertThat(insertedNew.getVersion()).isEqualTo(4);
        assertThat(page.getCurrentMetadataVersionId()).isEqualTo(newId);
        assertThat(result.isCurrent()).isTrue();
        verify(pageRepository).save(page);
    }

    @Test
    void getLatest_whenVersionsExist_returnsHighestVersion() throws Exception {
        when(pageRepository.existsById(pageId)).thenReturn(true);
        MetadataVersion latest = new MetadataVersion();
        UUID latestId = UUID.randomUUID();
        org.springframework.test.util.ReflectionTestUtils.setField(latest, "id", latestId);
        latest.setPage(page);
        latest.setVersion(5);
        latest.setSnapshot("{}");
        page.setCurrentMetadataVersionId(latestId);
        when(metadataVersionRepository.findFirstByPageIdOrderByVersionDesc(pageId)).thenReturn(Optional.of(latest));
        when(objectMapper.readTree("{}")).thenReturn(JsonNodeFactory.instance.objectNode());

        MetadataVersionDto result = metadataVersionService.getLatest(pageId);
        assertThat(result.version()).isEqualTo(5);
    }

    @Test
    void getLatest_whenNoVersions_throwsNotFound() {
        when(pageRepository.existsById(pageId)).thenReturn(true);
        when(metadataVersionRepository.findFirstByPageIdOrderByVersionDesc(pageId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> metadataVersionService.getLatest(pageId))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("No metadata versions found");
    }

    @Test
    void getAll_whenEmpty_returnsEmptyPage() {
        when(pageRepository.existsById(pageId)).thenReturn(true);
        Page<MetadataVersion> emptyPage = new PageImpl<>(List.of());
        when(metadataVersionRepository.findAllByPageId(any(), any())).thenReturn(emptyPage);

        PagedBody<MetadataVersionDto> result = metadataVersionService.getAll(pageId, PageRequest.of(0, 20));
        assertThat(result.totalElements()).isZero();
    }

    @Test
    void create_preservesIncomingJsonValueDuringJackson2Conversion() {
        when(pageRepository.findByIdWithLock(pageId)).thenReturn(Optional.of(page));
        when(metadataVersionRepository.findFirstByPageIdOrderByVersionDesc(pageId)).thenReturn(Optional.empty());

        MetadataVersion savedVersion = new MetadataVersion();
        savedVersion.setPage(page);
        org.springframework.test.util.ReflectionTestUtils.setField(savedVersion, "id", UUID.randomUUID());
        when(metadataVersionRepository.save(any())).thenReturn(savedVersion);

        tools.jackson.databind.node.ObjectNode incomingSnapshot = JsonNodeFactory.instance.objectNode();
        incomingSnapshot.put("width", new java.math.BigDecimal("0.9999999999999999999999999999"));

        try {
            when(objectMapper.writeValueAsString(incomingSnapshot)).thenReturn(incomingSnapshot.toString());
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        CreateMetadataVersionRequest request = new CreateMetadataVersionRequest("1.0.0", incomingSnapshot);

        metadataVersionService.create(pageId, request);

        ArgumentCaptor<com.fasterxml.jackson.databind.JsonNode> captor = ArgumentCaptor.forClass(com.fasterxml.jackson.databind.JsonNode.class);
        verify(schemaValidator).validate(any(), captor.capture());

        com.fasterxml.jackson.databind.JsonNode convertedNode = captor.getValue();
        assertThat(convertedNode.get("width").decimalValue()).isEqualByComparingTo("0.9999999999999999999999999999");
        assertThat(convertedNode.toString()).isEqualTo(incomingSnapshot.toString());
    }

    @Test
    void create_whenJacksonConversionFails_throwsIllegalStateExceptionRetainingCause() {
        tools.jackson.databind.JsonNode badNode = org.mockito.Mockito.mock(tools.jackson.databind.JsonNode.class);
        RuntimeException cause = new RuntimeException("Simulated conversion failure");
        try {
            when(objectMapper.writeValueAsString(badNode)).thenThrow(cause);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        CreateMetadataVersionRequest request = new CreateMetadataVersionRequest("1.0.0", badNode);

        assertThatThrownBy(() -> metadataVersionService.create(pageId, request))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Failed to convert snapshot node")
                .hasCause(cause);
    }

    @Test
    void create_whenValidatorReturnsNull_failsBeforeRepositoryAccess() {
        when(schemaValidator.validate("1.0.0", null)).thenReturn(null);

        assertThatThrownBy(() -> metadataVersionService.create(pageId,
                new CreateMetadataVersionRequest("1.0.0", null)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Validator unexpectedly returned null");
        org.mockito.Mockito.verifyNoInteractions(metadataVersionRepository, pageRepository);
    }
}
