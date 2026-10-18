package eg.mts.gsuif.generation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import eg.mts.gsuif.dto.GenerationApiDtos;
import eg.mts.gsuif.entity.*;
import eg.mts.gsuif.repository.*;
import eg.mts.gsuif.validator.MetadataSchemaValidator;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

/** One explicitly opted-in public run with the real server-configured gateway and isolated exported-JAR execution. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(BackendConnectedRealProviderAcceptanceTest.AcceptedSourceEvidence.Configuration.class)
class BackendConnectedRealProviderAcceptanceTest {
    private static final Path STORE = Path.of("target", "backend-connected-real-" + UUID.randomUUID());
    private static final Path EXPORTS = Path.of("target", "backend-connected-real-exports-" + UUID.randomUUID());
    private final ObjectMapper json = new ObjectMapper();
    @Autowired MockMvc mvc;
    @Autowired EntityManager entities;
    @Autowired PlatformTransactionManager transactions;
    @Autowired GsuifPermissionRepository permissions;
    @Autowired GenerationRunRepository runs;
    @Autowired GeneratedArtifactRepository artifacts;
    @Autowired BackendModelGateway gateway;
    @Autowired BackendFinalBuild finalBuild;
    @Autowired AcceptedSourceEvidence evidence;
    private Path scratch;
    private Fixture fixture;

    @DynamicPropertySource static void paths(DynamicPropertyRegistry registry) {
        registry.add("gsuif.generation.artifact-root", () -> STORE.toString());
        registry.add("gsuif.generation.export-root", () -> EXPORTS.toString());
    }

    @Test void offlineWiringUsesRealConfiguredComponentsAndRegisteredPagedContract() {
        assertEquals("openrouter", gateway.provenance().providerId());
        assertEquals(OpenRouterBackendModelAdapter.MODEL, gateway.provenance().modelId());
        assertFalse(gateway.provenance().mocked());
        assertInstanceOf(ConfiguredBackendFinalBuild.class, finalBuild);
        assertEquals("/openapi/stock-unit-api-paged.yaml",
                new PackagedContractRegistry().resource("stock-unit-api", "3.0.0"));
    }

    @Test void compilerImageComesFromEnvironmentWithoutSystemPropertyOrDocker() {
        String image = "sha256:" + "a".repeat(64);
        assertEquals(image, compilerImage(Map.of("GSUIF_BACKEND_COMPILER_IMAGE_ID", image)));
        assertDoesNotThrow(() -> new DockerBackendJavaCompiler(
                compilerImage(Map.of("GSUIF_BACKEND_COMPILER_IMAGE_ID", image))));
        assertThrows(IllegalArgumentException.class, () -> compilerImage(Map.of()));
    }

    @Test void workspaceExtractionVerifiesParentAndCleansOnlyOwnedDirectory() throws Exception {
        scratch = createWorkspaceScratch();
        Path owned = scratch;
        Path neighbor = null;
        try {
            neighbor = Files.createTempDirectory(workspaceScratchParent(), "attempt-neighbor-");
            Path untouched = Files.writeString(neighbor.resolve("untouched.txt"), "neighbor",
                    StandardCharsets.US_ASCII);
            Path zip = scratch.resolve("synthetic-export.zip");
            try (var archive = new ZipOutputStream(Files.newOutputStream(zip))) {
                archive.putNextEntry(new ZipEntry("generation-manifest.json"));
                archive.write("{}".getBytes(StandardCharsets.US_ASCII));
                archive.closeEntry();
                archive.putNextEntry(new ZipEntry("backend/pom.xml"));
                archive.write("<project/>".getBytes(StandardCharsets.US_ASCII));
                archive.closeEntry();
            }
            Path extracted = extractExactZip(zip);
            assertEquals("{}", Files.readString(extracted.resolve("generation-manifest.json")));
            assertEquals("<project/>", Files.readString(extracted.resolve("backend/pom.xml")));
            scratch = owned.resolve("missing-parent").resolve("attempt-child");
            AssertionError missingParent = assertThrows(AssertionError.class, () -> extractExactZip(zip));
            assertTrue(missingParent.getMessage().startsWith("EXTRACTION_PARENT_MISSING"));
            scratch = owned;
        } finally {
            try {
                cleanupWorkspaceScratch(owned);
                assertFalse(Files.exists(owned), "OWNED_SCRATCH_REMAINS");
                if (neighbor != null)
                    assertTrue(Files.isRegularFile(neighbor.resolve("untouched.txt")),
                            "NEIGHBOR_SCRATCH_CHANGED");
            } finally {
                if (neighbor != null) cleanupWorkspaceScratch(neighbor);
                scratch = null;
            }
        }
    }

    @Test
    @EnabledIfSystemProperty(named = "gsuif.test.connected-real-provider", matches = "true")
    void oneRealProviderRunExportsAndExecutesExactPagedPackage() throws Exception {
        requirePrerequisites();
        Path attemptEvidence = Path.of(required("gsuif.test.connected-real-evidence-dir"));
        BackendModelResponseDiagnostic.openLive(attemptEvidence); // private, fresh directory outside Git/target
        String snapshot = sourceSnapshot(attemptEvidence);
        assertEquals(required("gsuif.test.connected-real-source-sha256"), snapshot,
                "SOURCE_SNAPSHOT_CHANGED_BEFORE_PROVIDER_REQUEST");
        preflightLocalIsolation();
        evidence.prepare(attemptEvidence);
        assertEquals(OpenRouterBackendModelAdapter.MODEL, gateway.provenance().modelId());
        assertFalse(gateway.provenance().mocked());
        assertEquals("openrouter", gateway.provenance().providerId());
        assertInstanceOf(ConfiguredBackendFinalBuild.class, finalBuild);

        CompleteOutputTestFixture selected = new CompleteOutputTestFixture();
        UUID attemptId = UUID.randomUUID();
        String stage = "PUBLIC_REQUEST";
        UUID runId = null;
        scratch = createWorkspaceScratch();
        try {
            fixture = new TransactionTemplate(transactions).execute(status -> persist(selected.pagedSnapshot()));
            var response = mvc.perform(post("/api/v1/generation/backend")
                            .with(authentication(fixture.auth())).contentType("application/json")
                            .content(json.writeValueAsBytes(new GenerationApiDtos.BackendRequest(
                                    fixture.pageId(), selected.pagedSpecification(), attemptId))))
                    .andReturn().getResponse();
            if (response.getStatus() != 201) {
                boundedFailure(attemptEvidence, response.getStatus(), response.getHeader("X-Generation-Run-Id"),
                        response.getHeader("X-Generation-Failure-Stage"),
                        response.getHeader("X-Generation-Failure-Code"));
                fail("PUBLIC_BACKEND_" + response.getStatus());
            }
            JsonNode created = json.readTree(response.getContentAsByteArray()).path("body");
            runId = UUID.fromString(created.path("runId").asText());
            assertEquals("stock-unit-api", created.path("backendPackage").path("contractId").asText());
            assertEquals("3.0.0", created.path("backendPackage").path("contractVersion").asText());
            assertEquals("MODEL_OUTPUT_STRUCTURE", created.path("backendPackage")
                    .path("validationScope").path("passedTargets").get(0).asText());
            assertEquals("ISOLATED_JAVA_COMPILATION", created.path("backendPackage")
                    .path("validationScope").path("passedTargets").get(1).asText());
            assertEquals("STANDALONE_MAVEN_PACKAGE", created.path("backendPackage")
                    .path("validationScope").path("passedTargets").get(2).asText());
            var persisted = runs.findById(runId).orElseThrow();
            assertEquals(GenerationRunStatus.SUCCESS, persisted.getStatus());
            assertEquals("openrouter", persisted.getToolName());
            assertEquals("api-v1|qwen/qwen3-coder", persisted.getToolVersion());
            assertEquals(persisted.getPackageManifestSha256(),
                    created.path("backendPackage").path("manifestSha256").asText());
            stage = "RUN_DETAILS";
            JsonNode details = json.readTree(mvc.perform(get("/api/v1/generation/runs/{id}", runId)
                    .with(authentication(fixture.auth()))).andReturn().getResponse().getContentAsByteArray());
            assertEquals(persisted.getPackageManifestSha256(), details.path("body")
                    .path("backendPackage").path("manifestSha256").asText());

            stage = "EXPORT";
            assertEquals(200, mvc.perform(put("/api/v1/generation/projects/{id}/destinations/connected-real",
                            fixture.projectId()).with(authentication(fixture.auth()))
                            .contentType("application/json")
                            .content("{\"type\":\"ZIP\",\"path\":\"connected-real\"}"))
                    .andReturn().getResponse().getStatus());
            var exported = mvc.perform(post("/api/v1/generation/runs/{id}/exports/connected-real", runId)
                    .with(authentication(fixture.auth()))).andReturn().getResponse();
            assertEquals(200, exported.getStatus());
            Path zip = Path.of(URI.create(json.readTree(exported.getContentAsByteArray())
                    .path("body").path("location").asText()));
            byte[] exportedZip = Files.readAllBytes(zip);
            Files.write(attemptEvidence.resolve("exact-public-export.zip"), exportedZip,
                    java.nio.file.StandardOpenOption.CREATE_NEW);
            Files.writeString(attemptEvidence.resolve("exact-public-export.sha256"),
                    sha256(exportedZip) + "\n", StandardCharsets.US_ASCII);
            Path extracted = extractExactZip(zip);

            stage = "EXPORTED_INTEGRITY";
            byte[] manifestBytes = Files.readAllBytes(extracted.resolve("generation-manifest.json"));
            assertEquals(persisted.getPackageManifestSha256(), sha256(manifestBytes));
            JsonNode manifest = json.readTree(manifestBytes);
            assertEquals("stock-unit-api", manifest.path("contract").path("id").asText());
            assertEquals("3.0.0", manifest.path("contract").path("version").asText());
            assertFalse(manifest.path("provider").path("mocked").asBoolean(true));
            assertEquals(OpenRouterBackendModelAdapter.MODEL, manifest.path("provider").path("model").asText());
            verifyExportedFiles(extracted, manifest);
            byte[] contract = Files.readAllBytes(extracted.resolve("contracts/stock-unit-api-3.0.0.yaml"));
            try (var registered = getClass().getResourceAsStream("/openapi/stock-unit-api-paged.yaml")) {
                assertNotNull(registered);
                assertArrayEquals(registered.readAllBytes(), contract);
            }
            assertEquals(sha256(contract), manifest.path("contract").path("sha256").asText());
            String sourcePath = "backend/src/main/java/com/example/inventory/service/StockUnitServiceImpl.java";
            byte[] source = Files.readAllBytes(extracted.resolve(sourcePath));
            JsonNode sourceEntry = null;
            for (JsonNode entry : manifest.path("files"))
                if (sourcePath.equals(entry.path("path").asText())) sourceEntry = entry;
            assertNotNull(sourceEntry, "MODEL_SOURCE_MISSING_FROM_MANIFEST");
            assertEquals("MODEL", sourceEntry.path("owner").asText());
            assertEquals(sha256(source), sourceEntry.path("sha256").asText());
            assertArrayEquals(source, Files.readAllBytes(attemptEvidence.resolve(sourcePath)));
            assertEquals(sha256(source), Files.readString(attemptEvidence.resolve("accepted-source.sha256")).trim());

            stage = "ISOLATED_RUNTIME";
            var descriptor = new TransactionTemplate(transactions).execute(status -> {
                var version = entities.find(MetadataVersion.class, fixture.versionId());
                var registry = new PackagedContractRegistry();
                var preflight = new GenerationCapabilityPreflight(new GenerationContextBuilder(
                        json, new MetadataSchemaValidator(), registry), registry);
                return preflight.describe(version, selected.pagedSpecification(),
                        GenerationCapabilityPreflight.OutputMode.BACKEND);
            });
            assertNotNull(descriptor);
            var plan = GenerationFilePlan.forDescriptor(descriptor);
            var runtime = new CompleteBackendRuntimeRenderer(new PackagedContractRegistry());
            var definitions = new CompleteBackendDefinitionRenderer().render(descriptor, plan);
            var input = BackendModelInput.from(descriptor, plan, definitions, runtime);
            var dependencies = BackendMavenDependencyProvisioner.local().provision(input);
            var compiler = new DockerBackendJavaCompiler(compilerImage(System.getenv()));
            byte[] responseBytes = json.writeValueAsBytes(java.util.Map.of("files", List.of(
                    java.util.Map.of("path", input.writablePath(), "content",
                            new String(source, StandardCharsets.UTF_8)))));
            var accepted = new BackendModelOutputValidator((chosen, candidate) ->
                    compiler.compile(chosen, candidate, dependencies, Duration.ofSeconds(120)))
                    .validate(input, responseBytes);
            assertArrayEquals(source, accepted.source());
            var provenance = gateway.provenance();
            var assembler = new CompleteBackendPackageAssembler(runtime);
            var assembled = CompleteBackendPackageAssembler.combine(input, accepted, provenance,
                    assembler.deterministic(input));
            assertEquals(manifest.path("files").size() + 1, assembled.files().size());
            for (var file : assembled.files().entrySet())
                assertArrayEquals(file.getValue(), Files.readAllBytes(extracted.resolve(file.getKey())));
            new IsolatedStockUnitContainerAcceptance(scratch).verify(input, accepted, assembled, extracted,
                    dependencies, provenance, attemptEvidence, true);
            stage = "COMPLETE";
        } finally {
            try {
                Files.writeString(attemptEvidence.resolve("connected-result.txt"),
                        "stage=" + stage + "\nattemptId=" + attemptId + "\nrunId="
                                + (runId == null ? "unavailable" : runId) + "\nsourceSnapshotSha256=" + snapshot + "\n",
                        StandardCharsets.UTF_8);
                assertEquals(snapshot, sourceSnapshot(null), "SOURCE_CHANGED_DURING_ACCEPTANCE");
            } finally {
                try {
                    cleanupWorkspaceScratch(scratch);
                    Files.writeString(attemptEvidence.resolve("workspace-cleanup.txt"), "cleanup=confirmed\n",
                            StandardCharsets.US_ASCII);
                } catch (Exception cleanupFailure) {
                    Files.writeString(attemptEvidence.resolve("workspace-cleanup.txt"), "cleanup=unconfirmed\n",
                            StandardCharsets.US_ASCII);
                    throw cleanupFailure;
                }
            }
        }
    }

    private Path extractExactZip(Path zip) throws Exception {
        assertTrue(Files.isDirectory(scratch.getParent()), "EXTRACTION_PARENT_MISSING");
        assertTrue(Files.isDirectory(scratch), "EXTRACTION_SCRATCH_MISSING");
        Path extracted = Files.createDirectory(scratch.resolve("exact-public-export"));
        Set<String> paths = new java.util.HashSet<>();
        try (ZipFile archive = new ZipFile(zip.toFile())) {
            var entries = archive.entries();
            while (entries.hasMoreElements()) {
                var entry = entries.nextElement();
                if (entry.isDirectory()) continue;
                Path relative = GenerationArtifactStore.safeRelative(entry.getName());
                assertTrue(paths.add(entry.getName()), "DUPLICATE_EXPORT_PATH");
                Path target = extracted.resolve(relative).normalize();
                assertTrue(target.startsWith(extracted), "UNSAFE_EXPORT_PATH");
                Files.createDirectories(target.getParent());
                Files.write(target, archive.getInputStream(entry).readAllBytes());
            }
        }
        assertTrue(paths.contains("generation-manifest.json"));
        return extracted;
    }

    private static Path workspaceScratchParent() {
        Path workspace = Path.of("").toAbsolutePath().normalize();
        Path parent = workspace.resolve("target/connected-real-export-temp");
        assertTrue(parent.startsWith(workspace.resolve("target")), "SCRATCH_OUTSIDE_WORKSPACE");
        return parent;
    }

    private static Path createWorkspaceScratch() throws IOException {
        Path parent = workspaceScratchParent();
        Files.createDirectories(parent);
        assertTrue(Files.isDirectory(parent), "EXTRACTION_PARENT_MISSING");
        Path owned = Files.createTempDirectory(parent, "attempt-");
        assertTrue(Files.isDirectory(owned), "EXTRACTION_SCRATCH_MISSING");
        return owned;
    }

    private static void cleanupWorkspaceScratch(Path owned) throws IOException {
        Path parent = workspaceScratchParent().toRealPath();
        Path target = owned.toRealPath();
        if (!parent.equals(target.getParent()) || !target.getFileName().toString().startsWith("attempt-"))
            throw new IOException("Workspace scratch ownership mismatch");
        try (var files = Files.walk(target)) {
            for (Path file : files.sorted(java.util.Comparator.reverseOrder()).toList())
                Files.deleteIfExists(file);
        }
    }

    private void verifyExportedFiles(Path extracted, JsonNode manifest) throws Exception {
        Set<String> listed = new java.util.HashSet<>();
        for (JsonNode entry : manifest.path("files")) {
            String path = entry.path("path").asText();
            assertTrue(listed.add(path), "DUPLICATE_MANIFEST_PATH");
            Path file = extracted.resolve(GenerationArtifactStore.safeRelative(path));
            assertEquals(entry.path("sha256").asText(), sha256(Files.readAllBytes(file)));
        }
        try (var files = Files.walk(extracted)) {
            Set<String> actual = files.filter(Files::isRegularFile)
                    .map(extracted::relativize).map(path -> path.toString().replace('\\', '/'))
                    .collect(java.util.stream.Collectors.toSet());
            listed.add("generation-manifest.json");
            assertEquals(listed, actual, "EXPORT_FILE_SET");
        }
    }

    private void requirePrerequisites() {
        for (String key : List.of("gsuif.test.connected-real-evidence-dir",
                "gsuif.test.connected-real-source-sha256", "gsuif.test.ai-evidence-dir",
                "gsuif.test.ai-jdk-image", "gsuif.test.ai-postgres-image", "maven.repo.local",
                "gsuif.test.maven-command"))
            required(key);
        for (String key : List.of("OPENROUTER_API_KEY", "OPENROUTER_MODEL",
                "GSUIF_BACKEND_COMPILER_IMAGE_ID", "GSUIF_BACKEND_BUILD_IMAGE_ID", "GSUIF_MAVEN_COMMAND"))
            assertTrue(System.getenv(key) != null && !System.getenv(key).isBlank(), "MISSING_" + key);
        assertEquals(OpenRouterBackendModelAdapter.MODEL, System.getenv("OPENROUTER_MODEL"));
        compilerImage(System.getenv());
        assertTrue(System.getenv("GSUIF_BACKEND_BUILD_IMAGE_ID").matches("sha256:[0-9a-f]{64}"),
                "UNPINNED_GSUIF_BACKEND_BUILD_IMAGE_ID");
        for (String key : List.of("gsuif.test.ai-jdk-image", "gsuif.test.ai-postgres-image"))
            assertTrue(required(key).matches("sha256:[0-9a-f]{64}"), "UNPINNED_" + key);
        assertEquals(Path.of(required("gsuif.test.ai-evidence-dir")).toAbsolutePath().normalize(),
                Path.of(required("gsuif.test.connected-real-evidence-dir")).toAbsolutePath().normalize().getParent());
        assertTrue(required("gsuif.test.connected-real-source-sha256").matches("[0-9a-f]{64}"));
        assertEquals(Path.of(System.getenv("GSUIF_MAVEN_COMMAND")).toAbsolutePath().normalize(),
                Path.of(required("gsuif.test.maven-command")).toAbsolutePath().normalize());
    }

    private void preflightLocalIsolation() throws Exception {
        assertTrue(Files.isRegularFile(Path.of(required("gsuif.test.maven-command"))),
                "MAVEN_COMMAND_UNAVAILABLE");
        assertTrue(Files.isDirectory(Path.of(required("maven.repo.local"))),
                "MAVEN_CACHE_UNAVAILABLE");
        try (var docker = IsolatedBackendDockerSupport.local()) {
            for (String image : new java.util.HashSet<>(List.of(System.getenv("GSUIF_BACKEND_COMPILER_IMAGE_ID"),
                    System.getenv("GSUIF_BACKEND_BUILD_IMAGE_ID"), required("gsuif.test.ai-jdk-image"),
                    required("gsuif.test.ai-postgres-image")))) {
                var inspected = docker.run(List.of("image", "inspect", "--format", "{{.Id}}", image),
                        Duration.ofSeconds(10));
                assertEquals(0, inspected.exit(), "PINNED_IMAGE_UNAVAILABLE");
                assertEquals(image, inspected.output().trim(), "PINNED_IMAGE_CHANGED");
            }
        }
    }

    private static String required(String key) {
        String value = System.getProperty(key);
        if (value == null || value.isBlank()) throw new IllegalArgumentException("MISSING_" + key);
        return value;
    }

    private static String compilerImage(Map<String, String> environment) {
        String image = environment.get("GSUIF_BACKEND_COMPILER_IMAGE_ID");
        if (image == null || !image.matches("sha256:[0-9a-f]{64}"))
            throw new IllegalArgumentException("UNPINNED_GSUIF_BACKEND_COMPILER_IMAGE_ID");
        return image;
    }

    private static String sourceSnapshot(Path evidence) throws Exception {
        Path root = Path.of("").toAbsolutePath().normalize();
        List<Path> paths;
        try (var main = Files.walk(root.resolve("src/main"));
             var test = Files.walk(root.resolve("src/test"));
             var sql = Files.walk(root.resolve("sql"))) {
            paths = java.util.stream.Stream.concat(java.util.stream.Stream.concat(main, test), sql)
                    .filter(Files::isRegularFile).sorted().toList();
        }
        paths = new java.util.ArrayList<>(paths);
        paths.add(root.resolve("pom.xml"));
        paths.add(root.resolve("scripts/Invoke-OpenRouterLocal.ps1"));
        paths.sort(Path::compareTo);
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        StringBuilder manifest = new StringBuilder();
        for (Path file : paths) {
            String relative = root.relativize(file).toString().replace('\\', '/');
            String hash = sha256(Files.readAllBytes(file));
            manifest.append(hash).append("  ").append(relative).append('\n');
            digest.update((relative + "\u0000" + hash + "\n").getBytes(StandardCharsets.UTF_8));
        }
        if (evidence != null)
            Files.writeString(evidence.resolve("source-snapshot-files.sha256"), manifest, StandardCharsets.UTF_8);
        return HexFormat.of().formatHex(digest.digest());
    }

    private static String sha256(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    private static void boundedFailure(Path evidence, int status, String runId, String stage, String code)
            throws IOException {
        Files.writeString(evidence.resolve("public-failure.txt"), "httpStatus=" + status + "\nrunId="
                + bounded(runId) + "\nfailureStage=" + bounded(stage) + "\nfailureCode="
                + bounded(code) + "\n", StandardCharsets.UTF_8);
    }

    private static String bounded(String value) {
        return value != null && value.matches("[A-Za-z0-9_-]{1,80}") ? value : "unavailable";
    }

    private Fixture persist(com.fasterxml.jackson.databind.node.ObjectNode snapshot) {
        String unique = UUID.randomUUID().toString().substring(0, 12);
        GsuifProject project = new GsuifProject(); project.setName("Real backend " + unique); entities.persist(project);
        GsuifPage page = new GsuifPage(); page.setProject(project); page.setName("Inventory"); entities.persist(page);
        MetadataVersion version = new MetadataVersion(); version.setPage(page); version.setVersion(1);
        version.setSchemaVersion("1.0.0"); version.setSnapshot(snapshot.toString()); entities.persist(version);
        entities.flush(); page.setCurrentMetadataVersionId(version.getId());
        GsuifUser user = new GsuifUser(); user.setUsername("backend-" + unique);
        user.setPasswordHash("test-only"); entities.persist(user);
        GsuifRole role = new GsuifRole(); role.setName("ROLE_BACKEND_" + unique); entities.persist(role);
        entities.flush();
        GsuifUserRole membership = new GsuifUserRole();
        membership.setId(new GsuifUserRoleId(user.getId(), role.getId()));
        membership.setUser(user); membership.setRole(role); entities.persist(membership);
        for (String code : List.of("ENTITY_ACCESS:GsuifPage", "ENTITY_ACCESS:MetadataVersion",
                "ENTITY_ACCESS:GsuifProject")) {
            var permission = permissions.findByCode(code).orElseThrow();
            GsuifRolePermission grant = new GsuifRolePermission();
            grant.setId(new GsuifRolePermissionId(role.getId(), permission.getId()));
            grant.setRole(role); grant.setPermission(permission); entities.persist(grant);
        }
        Authentication auth = new UsernamePasswordAuthenticationToken(user.getUsername(), null,
                List.of(new SimpleGrantedAuthority(role.getName())));
        return new Fixture(project.getId(), page.getId(), version.getId(), user.getId(), role.getId(), auth);
    }

    @AfterEach void cleanupFixture() {
        SecurityContextHolder.clearContext();
        if (fixture == null) return;
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            entities.createQuery("delete from GeneratedArtifact a where a.generationRun.metadataVersion.id = :version")
                    .setParameter("version", fixture.versionId()).executeUpdate();
            entities.createQuery("delete from GenerationRun r where r.metadataVersion.id = :version")
                    .setParameter("version", fixture.versionId()).executeUpdate();
            entities.createQuery("delete from GsuifRolePermission p where p.role.id = :role")
                    .setParameter("role", fixture.roleId()).executeUpdate();
            entities.createQuery("delete from GsuifUserRole u where u.role.id = :role")
                    .setParameter("role", fixture.roleId()).executeUpdate();
            var page = entities.find(GsuifPage.class, fixture.pageId());
            page.setCurrentMetadataVersionId(null); entities.flush();
            entities.remove(entities.getReference(MetadataVersion.class, fixture.versionId()));
            entities.remove(page);
            entities.remove(entities.getReference(GsuifProject.class, fixture.projectId()));
            entities.remove(entities.getReference(GsuifUser.class, fixture.userId()));
            entities.remove(entities.getReference(GsuifRole.class, fixture.roleId()));
        });
    }

    private record Fixture(UUID projectId, UUID pageId, UUID versionId, UUID userId, UUID roleId,
                           Authentication auth) { }

    /** Test-only observing decorator. The real configured gateway performs every provider and compiler step. */
    static final class AcceptedSourceEvidence {
        private final AtomicReference<Path> directory = new AtomicReference<>();
        void prepare(Path selected) { assertTrue(directory.compareAndSet(null, selected)); }
        void save(BackendModelOutputValidator.Accepted accepted) {
            Path selected = directory.get();
            if (selected == null) throw new IllegalStateException("ACCEPTED_SOURCE_EVIDENCE_NOT_CONFIGURED");
            try {
                byte[] source = accepted.source();
                Path target = selected.resolve(GenerationArtifactStore.safeRelative(accepted.path()));
                Files.createDirectories(target.getParent());
                Files.write(target, source, java.nio.file.StandardOpenOption.CREATE_NEW);
                Files.writeString(selected.resolve("accepted-source.sha256"), sha256(source) + "\n",
                        StandardCharsets.US_ASCII);
                if (!Arrays.equals(source, Files.readAllBytes(target)))
                    throw new IOException("ACCEPTED_SOURCE_EVIDENCE_MISMATCH");
            } catch (Exception ex) { throw new IllegalStateException("ACCEPTED_SOURCE_EVIDENCE_FAILED"); }
        }

        @TestConfiguration(proxyBeanMethods = false)
        static class Configuration {
            @Bean AcceptedSourceEvidence acceptedSourceEvidence() { return new AcceptedSourceEvidence(); }

            @Bean @Primary BackendModelGateway observingRealGateway(
                    @Qualifier("configuredOpenRouterBackendGateway") BackendModelGateway actual,
                    AcceptedSourceEvidence capture) {
                assertInstanceOf(ConfiguredOpenRouterBackendGateway.class, actual);
                return new BackendModelGateway() {
                    @Override public CompleteBackendPackageAssembler.Provenance provenance() {
                        return actual.provenance();
                    }
                    @Override public BackendModelOutputValidator.Accepted generate(BackendModelInput input) {
                        BackendModelOutputValidator.Accepted accepted = actual.generate(input);
                        capture.save(accepted);
                        return accepted;
                    }
                };
            }
        }
    }
}
