package eg.mts.gsuif.service;

import tools.jackson.databind.node.JsonNodeFactory;
import eg.mts.gsuif.dto.CreateMetadataVersionRequest;
import eg.mts.gsuif.dto.MetadataVersionDto;
import eg.mts.gsuif.entity.GsuifPage;
import eg.mts.gsuif.entity.GsuifProject;
import eg.mts.gsuif.entity.MetadataVersion;
import eg.mts.gsuif.repository.GsuifPageRepository;
import eg.mts.gsuif.repository.GsuifProjectRepository;
import eg.mts.gsuif.repository.MetadataVersionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

@SpringBootTest
@ActiveProfiles("test")
class MetadataVersionIntegrationTest {

    @Autowired
    private MetadataVersionService metadataVersionService;

    @Autowired
    private MetadataVersionRepository metadataVersionRepository;

    @Autowired
    private GsuifPageRepository pageRepository;

    @Autowired
    private org.springframework.jdbc.core.JdbcTemplate jdbcTemplate;

    @Autowired
    private GsuifProjectRepository projectRepository;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @BeforeEach
    void setUp() {
        jdbcTemplate.execute("UPDATE gsuif_page SET current_metadata_version_id = NULL");
        metadataVersionRepository.deleteAll();
        pageRepository.deleteAll();
        projectRepository.deleteAll();
    }

    private GsuifPage savedPage(String name) {
        GsuifProject p = new GsuifProject();
        p.setName("Project for " + name);
        projectRepository.save(p);

        GsuifPage page = new GsuifPage();
        page.setProject(p);
        page.setName(name);
        return pageRepository.save(page);
    }

    @Test
    void testSamePageConcurrency() throws Exception {
        GsuifPage page = savedPage("Concurrent Page");

        int threadCount = 5;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        try {
            CountDownLatch readyLatch = new CountDownLatch(threadCount);
            CountDownLatch startLatch = new CountDownLatch(1);
            List<Future<MetadataVersionDto>> futures = new ArrayList<>();
            
            for (int i = 0; i < threadCount; i++) {
                futures.add(executor.submit(() -> {
                    readyLatch.countDown();
                    startLatch.await();
                    CreateMetadataVersionRequest request = new CreateMetadataVersionRequest("1.0", JsonNodeFactory.instance.objectNode());
                    return metadataVersionService.create(page.getId(), request);
                }));
            }

            readyLatch.await();
            startLatch.countDown();
            
            List<Integer> versions = new ArrayList<>();
            for (Future<MetadataVersionDto> future : futures) {
                versions.add(future.get().version());
            }

            assertThat(versions).hasSize(5);
            assertThat(versions).containsExactlyInAnyOrder(1, 2, 3, 4, 5);
            
            GsuifPage updatedPage = pageRepository.findById(page.getId()).orElseThrow();
            MetadataVersion highestVersion = metadataVersionRepository.findFirstByPageIdOrderByVersionDesc(page.getId()).orElseThrow();

            assertThat(updatedPage.getCurrentMetadataVersionId()).isEqualTo(highestVersion.getId());

            List<MetadataVersionDto> listedVersions = metadataVersionService
                    .getAll(page.getId(), org.springframework.data.domain.Pageable.unpaged())
                    .data();
            List<MetadataVersionDto> currentVersions = listedVersions.stream()
                    .filter(MetadataVersionDto::isCurrent)
                    .toList();
            assertThat(currentVersions).hasSize(1);
            assertThat(currentVersions.getFirst().id()).isEqualTo(highestVersion.getId());
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void testDifferentPageConcurrency() throws Exception {
        GsuifPage page1 = savedPage("Page 1");
        GsuifPage page2 = savedPage("Page 2");

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch page1Locked = new CountDownLatch(1);
        CountDownLatch releasePage1 = new CountDownLatch(1);
        try {
            Future<?> heldPage1Lock = executor.submit(() -> {
                TransactionTemplate txTemplate = new TransactionTemplate(transactionManager);
                txTemplate.executeWithoutResult(status -> {
                    pageRepository.findByIdWithLock(page1.getId()).orElseThrow();
                    page1Locked.countDown();
                    try {
                        if (!releasePage1.await(15, TimeUnit.SECONDS)) {
                            throw new IllegalStateException("Timed out waiting to release Page 1 lock");
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException("Interrupted while holding Page 1 lock", e);
                    }
                });
            });

            assertThat(page1Locked.await(5, TimeUnit.SECONDS)).isTrue();

            Future<MetadataVersionDto> page2Creation = executor.submit(() ->
                    metadataVersionService.create(page2.getId(),
                            new CreateMetadataVersionRequest("1.0", JsonNodeFactory.instance.objectNode())));

            MetadataVersionDto createdForPage2 = page2Creation.get(5, TimeUnit.SECONDS);
            assertThat(createdForPage2.version()).isEqualTo(1);

            releasePage1.countDown();
            heldPage1Lock.get(5, TimeUnit.SECONDS);
        } finally {
            releasePage1.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void testCrossPageIntegrity() {
        GsuifPage page1 = savedPage("Page 1");
        GsuifPage page2 = savedPage("Page 2");

        MetadataVersionDto mv1 = metadataVersionService.create(page1.getId(), new CreateMetadataVersionRequest("1.0", JsonNodeFactory.instance.objectNode()));

        page2.setCurrentMetadataVersionId(mv1.id());
        
        assertThrows(DataIntegrityViolationException.class, () -> {
            pageRepository.saveAndFlush(page2);
        });
    }

    @Test
    void testPageDeletion_failsWhenMetadataVersionsExist() {
        GsuifPage page = savedPage("Page To Delete");
        metadataVersionService.create(page.getId(), new CreateMetadataVersionRequest("1.0", JsonNodeFactory.instance.objectNode()));
        
        assertThat(metadataVersionRepository.findAll()).hasSize(1);
        
        assertThrows(DataIntegrityViolationException.class, () -> {
            pageRepository.delete(page);
            pageRepository.flush();
        });
    }

    @Test
    void testTransactionRollback() {
        GsuifPage page = savedPage("Rollback Page");
        MetadataVersionDto original = metadataVersionService.create(
                page.getId(),
                new CreateMetadataVersionRequest("1.0", JsonNodeFactory.instance.objectNode()));

        TransactionTemplate txTemplate = new TransactionTemplate(transactionManager);
        txTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);

        RuntimeException failure = assertThrows(RuntimeException.class, () ->
                txTemplate.execute(status -> {
                    metadataVersionService.create(
                            page.getId(),
                            new CreateMetadataVersionRequest("2.0", JsonNodeFactory.instance.objectNode()));
                    throw new RuntimeException("Force rollback");
                }));
        assertThat(failure.getMessage()).isEqualTo("Force rollback");

        List<MetadataVersion> remainingVersions = metadataVersionRepository
                .findAllByPageId(page.getId(), org.springframework.data.domain.Pageable.unpaged())
                .getContent();
        assertThat(remainingVersions).hasSize(1);
        assertThat(remainingVersions.getFirst().getId()).isEqualTo(original.id());

        GsuifPage reloadedPage = pageRepository.findById(page.getId()).orElseThrow();
        assertThat(reloadedPage.getCurrentMetadataVersionId()).isEqualTo(original.id());
    }
}
