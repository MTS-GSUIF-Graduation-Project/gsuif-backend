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
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

@SpringBootTest(properties = "gsuif.security.mode=dev")
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

    private tools.jackson.databind.JsonNode validSnapshot(String label) {
        String json = """
            {
                "components": [
                    {
                        "id": "123e4567-e89b-12d3-a456-426614174000",
                        "type": "text-field",
                        "label": "%s",
                        "position": { "row": 0, "col": 0 },
                        "size": { "width": 6, "height": 1 },
                        "visibility": true,
                        "disabled": false
                    }
                ],
                "apiBindings": []
            }
        """.formatted(label);
        try {
            return new tools.jackson.databind.ObjectMapper().readTree(json);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
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
                    CreateMetadataVersionRequest request = new CreateMetadataVersionRequest("1.0.0", validSnapshot("test"));
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
                            new CreateMetadataVersionRequest("1.0.0", validSnapshot("test2"))));

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
    void selectionWaitsForPageLockWhileOtherPageCanBeSelected() throws Exception {
        GsuifPage page1 = savedPage("Locked selection page");
        GsuifPage page2 = savedPage("Independent selection page");
        MetadataVersionDto first = metadataVersionService.create(page1.getId(),
                new CreateMetadataVersionRequest("1.0.0", validSnapshot("first")));
        metadataVersionService.create(page1.getId(),
                new CreateMetadataVersionRequest("1.0.0", validSnapshot("second")));
        MetadataVersionDto other = metadataVersionService.create(page2.getId(),
                new CreateMetadataVersionRequest("1.0.0", validSnapshot("other")));

        ExecutorService executor = Executors.newFixedThreadPool(3);
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch selectionStarted = new CountDownLatch(1);
        try {
            Future<?> lockHolder = executor.submit(() -> {
                new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                    pageRepository.findByIdWithLock(page1.getId()).orElseThrow();
                    locked.countDown();
                    try {
                        if (!release.await(10, TimeUnit.SECONDS)) {
                            throw new IllegalStateException("Timed out holding selection lock");
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException(e);
                    }
                });
            });
            assertThat(locked.await(5, TimeUnit.SECONDS)).isTrue();
            Future<MetadataVersionDto> selected = executor.submit(() -> {
                selectionStarted.countDown();
                return metadataVersionService.selectCurrent(page1.getId(), first.id());
            });
            assertThat(selectionStarted.await(5, TimeUnit.SECONDS)).isTrue();
            assertThrows(TimeoutException.class, () -> selected.get(200, TimeUnit.MILLISECONDS));
            Future<MetadataVersionDto> independent = executor.submit(() ->
                    metadataVersionService.selectCurrent(page2.getId(), other.id()));
            assertThat(independent.get(5, TimeUnit.SECONDS).id()).isEqualTo(other.id());
            release.countDown();
            lockHolder.get(5, TimeUnit.SECONDS);
            assertThat(selected.get(5, TimeUnit.SECONDS).id()).isEqualTo(first.id());
            assertThat(pageRepository.findById(page1.getId()).orElseThrow().getCurrentMetadataVersionId())
                    .isEqualTo(first.id());
            assertThat(metadataVersionRepository.findAllByPageId(page1.getId(),
                    org.springframework.data.domain.Pageable.unpaged()).getContent()).hasSize(2);
        } finally {
            release.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void concurrentSelectionsCommitInPageLockOrder() throws Exception {
        GsuifPage page = savedPage("Concurrent selections");
        MetadataVersionDto first = metadataVersionService.create(page.getId(),
                new CreateMetadataVersionRequest("1.0.0", validSnapshot("first")));
        MetadataVersionDto second = metadataVersionService.create(page.getId(),
                new CreateMetadataVersionRequest("1.0.0", validSnapshot("second")));
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch firstSelected = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        CountDownLatch secondStarted = new CountDownLatch(1);
        try {
            Future<?> firstTransaction = executor.submit(() ->
                    new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                        metadataVersionService.selectCurrent(page.getId(), first.id());
                        firstSelected.countDown();
                        try {
                            if (!releaseFirst.await(10, TimeUnit.SECONDS)) {
                                throw new IllegalStateException("Timed out holding first selection");
                            }
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            throw new IllegalStateException(e);
                        }
                    }));
            assertThat(firstSelected.await(5, TimeUnit.SECONDS)).isTrue();
            Future<MetadataVersionDto> secondSelection = executor.submit(() -> {
                secondStarted.countDown();
                return metadataVersionService.selectCurrent(page.getId(), second.id());
            });
            assertThat(secondStarted.await(5, TimeUnit.SECONDS)).isTrue();
            assertThrows(TimeoutException.class, () -> secondSelection.get(200, TimeUnit.MILLISECONDS));
            releaseFirst.countDown();
            firstTransaction.get(5, TimeUnit.SECONDS);
            assertThat(secondSelection.get(5, TimeUnit.SECONDS).id()).isEqualTo(second.id());
            assertThat(metadataVersionService.getCurrent(page.getId()).id()).isEqualTo(second.id());
            assertThat(metadataVersionRepository.findById(first.id()).orElseThrow().getVersion()).isEqualTo(1);
        } finally {
            releaseFirst.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void saveWaitsForSelectionTransactionAndThenSelectsNewVersion() throws Exception {
        GsuifPage page = savedPage("Selection versus save");
        MetadataVersionDto first = metadataVersionService.create(page.getId(),
                new CreateMetadataVersionRequest("1.0.0", validSnapshot("first")));
        metadataVersionService.create(page.getId(),
                new CreateMetadataVersionRequest("1.0.0", validSnapshot("second")));
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch selected = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch saveStarted = new CountDownLatch(1);
        try {
            Future<?> selection = executor.submit(() ->
                    new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                        metadataVersionService.selectCurrent(page.getId(), first.id());
                        selected.countDown();
                        try {
                            if (!release.await(10, TimeUnit.SECONDS)) {
                                throw new IllegalStateException("Timed out holding selection transaction");
                            }
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            throw new IllegalStateException(e);
                        }
                    }));
            assertThat(selected.await(5, TimeUnit.SECONDS)).isTrue();
            Future<MetadataVersionDto> saved = executor.submit(() -> {
                saveStarted.countDown();
                return metadataVersionService.create(page.getId(),
                        new CreateMetadataVersionRequest("1.0.0", validSnapshot("third")));
            });
            assertThat(saveStarted.await(5, TimeUnit.SECONDS)).isTrue();
            assertThrows(TimeoutException.class, () -> saved.get(200, TimeUnit.MILLISECONDS));
            release.countDown();
            selection.get(5, TimeUnit.SECONDS);
            MetadataVersionDto third = saved.get(5, TimeUnit.SECONDS);
            assertThat(third.version()).isEqualTo(3);
            assertThat(metadataVersionService.getCurrent(page.getId()).id()).isEqualTo(third.id());
            assertThat(metadataVersionService.getLatest(page.getId()).id()).isEqualTo(third.id());
            assertThat(metadataVersionRepository.findById(first.id()).orElseThrow().getVersion()).isEqualTo(1);
        } finally {
            release.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void testCrossPageIntegrity() {
        GsuifPage page1 = savedPage("Page 1");
        GsuifPage page2 = savedPage("Page 2");

        MetadataVersionDto mv1 = metadataVersionService.create(page1.getId(), new CreateMetadataVersionRequest("1.0.0", validSnapshot("test")));

        page2.setCurrentMetadataVersionId(mv1.id());
        
        assertThrows(DataIntegrityViolationException.class, () -> {
            pageRepository.saveAndFlush(page2);
        });
    }

    @Test
    void testPageDeletion_failsWhenMetadataVersionsExist() {
        GsuifPage page = savedPage("Page To Delete");
        metadataVersionService.create(page.getId(), new CreateMetadataVersionRequest("1.0.0", validSnapshot("test")));
        
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
                new CreateMetadataVersionRequest("1.0.0", validSnapshot("test1")));

        TransactionTemplate txTemplate = new TransactionTemplate(transactionManager);
        txTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);

        RuntimeException failure = assertThrows(RuntimeException.class, () ->
                txTemplate.execute(status -> {
                    metadataVersionService.create(
                            page.getId(),
                            new CreateMetadataVersionRequest("1.0.0", validSnapshot("test2")));
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

    @Test
    void testInvalidSnapshotRejection_createsNoVersionAndPreservesPointer() {
        GsuifPage page = savedPage("Rejection Page");
        MetadataVersionDto original = metadataVersionService.create(
                page.getId(),
                new CreateMetadataVersionRequest("1.0.0", validSnapshot("original")));

        // Try to create invalid version
        CreateMetadataVersionRequest invalidRequest = new CreateMetadataVersionRequest("1.0.0", tools.jackson.databind.node.JsonNodeFactory.instance.objectNode());
        assertThrows(eg.mts.gsuif.exception.MetadataValidationException.class, () ->
                metadataVersionService.create(page.getId(), invalidRequest));

        // Verify no new version created
        List<MetadataVersion> versions = metadataVersionRepository
                .findAllByPageId(page.getId(), org.springframework.data.domain.Pageable.unpaged())
                .getContent();
        assertThat(versions).hasSize(1);
        assertThat(versions.getFirst().getId()).isEqualTo(original.id());

        // Verify pointer is preserved
        GsuifPage reloadedPage = pageRepository.findById(page.getId()).orElseThrow();
        assertThat(reloadedPage.getCurrentMetadataVersionId()).isEqualTo(original.id());
    }
}
