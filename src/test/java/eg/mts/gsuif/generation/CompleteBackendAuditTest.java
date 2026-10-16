package eg.mts.gsuif.generation;

import eg.mts.gsuif.generation.GenerationCapabilityPreflight.OutputMode;
import eg.mts.gsuif.generation.GenerationSpecification.EntitySpec;
import eg.mts.gsuif.generation.GenerationSpecification;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import javax.tools.ToolProvider;

import static org.junit.jupiter.api.Assertions.*;

class CompleteBackendAuditTest {
    private final CompleteOutputTestFixture fixture = new CompleteOutputTestFixture();

    @Configuration(proxyBeanMethods = false)
    @org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(
            name = "gsuif.audit.database-probe", havingValue = "true")
    @EnableAutoConfiguration
    @EntityScan(basePackages = {"com.example.inventory.entity", "com.example.inventory.security",
            "com.example.inventory.audit"})
    static class AuditDatabaseProbe { }

    @RestController
    static class AuditFailureProbe {
        private final org.springframework.data.domain.AuditorAware<String> auditor;
        AuditFailureProbe(org.springframework.data.domain.AuditorAware<String> auditor) {
            this.auditor = auditor;
        }
        @PostMapping("/audit-write-probe")
        public void write() { auditor.getCurrentAuditor(); }
    }

    private CompleteOutputDescriptor descriptor(boolean audited) throws Exception {
        GenerationSpecification base = fixture.specification();
        var entity = base.entity();
        var selected = new GenerationSpecification(base.specVersion(), base.basePackage(),
                new EntitySpec(entity.className(), entity.tableName(), entity.fields(), audited),
                base.openApi(), base.operationRoles(), base.angular());
        return fixture.preflight().describe(fixture.version(fixture.snapshot("condition")), selected,
                OutputMode.BACKEND);
    }

    @Test void auditFilesAreOwnedDeterministicAndBackendOnly() throws Exception {
        var descriptor = descriptor(false);
        var plan = GenerationFilePlan.forDescriptor(descriptor);
        var renderer = new CompleteBackendAuditRenderer();
        var first = renderer.render(descriptor, plan);
        var second = renderer.render(descriptor, plan);
        assertEquals(2, first.size());
        assertEquals(first.keySet(), second.keySet());
        for (String path : first.keySet()) {
            assertEquals(GenerationFilePlan.Owner.DETERMINISTIC, plan.entries().stream()
                    .filter(entry -> entry.path().equals(path)).findFirst().orElseThrow().owner());
            assertArrayEquals(first.get(path), second.get(path));
        }
        String path = first.keySet().iterator().next();
        byte[] original = first.get(path).clone();
        first.get(path)[0] ^= 1;
        assertArrayEquals(original, renderer.render(descriptor, plan).get(path));
        var missing = new GenerationFilePlan(plan.entries().stream()
                .filter(entry -> !entry.path().equals(path)).toList());
        assertThrows(IllegalArgumentException.class, () -> renderer.render(descriptor, missing));
        var wrongOwner = new GenerationFilePlan(plan.entries().stream().map(entry -> entry.path().equals(path)
                ? new GenerationFilePlan.Entry(entry.path(), GenerationFilePlan.Owner.MODEL, entry.purpose())
                : entry).toList());
        assertThrows(IllegalArgumentException.class, () -> renderer.render(descriptor, wrongOwner));
    }

    @Test void devRequiresOperatorActorAndSecuredModesRejectUnknownPrincipals(@TempDir Path scratch) throws Exception {
        try (var loader = compiled(descriptor(false), scratch)) {
            Class<?> modeClass = loader.loadClass("com.example.inventory.security.SecurityMode");
            Class<?> actorClass = loader.loadClass("com.example.inventory.audit.GeneratedAppAuditorAware");
            var constructor = actorClass.getConstructor(modeClass, org.springframework.core.env.Environment.class);
            var current = actorClass.getMethod("getCurrentAuditor");
            for (String invalid : List.of("", "   ", "a".repeat(101))) {
                var environment = new MockEnvironment().withProperty("server.address", "127.0.0.1")
                        .withProperty("GENERATED_APP_DEV_ACTOR", invalid);
                environment.setActiveProfiles("dev");
                Object mode = modeClass.getConstructor(org.springframework.core.env.Environment.class)
                        .newInstance(environment);
                assertInstanceOf(IllegalStateException.class,
                        assertThrows(java.lang.reflect.InvocationTargetException.class,
                                () -> constructor.newInstance(mode, environment)).getCause());
            }
            var absent = new MockEnvironment().withProperty("server.address", "127.0.0.1");
            absent.setActiveProfiles("dev");
            Object devMode = modeClass.getConstructor(org.springframework.core.env.Environment.class).newInstance(absent);
            assertThrows(java.lang.reflect.InvocationTargetException.class,
                    () -> constructor.newInstance(devMode, absent));
            var valid = new MockEnvironment().withProperty("server.address", "127.0.0.1")
                    .withProperty("GENERATED_APP_DEV_ACTOR", "local-operator");
            valid.setActiveProfiles("dev");
            Object configured = constructor.newInstance(modeClass.getConstructor(org.springframework.core.env.Environment.class)
                    .newInstance(valid), valid);
            assertEquals("local-operator", ((java.util.Optional<?>) current.invoke(configured)).orElseThrow());
            var maximum = new MockEnvironment().withProperty("server.address", "127.0.0.1")
                    .withProperty("GENERATED_APP_DEV_ACTOR", "a".repeat(100));
            maximum.setActiveProfiles("dev");
            Object maximumActor = constructor.newInstance(modeClass.getConstructor(org.springframework.core.env.Environment.class)
                    .newInstance(maximum), maximum);
            assertEquals("a".repeat(100), ((java.util.Optional<?>) current.invoke(maximumActor)).orElseThrow());
            var nonLoopback = new MockEnvironment().withProperty("server.address", "0.0.0.0")
                    .withProperty("GENERATED_APP_DEV_ACTOR", "local-operator");
            nonLoopback.setActiveProfiles("dev");
            assertThrows(java.lang.reflect.InvocationTargetException.class,
                    () -> modeClass.getConstructor(org.springframework.core.env.Environment.class).newInstance(nonLoopback));
            for (String profile : List.of("test", "prod")) {
                var environment = new MockEnvironment();
                environment.setActiveProfiles(profile);
                Object mode = modeClass.getConstructor(org.springframework.core.env.Environment.class)
                        .newInstance(environment);
                Object auditor = constructor.newInstance(mode, environment);
                try {
                    SecurityContextHolder.clearContext();
                    assertUnavailable(current, auditor);
                    SecurityContextHolder.getContext().setAuthentication(new AnonymousAuthenticationToken(
                            "test", "anonymous", List.of(new SimpleGrantedAuthority("ROLE_ANONYMOUS"))));
                    assertUnavailable(current, auditor);
                    SecurityContextHolder.getContext().setAuthentication(
                            UsernamePasswordAuthenticationToken.authenticated("unexpected", null, List.of()));
                    assertUnavailable(current, auditor);
                    Object principal = loader.loadClass("com.example.inventory.security.AuthenticatedAccount")
                            .getConstructor(UUID.class, String.class, Set.class)
                            .newInstance(UUID.randomUUID(), "Alice", Set.of());
                    SecurityContextHolder.getContext().setAuthentication(
                            UsernamePasswordAuthenticationToken.authenticated(principal, null, List.of()));
                    assertEquals("Alice", ((java.util.Optional<?>) current.invoke(auditor)).orElseThrow());
                } finally { SecurityContextHolder.clearContext(); }
            }
        }
    }

    @Test void auditingFailureUsesExistingSafeFiveFieldHttpEnvelope(@TempDir Path scratch) throws Exception {
        try (var loader = compiled(descriptor(false), scratch)) {
            var environment = new MockEnvironment();
            environment.setActiveProfiles("test");
            Class<?> mode = loader.loadClass("com.example.inventory.security.SecurityMode");
            Object selected = mode.getConstructor(org.springframework.core.env.Environment.class)
                    .newInstance(environment);
            @SuppressWarnings("unchecked")
            var auditor = (org.springframework.data.domain.AuditorAware<String>) loader
                    .loadClass("com.example.inventory.audit.GeneratedAppAuditorAware")
                    .getConstructor(mode, org.springframework.core.env.Environment.class)
                    .newInstance(selected, environment);
            Object handler = loader.loadClass("com.example.inventory.exception.GlobalExceptionHandler")
                    .getConstructor().newInstance();
            try {
                SecurityContextHolder.getContext().setAuthentication(
                        UsernamePasswordAuthenticationToken.authenticated("unexpected", null, List.of()));
                var response = org.springframework.test.web.servlet.setup.MockMvcBuilders
                        .standaloneSetup(new AuditFailureProbe(auditor)).setControllerAdvice(handler).build()
                        .perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                                .post("/audit-write-probe")).andReturn().getResponse();
                assertEquals(500, response.getStatus());
                var json = new com.fasterxml.jackson.databind.ObjectMapper().readTree(response.getContentAsString());
                assertEquals(5, json.size());
                assertEquals("INTERNAL_SERVER_ERROR", json.get("status").asText());
                assertEquals(500, json.get("statusCode").asInt());
                assertTrue(json.get("body").isNull());
                assertEquals("Unexpected server error", json.get("clientMessage").asText());
                assertEquals(1, json.get("errors").size());
                assertEquals("Unexpected server error", json.get("errors").get("_global").asText());
                assertFalse(response.getContentAsString().contains("Audit actor unavailable"));
                assertFalse(response.getContentAsString().contains("unexpected"));
            } finally { SecurityContextHolder.clearContext(); }
        }
    }

    private static void assertUnavailable(java.lang.reflect.Method current, Object auditor) {
        assertInstanceOf(IllegalStateException.class,
                assertThrows(java.lang.reflect.InvocationTargetException.class,
                        () -> current.invoke(auditor)).getCause());
    }

    @Test void auditedWritesPersistActorsAndRejectedTransactionsLeaveNoRevisions(@TempDir Path scratch)
            throws Exception {
        String baseUrl = System.getenv("SCRUM65_AUDIT_TEST_URL");
        org.junit.jupiter.api.Assumptions.assumeTrue(baseUrl != null
                && baseUrl.startsWith("jdbc:postgresql://127.0.0.1:"),
                "Requires a newly created disposable localhost PostgreSQL container");
        String password = System.getenv("SCRUM65_AUDIT_TEST_PASSWORD");
        assertNotNull(password);
        var descriptor = descriptor(true);
        var plan = GenerationFilePlan.forDescriptor(descriptor);
        Path migration = scratch.resolve("backend/src/main/resources/db/migration");
        var migrationFiles = new CompleteBackendInitialMigrationRenderer().render(descriptor, plan);
        for (var entry : migrationFiles.entrySet()) {
            if (!entry.getKey().endsWith(".sql")) continue;
            Path file = scratch.resolve(entry.getKey());
            Files.createDirectories(file.getParent());
            Files.write(file, entry.getValue());
        }
        String schema = "audit_" + UUID.randomUUID().toString().replace("-", "");
        try (var connection = java.sql.DriverManager.getConnection(baseUrl, "postgres", password);
                var statement = connection.createStatement()) {
            statement.execute("CREATE SCHEMA " + schema);
        }
        String url = baseUrl + (baseUrl.contains("?") ? "&" : "?") + "currentSchema=" + schema;
        assertEquals(1, org.flywaydb.core.Flyway.configure().dataSource(url, "postgres", password)
                .locations("filesystem:" + migration).load().migrate().migrationsExecuted);
        try (var loader = compiled(descriptor, scratch)) {
            String base = "com.example.inventory.";
            Class<?> entity = loader.loadClass(base + "entity.StockUnit");
            Class<?> condition = loader.loadClass(base + "dto.StockUnitCondition");
            Class<?> principalType = loader.loadClass(base + "security.AuthenticatedAccount");
            Class<?> auditConfig = loader.loadClass(base + "audit.AuditActorConfig");
            Class<?> mode = loader.loadClass(base + "security.SecurityMode");
            Object accountA = principalType.getConstructor(UUID.class, String.class, Set.class)
                    .newInstance(UUID.randomUUID(), "Alice", Set.of());
            Object accountB = principalType.getConstructor(UUID.class, String.class, Set.class)
                    .newInstance(UUID.randomUUID(), "Bob", Set.of());
            var runtime = new CompleteBackendRuntimeRenderer(new PackagedContractRegistry(
                    "/generation/stock-unit-contracts.yaml")).render(descriptor, plan);
            Path config = scratch.resolve("generated-application.yml");
            Files.write(config, runtime.get("backend/src/main/resources/application.yml"));
            ClassLoader previous = Thread.currentThread().getContextClassLoader();
            Thread.currentThread().setContextClassLoader(loader);
            try (var context = new SpringApplicationBuilder(AuditDatabaseProbe.class, auditConfig, mode)
                    .web(WebApplicationType.NONE)
                    .initializers(application -> application.getEnvironment().getPropertySources().addFirst(
                            new org.springframework.core.env.MapPropertySource("disposable-audit-db", Map.of(
                                    "spring.datasource.url", url,
                                    "spring.datasource.username", "postgres",
                                    "spring.datasource.password", password,
                                    "spring.flyway.locations", "filesystem:" + migration))))
                    .run("--spring.profiles.active=test", "--spring.config.location=" + config.toUri(),
                            "--spring.main.banner-mode=off", "--logging.level.root=OFF",
                            "--gsuif.audit.database-probe=true")) {
                assertEquals("false", context.getEnvironment().getProperty("spring.jpa.open-in-view"));
                var manager = context.getBean(jakarta.persistence.EntityManagerFactory.class);
                var transactions = new org.springframework.transaction.support.TransactionTemplate(
                        context.getBean(org.springframework.transaction.PlatformTransactionManager.class));
                var shared = org.springframework.orm.jpa.SharedEntityManagerCreator.createSharedEntityManager(manager);
                @SuppressWarnings({"unchecked", "rawtypes"})
                Object open = Enum.valueOf((Class<? extends Enum>) condition, "OPEN");
                Object item = entity.getConstructor().newInstance();
                entity.getMethod("setLabel", String.class).invoke(item, "initial");
                entity.getMethod("setCondition", condition).invoke(item, open);
                try {
                    authenticate(accountA);
                    transactions.executeWithoutResult(status -> {
                        shared.persist(item);
                        shared.flush();
                    });
                    UUID itemId = (UUID) entity.getMethod("getId").invoke(item);
                    assertNotNull(itemId);
                    assertStored(manager, entity, itemId, "initial", "Alice", "Alice");
                    authenticate(accountB);
                    transactions.executeWithoutResult(status -> {
                        Object stored = shared.find(entity, itemId);
                        try { entity.getMethod("setLabel", String.class).invoke(stored, "updated"); }
                        catch (ReflectiveOperationException exception) { throw new IllegalStateException(exception); }
                        shared.flush();
                    });
                    assertStored(manager, entity, itemId, "updated", "Alice", "Bob");
                    assertCurrentRows(url, password);
                    assertAuditRows(url, password, List.of(
                            List.of("initial", "Alice", "Alice"), List.of("updated", "Alice", "Bob")));
                    for (int invalid = 0; invalid < 3; invalid++) {
                        SecurityContextHolder.clearContext();
                        if (invalid == 1) SecurityContextHolder.getContext().setAuthentication(
                                new AnonymousAuthenticationToken("test", "anonymous",
                                        List.of(new SimpleGrantedAuthority("ROLE_ANONYMOUS"))));
                        if (invalid == 2) SecurityContextHolder.getContext().setAuthentication(
                                UsernamePasswordAuthenticationToken.authenticated("unexpected", null, List.of()));
                        Object rejected = entity.getConstructor().newInstance();
                        entity.getMethod("setLabel", String.class).invoke(rejected, "rejected");
                        entity.getMethod("setCondition", condition).invoke(rejected, open);
                        assertThrows(RuntimeException.class, () -> transactions.executeWithoutResult(status -> {
                            shared.persist(rejected); shared.flush();
                        }));
                        assertThrows(RuntimeException.class, () -> transactions.executeWithoutResult(status -> {
                            Object stored = shared.find(entity, itemId);
                            try { entity.getMethod("setLabel", String.class).invoke(stored, "rejected"); }
                            catch (ReflectiveOperationException exception) { throw new IllegalStateException(exception); }
                            shared.flush();
                        }));
                        assertStored(manager, entity, itemId, "updated", "Alice", "Bob");
                        assertCurrentRows(url, password);
                        assertAuditRows(url, password, List.of(
                                List.of("initial", "Alice", "Alice"), List.of("updated", "Alice", "Bob")));
                    }
                } finally { SecurityContextHolder.clearContext(); }
            } finally { Thread.currentThread().setContextClassLoader(previous); }
        }
    }

    private static void authenticate(Object principal) {
        SecurityContextHolder.getContext().setAuthentication(
                UsernamePasswordAuthenticationToken.authenticated(principal, null, List.of()));
    }

    private static void assertStored(jakarta.persistence.EntityManagerFactory factory, Class<?> entity, UUID id,
            String label, String createdBy, String lastModifiedBy) throws Exception {
        try (var fresh = factory.createEntityManager()) {
            Object stored = fresh.find(entity, id);
            assertNotNull(stored);
            assertEquals(label, entity.getMethod("getLabel").invoke(stored));
            assertEquals(createdBy, entity.getMethod("getCreatedBy").invoke(stored));
            assertEquals(lastModifiedBy, entity.getMethod("getLastModifiedBy").invoke(stored));
        }
    }

    private static void assertAuditRows(String url, String password, List<List<String>> expected) throws Exception {
        try (var connection = java.sql.DriverManager.getConnection(url, "postgres", password);
                var statement = connection.createStatement();
                var rows = statement.executeQuery(
                        "SELECT label, created_by, last_modified_by FROM stock_unit_aud ORDER BY rev")) {
            for (var values : expected) {
                assertTrue(rows.next());
                assertEquals(values.get(0), rows.getString(1));
                assertEquals(values.get(1), rows.getString(2));
                assertEquals(values.get(2), rows.getString(3));
            }
            assertFalse(rows.next(), "Rejected writes must not add revisions");
        }
    }

    private static void assertCurrentRows(String url, String password) throws Exception {
        try (var connection = java.sql.DriverManager.getConnection(url, "postgres", password);
                var statement = connection.createStatement();
                var rows = statement.executeQuery("SELECT label, created_by, last_modified_by FROM stock_unit")) {
            assertTrue(rows.next());
            assertEquals("updated", rows.getString(1));
            assertEquals("Alice", rows.getString(2));
            assertEquals("Bob", rows.getString(3));
            assertFalse(rows.next(), "Rejected inserts must not commit");
        }
    }

    @Test void unauditedDevWritesPreserveConfiguredActor(@TempDir Path scratch) throws Exception {
        String baseUrl = System.getenv("SCRUM65_AUDIT_TEST_URL");
        org.junit.jupiter.api.Assumptions.assumeTrue(baseUrl != null
                && baseUrl.startsWith("jdbc:postgresql://127.0.0.1:"),
                "Requires a newly created disposable localhost PostgreSQL container");
        String password = System.getenv("SCRUM65_AUDIT_TEST_PASSWORD");
        assertNotNull(password);
        var descriptor = descriptor(false);
        var plan = GenerationFilePlan.forDescriptor(descriptor);
        Path migration = scratch.resolve("backend/src/main/resources/db/migration");
        for (var entry : new CompleteBackendInitialMigrationRenderer().render(descriptor, plan).entrySet()) {
            if (!entry.getKey().endsWith(".sql")) continue;
            Path file = scratch.resolve(entry.getKey());
            Files.createDirectories(file.getParent());
            Files.write(file, entry.getValue());
        }
        String schema = "dev_audit_" + UUID.randomUUID().toString().replace("-", "");
        try (var connection = java.sql.DriverManager.getConnection(baseUrl, "postgres", password);
                var statement = connection.createStatement()) {
            statement.execute("CREATE SCHEMA " + schema);
        }
        String url = baseUrl + (baseUrl.contains("?") ? "&" : "?") + "currentSchema=" + schema;
        assertEquals(1, org.flywaydb.core.Flyway.configure().dataSource(url, "postgres", password)
                .locations("filesystem:" + migration).load().migrate().migrationsExecuted);
        try (var connection = java.sql.DriverManager.getConnection(url, "postgres", password);
                var statement = connection.createStatement();
                var rows = statement.executeQuery("SELECT to_regclass('stock_unit_aud')")) {
            assertTrue(rows.next());
            assertNull(rows.getString(1), "The unaudited profile must not create an Envers table");
        }
        try (var loader = compiled(descriptor, scratch)) {
            String base = "com.example.inventory.";
            Class<?> entity = loader.loadClass(base + "entity.StockUnit");
            Class<?> condition = loader.loadClass(base + "dto.StockUnitCondition");
            var runtime = new CompleteBackendRuntimeRenderer(new PackagedContractRegistry(
                    "/generation/stock-unit-contracts.yaml")).render(descriptor, plan);
            Path config = scratch.resolve("generated-application.yml");
            Files.write(config, runtime.get("backend/src/main/resources/application.yml"));
            String actor = " Dev Operator ";
            ClassLoader previous = Thread.currentThread().getContextClassLoader();
            Thread.currentThread().setContextClassLoader(loader);
            try (var context = new SpringApplicationBuilder(AuditDatabaseProbe.class,
                    loader.loadClass(base + "audit.AuditActorConfig"),
                    loader.loadClass(base + "security.SecurityMode"))
                    .web(WebApplicationType.NONE)
                    .initializers(application -> application.getEnvironment().getPropertySources().addFirst(
                            new org.springframework.core.env.MapPropertySource("disposable-dev-audit-db", Map.of(
                                    "spring.datasource.url", url,
                                    "spring.datasource.username", "postgres",
                                    "spring.datasource.password", password,
                                    "spring.flyway.locations", "filesystem:" + migration,
                                    "GENERATED_APP_DEV_ACTOR", actor))))
                    .run("--spring.profiles.active=dev", "--spring.config.location=" + config.toUri(),
                            "--server.address=127.0.0.1", "--spring.main.banner-mode=off",
                            "--logging.level.root=OFF", "--gsuif.audit.database-probe=true")) {
                assertEquals("false", context.getEnvironment().getProperty("spring.jpa.open-in-view"));
                var factory = context.getBean(jakarta.persistence.EntityManagerFactory.class);
                var transactions = new org.springframework.transaction.support.TransactionTemplate(
                        context.getBean(org.springframework.transaction.PlatformTransactionManager.class));
                var shared = org.springframework.orm.jpa.SharedEntityManagerCreator.createSharedEntityManager(factory);
                @SuppressWarnings({"unchecked", "rawtypes"})
                Object open = Enum.valueOf((Class<? extends Enum>) condition, "OPEN");
                Object item = entity.getConstructor().newInstance();
                entity.getMethod("setLabel", String.class).invoke(item, "created");
                entity.getMethod("setCondition", condition).invoke(item, open);
                SecurityContextHolder.clearContext();
                transactions.executeWithoutResult(status -> { shared.persist(item); shared.flush(); });
                UUID id = (UUID) entity.getMethod("getId").invoke(item);
                assertStored(factory, entity, id, "created", actor, actor);
                transactions.executeWithoutResult(status -> {
                    Object stored = shared.find(entity, id);
                    try { entity.getMethod("setLabel", String.class).invoke(stored, "updated"); }
                    catch (ReflectiveOperationException exception) { throw new IllegalStateException(exception); }
                    shared.flush();
                });
                assertStored(factory, entity, id, "updated", actor, actor);
            } finally {
                Thread.currentThread().setContextClassLoader(previous);
                SecurityContextHolder.clearContext();
            }
        }
    }

    private URLClassLoader compiled(CompleteOutputDescriptor descriptor, Path scratch) throws Exception {
        var plan = GenerationFilePlan.forDescriptor(descriptor);
        Map<String, byte[]> sources = new TreeMap<>(new CompleteBackendAuditRenderer().render(descriptor, plan));
        sources.putAll(new CompleteBackendDefinitionRenderer().render(descriptor, plan).files());
        sources.putAll(new CompleteBackendSecurityRenderer().render(descriptor, plan));
        sources.putAll(new CompleteBackendErrorRenderer().render(descriptor, plan));
        sources.putAll(new CompleteBackendObservabilityRenderer().render(descriptor, plan).entrySet().stream()
                .filter(entry -> entry.getKey().endsWith("SafeFailureLogger.java"))
                .collect(java.util.stream.Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue)));
        sources.putAll(new CompleteBackendInitialMigrationRenderer().render(descriptor, plan));
        Path classes = Files.createDirectories(scratch.resolve("classes"));
        List<String> arguments = new ArrayList<>(List.of("-classpath", System.getProperty("java.class.path"),
                "-d", classes.toString()));
        for (var entry : sources.entrySet()) {
            if (!entry.getKey().endsWith(".java")) continue;
            Path source = scratch.resolve(entry.getKey());
            Files.createDirectories(source.getParent());
            Files.write(source, entry.getValue());
            arguments.add(source.toString());
        }
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null, arguments.toArray(String[]::new)));
        return new URLClassLoader(new java.net.URL[] {classes.toUri().toURL()}, getClass().getClassLoader());
    }
}
