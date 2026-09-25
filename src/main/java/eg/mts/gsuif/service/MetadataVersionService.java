package eg.mts.gsuif.service;

import eg.mts.gsuif.dto.CreateMetadataVersionRequest;
import eg.mts.gsuif.dto.MetadataVersionDto;
import eg.mts.gsuif.dto.PagedBody;
import org.springframework.data.domain.Pageable;

import java.util.UUID;

/**
 * Service interface for managing {@link eg.mts.gsuif.entity.MetadataVersion} operations.
 */
public interface MetadataVersionService {

    MetadataVersionDto create(UUID pageId, CreateMetadataVersionRequest request);

    MetadataVersionDto getLatest(UUID pageId);

    MetadataVersionDto getById(UUID pageId, UUID versionId);

    PagedBody<MetadataVersionDto> getAll(UUID pageId, Pageable pageable);
}
