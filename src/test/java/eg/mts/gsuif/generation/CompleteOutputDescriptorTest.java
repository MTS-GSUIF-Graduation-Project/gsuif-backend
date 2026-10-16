package eg.mts.gsuif.generation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import eg.mts.gsuif.entity.GsuifPage;
import eg.mts.gsuif.entity.GsuifProject;
import eg.mts.gsuif.entity.MetadataVersion;
import eg.mts.gsuif.generation.GenerationCapabilityPreflight.OutputMode;
import eg.mts.gsuif.generation.GenerationFilePlan.Entry;
import eg.mts.gsuif.generation.GenerationFilePlan.Owner;
import eg.mts.gsuif.generation.GenerationSpecification.*;
import eg.mts.gsuif.generation.PackagedContractRegistry.CrudAction;
import eg.mts.gsuif.validator.MetadataSchemaValidator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.TreeMap;
import java.nio.file.Files;
import java.nio.file.Path;
import java.io.InputStream;
import java.io.ByteArrayInputStream;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Element;
import javax.tools.ToolProvider;
import java.net.URLClassLoader;
import java.lang.reflect.Proxy;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicInteger;
import java.time.LocalDate;
import java.time.LocalDateTime;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.http.ResponseEntity;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import static org.junit.jupiter.api.Assertions.*;

class CompleteOutputDescriptorTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final List<String> ids = List.of("scanStockUnits", "registerStockUnit", "inspectStockUnit",
            "replaceStockUnit", "discardStockUnit");

    @Configuration
    @EnableMethodSecurity(proxyTargetClass = true)
    static class GeneratedMethodSecurityOnly { }

    @Test void initialMigrationConsumesProjectionAndReservesAllSupportPaths() throws Exception {
        var descriptor = preflight().describe(version(snapshot("condition")), specification(), OutputMode.BACKEND);
        var plan = GenerationFilePlan.forDescriptor(descriptor);
        var entity = CompleteBackendEntitySchema.from(descriptor);
        var files = new CompleteBackendInitialMigrationRenderer().render(descriptor, plan);
        String sql = new String(files.get("backend/src/main/resources/db/migration/V1__initial_schema.sql"),
                StandardCharsets.UTF_8);
        Files.createDirectories(Path.of("target/v1-review-examples"));
        Files.writeString(Path.of("target/v1-review-examples/StockUnit.sql"), sql, StandardCharsets.UTF_8);
        assertTrue(sql.startsWith(entity.sqlFragment()));
        assertTrue(sql.contains("CONSTRAINT uq_user_accounts_username UNIQUE (username)"));
        assertTrue(sql.contains("ON DELETE RESTRICT"));
        assertFalse(sql.contains("INSERT INTO") || sql.contains("lower(username)") || sql.contains("app_revision"));
        String user = new String(files.entrySet().stream().filter(e -> e.getKey().endsWith("/UserAccount.java"))
                .findFirst().orElseThrow().getValue(), StandardCharsets.UTF_8);
        assertTrue(user.contains("FetchType.LAZY"));
        assertTrue(user.contains("ROLE_ADMIN, ROLE_USER"));
        assertEquals(2, files.size());
        assertThrows(UnsupportedOperationException.class, files::clear);
        assertEquals(files.keySet(), new CompleteBackendInitialMigrationRenderer().render(descriptor, plan).keySet());
    }

    @Test void auditedMigrationUsesBigintRevisionAndProjectedAuditColumns() throws Exception {
        var base = specification();
        var audited = new GenerationSpecification(base.specVersion(), base.basePackage(),
                new EntitySpec(base.entity().className(), base.entity().tableName(), base.entity().fields(), true),
                base.openApi(), base.operationRoles(), base.angular());
        var descriptor = preflight().describe(version(snapshot("condition")), audited, OutputMode.BACKEND);
        var plan = GenerationFilePlan.forDescriptor(descriptor);
        var projection = CompleteBackendEntitySchema.from(descriptor);
        var files = new CompleteBackendInitialMigrationRenderer().render(descriptor, plan);
        String sql = new String(files.get("backend/src/main/resources/db/migration/V1__initial_schema.sql"),
                StandardCharsets.UTF_8);
        Files.createDirectories(Path.of("target/v1-review-examples"));
        Files.writeString(Path.of("target/v1-review-examples/StockUnit-audited.sql"), sql, StandardCharsets.UTF_8);
        assertTrue(sql.startsWith(projection.sqlFragment()));
        assertTrue(sql.contains("rev bigint GENERATED BY DEFAULT AS IDENTITY NOT NULL"));
        assertTrue(sql.contains("revtstmp bigint NOT NULL"));
        assertTrue(sql.contains("CREATE TABLE stock_unit_aud"));
        assertTrue(sql.contains("created_at timestamp(6) with time zone,"));
        assertTrue(sql.contains("CONSTRAINT pk_stock_unit_aud PRIMARY KEY (id, rev)"));
        assertTrue(sql.contains("CONSTRAINT fk_stock_unit_aud_rev FOREIGN KEY (rev) REFERENCES app_revision(rev) ON DELETE RESTRICT"));
        String revision = new String(files.entrySet().stream().filter(e -> e.getKey().endsWith("/AppRevision.java"))
                .findFirst().orElseThrow().getValue(), StandardCharsets.UTF_8);
        assertTrue(revision.contains("private Long rev;"));
        assertTrue(revision.contains("private long timestamp;"));
        assertEquals(3, files.size());
        assertTrue(plan.entries().stream().anyMatch(e -> e.path().endsWith("/audit/AppRevision.java")));
        var definitions = new CompleteBackendDefinitionRenderer().render(descriptor, plan);
        String entity = new String(definitions.files().entrySet().stream().filter(e -> e.getKey().endsWith("/entity/StockUnit.java"))
                .findFirst().orElseThrow().getValue(), StandardCharsets.UTF_8);
        assertTrue(entity.contains("@org.hibernate.envers.AuditOverride(forClass = AuditableEntity.class, isAudited = true)"));
    }

    @Test void isolatedPostgresMigrationAndHibernateAcceptance(@TempDir Path scratch) throws Exception {
        String baseUrl = System.getenv("SCRUM65_V1_TEST_URL");
        org.junit.jupiter.api.Assumptions.assumeTrue(baseUrl != null && baseUrl.startsWith("jdbc:postgresql://127.0.0.1:"),
                "Requires the newly created disposable localhost PostgreSQL container");
        String password = System.getenv("SCRUM65_V1_TEST_PASSWORD");
        assertNotNull(password);
        for (boolean audited : List.of(false, true)) {
            var base = specification();
            var selected = new GenerationSpecification(base.specVersion(), base.basePackage(),
                    new EntitySpec(base.entity().className(), base.entity().tableName(), base.entity().fields(), audited),
                    base.openApi(), base.operationRoles(), base.angular());
            var descriptor = preflight().describe(version(snapshot("condition")), selected, OutputMode.BACKEND);
            var plan = GenerationFilePlan.forDescriptor(descriptor);
            var support = new CompleteBackendInitialMigrationRenderer().render(descriptor, plan);
            var definitions = new CompleteBackendDefinitionRenderer().render(descriptor, plan).files();
            Path output = Files.createDirectories(scratch.resolve(audited ? "audited" : "plain"));
            List<String> javaSources = new ArrayList<>();
            for (var file : support.entrySet()) {
                Path target = output.resolve(file.getKey());
                Files.createDirectories(target.getParent());
                Files.write(target, file.getValue());
                if (target.toString().endsWith(".java")) javaSources.add(target.toString());
            }
            for (var file : definitions.entrySet()) {
                Path target = output.resolve(file.getKey());
                Files.createDirectories(target.getParent());
                Files.write(target, file.getValue());
                if (target.toString().endsWith(".java")) javaSources.add(target.toString());
            }
            Path classes = Files.createDirectories(output.resolve("classes"));
            List<String> compile = new ArrayList<>(List.of("-classpath", System.getProperty("java.class.path"),
                    "-d", classes.toString()));
            compile.addAll(javaSources);
            assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null, compile.toArray(String[]::new)));
            String schema = "v1_" + (audited ? "audit" : "plain") + "_" + UUID.randomUUID().toString().replace("-", "");
            String url = baseUrl + (baseUrl.contains("?") ? "&" : "?") + "currentSchema=" + schema;
            try (var admin = java.sql.DriverManager.getConnection(baseUrl, "postgres", password);
                    var statement = admin.createStatement()) {
                statement.execute("CREATE SCHEMA " + schema);
            }
            Path migration = output.resolve("backend/src/main/resources/db/migration");
            var flyway = org.flywaydb.core.Flyway.configure().dataSource(url, "postgres", password)
                    .locations("filesystem:" + migration).load();
            assertEquals(1, flyway.migrate().migrationsExecuted);
            assertEquals(0, flyway.migrate().migrationsExecuted);
            try (var connection = java.sql.DriverManager.getConnection(url, "postgres", password);
                    var statement = connection.createStatement()) {
                try (var rows = statement.executeQuery("SELECT count(*) FROM flyway_schema_history WHERE success")) {
                    assertTrue(rows.next()); assertEquals(1, rows.getInt(1));
                }
                for (String table : List.of("user_accounts", "user_account_roles"))
                    try (var rows = statement.executeQuery("SELECT count(*) FROM " + table)) {
                        assertTrue(rows.next()); assertEquals(0, rows.getInt(1));
                    }
                if (audited) {
                    try (var rows = statement.executeQuery("SELECT count(*) FROM app_revision")) {
                        assertTrue(rows.next()); assertEquals(0, rows.getInt(1));
                    }
                    try (var rows = statement.executeQuery("SELECT column_name,data_type FROM information_schema.columns "
                            + "WHERE table_schema=current_schema() AND table_name IN ('app_revision','stock_unit_aud') "
                            + "AND column_name IN ('rev','revtstmp') ORDER BY table_name,column_name")) {
                        int bigintColumns = 0;
                        while (rows.next()) {
                            assertEquals("bigint", rows.getString("data_type"));
                            bigintColumns++;
                        }
                        assertEquals(3, bigintColumns);
                    }
                }
                statement.execute("INSERT INTO user_accounts(id,username,password_hash,enabled) VALUES "
                        + "('00000000-0000-0000-0000-000000000001','Alice','test_hash',true)");
                statement.execute("INSERT INTO user_accounts(id,username,password_hash,enabled) VALUES "
                        + "('00000000-0000-0000-0000-000000000002','alice','test_hash',true)");
                assertEquals("23505", assertThrows(java.sql.SQLException.class, () -> statement.execute("INSERT INTO user_accounts"
                        + "(id,username,password_hash,enabled) VALUES "
                        + "('00000000-0000-0000-0000-000000000003','Alice','test_hash',true)"))
                        .getSQLState());
                statement.execute("INSERT INTO user_account_roles(user_id,role) VALUES "
                        + "('00000000-0000-0000-0000-000000000001','ROLE_ADMIN')");
                assertEquals("23503", assertThrows(java.sql.SQLException.class, () -> statement.execute("DELETE FROM user_accounts "
                        + "WHERE username='Alice'" )).getSQLState());
                assertEquals("23514", assertThrows(java.sql.SQLException.class, () -> statement.execute("INSERT INTO user_account_roles"
                        + "(user_id,role) VALUES ('00000000-0000-0000-0000-000000000002','ROOT')"))
                        .getSQLState());
                assertEquals("23514", assertThrows(java.sql.SQLException.class, () -> statement.execute("INSERT INTO " + descriptor.tableName()
                        + "(id,condition,label,created_at,updated_at,created_by,last_modified_by) VALUES "
                        + "('00000000-0000-0000-0000-000000000010','UNKNOWN','invalid',now(),now(),'test','test')"))
                        .getSQLState());
                statement.execute("INSERT INTO " + descriptor.tableName()
                        + "(id,condition,label,created_at,updated_at,created_by,last_modified_by) VALUES "
                        + "('00000000-0000-0000-0000-000000000010','OPEN','valid',now(),now(),'test','test')");
                try (var rows = statement.executeQuery("SELECT label FROM " + descriptor.tableName())) {
                    assertTrue(rows.next()); assertEquals("valid", rows.getString(1));
                }
            }
            try (var loader = new URLClassLoader(new java.net.URL[]{classes.toUri().toURL()}, getClass().getClassLoader())) {
                Class<?> entity = loader.loadClass("com.example.inventory.entity.StockUnit");
                Class<?> user = loader.loadClass("com.example.inventory.security.UserAccount");
                Class<?> revision = audited ? loader.loadClass("com.example.inventory.audit.AppRevision") : null;
                assertEquals(jakarta.persistence.FetchType.LAZY, user.getDeclaredField("roles")
                        .getAnnotation(jakarta.persistence.ElementCollection.class).fetch());
                if (audited) {
                    assertEquals(Long.class, revision.getDeclaredField("rev").getType());
                    assertEquals(long.class, revision.getDeclaredField("timestamp").getType());
                    assertNotNull(entity.getAnnotation(org.hibernate.envers.AuditOverride.class));
                }
                ClassLoader previous = Thread.currentThread().getContextClassLoader();
                Thread.currentThread().setContextClassLoader(loader);
                var registry = new org.hibernate.boot.registry.StandardServiceRegistryBuilder()
                        .applySetting("hibernate.connection.url", url)
                        .applySetting("hibernate.connection.username", "postgres")
                        .applySetting("hibernate.connection.password", password)
                        .applySetting("hibernate.connection.driver_class", "org.postgresql.Driver")
                        .applySetting("hibernate.hbm2ddl.auto", "validate")
                        .applySetting("hibernate.classLoaders", List.of(loader))
                        .build();
                try {
                    var sources = new org.hibernate.boot.MetadataSources(registry).addAnnotatedClass(entity)
                            .addAnnotatedClass(user);
                    if (audited) sources.addAnnotatedClass(revision);
                    var metadata = sources.buildMetadata();
                    if (audited) {
                        var auditBinding = metadata.getEntityBindings().stream()
                                .filter(binding -> binding.getEntityName().endsWith("_AUD"))
                                .findFirst().orElseThrow();
                        for (String property : List.of("createdAt", "updatedAt", "createdBy", "lastModifiedBy"))
                            assertNotNull(auditBinding.getProperty(property), property);
                    }
                    try (var factory = metadata.buildSessionFactory()) {
                        var session = factory.openSession();
                        try {
                            var account = user.getConstructor().newInstance();
                            user.getMethod("setUsername", String.class).invoke(account, "Bob");
                            user.getMethod("setPasswordHash", String.class).invoke(account, "test_hash");
                            user.getMethod("setEnabled", boolean.class).invoke(account, true);
                            @SuppressWarnings({"unchecked", "rawtypes"})
                            Object userRole = Enum.valueOf((Class<? extends Enum>)
                                    loader.loadClass("com.example.inventory.security.UserAccount$Role"), "ROLE_USER");
                            @SuppressWarnings("unchecked")
                            Set<Object> roles = (Set<Object>) user.getMethod("getRoles").invoke(account);
                            roles.add(userRole);
                            var transaction = session.beginTransaction();
                            session.persist(account);
                            transaction.commit();
                            Object accountId = user.getMethod("getId").invoke(account);
                            assertNotNull(accountId);
                            try (var reopened = factory.openSession()) {
                                var read = reopened.beginTransaction();
                                Object stored = reopened.get(user, accountId);
                                assertNotNull(stored);
                                @SuppressWarnings("unchecked")
                                Set<Object> storedRoles = (Set<Object>) user.getMethod("getRoles").invoke(stored);
                                assertEquals(Set.of(userRole), storedRoles);
                                read.commit();
                            }
                        } finally { session.close(); }
                        if (audited) {
                            var entitySession = factory.openSession();
                            try {
                                var item = entity.getConstructor().newInstance();
                                var condition = loader.loadClass("com.example.inventory.dto.StockUnitCondition");
                                @SuppressWarnings({"unchecked", "rawtypes"})
                                Object open = Enum.valueOf((Class<? extends Enum>) condition, "OPEN");
                                entity.getMethod("setLabel", String.class).invoke(item, "persisted");
                                entity.getMethod("setCondition", condition).invoke(item, open);
                                var auditBase = loader.loadClass("com.example.inventory.entity.AuditableEntity");
                                auditBase.getMethod("setCreatedAt", java.time.Instant.class)
                                        .invoke(item, java.time.Instant.now());
                                auditBase.getMethod("setUpdatedAt", java.time.Instant.class)
                                        .invoke(item, java.time.Instant.now());
                                auditBase.getMethod("setCreatedBy", String.class).invoke(item, "test");
                                auditBase.getMethod("setLastModifiedBy", String.class).invoke(item, "test");
                                var transaction = entitySession.beginTransaction();
                                entitySession.persist(item);
                                transaction.commit();
                                Object itemId = entity.getMethod("getId").invoke(item);
                                try (var reopened = factory.openSession()) {
                                    var read = reopened.beginTransaction();
                                    Object stored = reopened.get(entity, itemId);
                                    assertNotNull(stored);
                                    assertEquals("persisted", entity.getMethod("getLabel").invoke(stored));
                                    assertEquals(open, entity.getMethod("getCondition").invoke(stored));
                                    read.commit();
                                }
                                try (var check = java.sql.DriverManager.getConnection(url, "postgres", password);
                                        var query = check.createStatement();
                                        var rows = query.executeQuery("SELECT a.created_by, r.revtstmp "
                                                + "FROM stock_unit_aud a JOIN app_revision r ON a.rev=r.rev")) {
                                    assertTrue(rows.next());
                                    assertEquals("test", rows.getString(1));
                                    assertTrue(rows.getLong(2) > 0);
                                }
                                var update = entitySession.beginTransaction();
                                entity.getMethod("setLabel", String.class).invoke(item, "updated");
                                update.commit();
                                try (var reopened = factory.openSession()) {
                                    var read = reopened.beginTransaction();
                                    Object stored = reopened.get(entity, itemId);
                                    assertEquals("updated", entity.getMethod("getLabel").invoke(stored));
                                    assertEquals(open, entity.getMethod("getCondition").invoke(stored));
                                    read.commit();
                                }
                                try (var check = java.sql.DriverManager.getConnection(url, "postgres", password);
                                        var query = check.createStatement();
                                        var rows = query.executeQuery("SELECT revtype, label, condition FROM stock_unit_aud "
                                                + "ORDER BY rev DESC")) {
                                    assertTrue(rows.next());
                                    assertEquals(1, rows.getInt(1));
                                    assertEquals("updated", rows.getString(2));
                                    assertEquals("OPEN", rows.getString(3));
                                    assertTrue(rows.next());
                                    assertEquals(0, rows.getInt(1));
                                    assertEquals("persisted", rows.getString(2));
                                }
                                var deletion = entitySession.beginTransaction();
                                entitySession.remove(item);
                                deletion.commit();
                                try (var check = java.sql.DriverManager.getConnection(url, "postgres", password);
                                        var query = check.createStatement();
                                        var rows = query.executeQuery("SELECT revtype, label, created_by FROM stock_unit_aud "
                                                + "ORDER BY rev DESC LIMIT 1")) {
                                    assertTrue(rows.next());
                                    assertEquals(2, rows.getInt(1));
                                    assertNull(rows.getString(2));
                                    assertNull(rows.getString(3));
                                }
                            } finally { entitySession.close(); }
                        }
                    }
                } finally {
                    org.hibernate.boot.registry.StandardServiceRegistryBuilder.destroy(registry);
                    Thread.currentThread().setContextClassLoader(previous);
                }
            }
            assertEquals(0, flyway.migrate().migrationsExecuted);
            try (var check = java.sql.DriverManager.getConnection(url, "postgres", password);
                    var query = check.createStatement()) {
                try (var rows = query.executeQuery("SELECT count(*) FROM user_accounts")) {
                    assertTrue(rows.next()); assertEquals(3, rows.getInt(1));
                }
                query.execute("ALTER TABLE stock_unit DROP COLUMN label");
            }
            try (var loader = new URLClassLoader(new java.net.URL[]{classes.toUri().toURL()}, getClass().getClassLoader())) {
                ClassLoader previous = Thread.currentThread().getContextClassLoader();
                Thread.currentThread().setContextClassLoader(loader);
                var brokenRegistry = new org.hibernate.boot.registry.StandardServiceRegistryBuilder()
                        .applySetting("hibernate.connection.url", url)
                        .applySetting("hibernate.connection.username", "postgres")
                        .applySetting("hibernate.connection.password", password)
                        .applySetting("hibernate.connection.driver_class", "org.postgresql.Driver")
                        .applySetting("hibernate.hbm2ddl.auto", "validate")
                        .applySetting("hibernate.classLoaders", List.of(loader)).build();
                try {
                    var brokenSources = new org.hibernate.boot.MetadataSources(brokenRegistry)
                            .addAnnotatedClass(loader.loadClass("com.example.inventory.entity.StockUnit"))
                            .addAnnotatedClass(loader.loadClass("com.example.inventory.security.UserAccount"));
                    if (audited) brokenSources.addAnnotatedClass(loader.loadClass("com.example.inventory.audit.AppRevision"));
                    var mismatch = assertThrows(org.hibernate.tool.schema.spi.SchemaManagementException.class,
                            () -> brokenSources.buildMetadata().buildSessionFactory());
                    assertTrue(mismatch.getMessage().contains("label"), mismatch.getMessage());
                } finally {
                    org.hibernate.boot.registry.StandardServiceRegistryBuilder.destroy(brokenRegistry);
                    Thread.currentThread().setContextClassLoader(previous);
                }
            }
        }
    }

    @Test void stockUnitSchemaProjectionHasExactFieldsAuditAndEnumCheck() throws Exception {
        var descriptor = preflight().describe(version(snapshot("condition")), specification(), OutputMode.BACKEND);
        var schema = CompleteBackendEntitySchema.from(descriptor);
        assertEquals("stock_unit", schema.tableName());
        assertEquals(List.of("id", "condition", "label", "retired_on", "created_at", "updated_at",
                "created_by", "last_modified_by"), schema.columns().stream().map(
                CompleteBackendEntitySchema.Column::name).toList());
        assertEquals("uuid", schema.columns().get(0).sqlType());
        assertTrue(schema.columns().get(0).primaryKey());
        assertEquals("varchar(255)", schema.columns().get(1).sqlType());
        assertEquals("varchar(100)", schema.columns().get(2).sqlType());
        assertEquals("date", schema.columns().get(3).sqlType());
        assertTrue(schema.columns().get(3).nullable());
        assertEquals("timestamp(6) with time zone", schema.columns().get(4).sqlType());
        assertEquals("varchar(100)", schema.columns().get(6).sqlType());
        assertTrue(schema.columns().stream().filter(c -> !c.name().equals("retired_on"))
                .noneMatch(CompleteBackendEntitySchema.Column::nullable));
        assertEquals(List.of("OPEN", "CLOSED"), schema.constraints().get(0).enumValues());
        assertTrue(schema.sqlFragment().contains("CONSTRAINT ck_stock_unit_condition_enum CHECK (condition IN ('OPEN', 'CLOSED'))"));
        assertTrue(schema.sqlFragment().contains("CONSTRAINT pk_stock_unit PRIMARY KEY (id)"));
        assertThrows(UnsupportedOperationException.class, () -> schema.columns().clear());
        assertThrows(UnsupportedOperationException.class, () -> schema.constraints().get(0).enumValues().clear());
    }

    @Test void schemaProjectionOrdersEquivalentDescriptorFieldsCanonically() throws Exception {
        var descriptor = preflight().describe(version(snapshot("condition")), specification(), OutputMode.BACKEND);
        var reversed = new ArrayList<>(descriptor.fields());
        java.util.Collections.reverse(reversed);
        assertEquals(CompleteBackendEntitySchema.from(descriptor),
                CompleteBackendEntitySchema.from(schemaDescriptor(descriptor, descriptor.tableName(), reversed)));
        assertEquals(CompleteBackendEntitySchema.from(descriptor).sqlFragment(),
                CompleteBackendEntitySchema.from(schemaDescriptor(descriptor, descriptor.tableName(), reversed)).sqlFragment());
    }

    @Test void schemaProjectionMapsRemainingSupportedScalarsAndRejectsDecimal() throws Exception {
        var descriptor = preflight().describe(version(snapshot("condition")), specification(), OutputMode.BACKEND);
        var fields = List.of(
                new FieldSpec("externalId", "external_id", "UUID", false, null, null),
                new FieldSpec("count", "count", "Integer", false, null, null),
                new FieldSpec("total", "total", "Long", false, null, null),
                new FieldSpec("active", "active", "Boolean", false, null, null),
                new FieldSpec("observedAt", "observed_at", "LocalDateTime", true, null, null));
        var schema = CompleteBackendEntitySchema.from(schemaDescriptor(descriptor, "warehouse_entry", fields));
        var types = new java.util.TreeMap<String, String>();
        schema.columns().forEach(column -> types.put(column.name(), column.sqlType()));
        assertEquals("uuid", types.get("external_id"));
        assertEquals("integer", types.get("count"));
        assertEquals("bigint", types.get("total"));
        assertEquals("boolean", types.get("active"));
        assertEquals("timestamp(6) without time zone", types.get("observed_at"));
        assertTrue(schema.columns().stream().filter(c -> c.name().equals("observed_at"))
                .findFirst().orElseThrow().nullable());
        var decimals = List.of(new FieldSpec("amount", "amount", "BigDecimal", false, null, null));
        assertThrows(IllegalArgumentException.class,
                () -> CompleteBackendEntitySchema.from(schemaDescriptor(descriptor, "warehouse_entry", decimals)));
    }

    @Test void schemaProjectionRejectsUnsafeNamesAndGeneratedConstraintOverflow() throws Exception {
        var descriptor = preflight().describe(version(snapshot("condition")), specification(), OutputMode.BACKEND);
        for (String table : List.of("select", "bad-name", "a".repeat(64)))
            assertThrows(IllegalArgumentException.class,
                    () -> CompleteBackendEntitySchema.from(schemaDescriptor(descriptor, table, descriptor.fields())));
        for (String column : List.of("created_at", "bad-name", "a".repeat(64))) {
            var fields = new ArrayList<>(descriptor.fields());
            fields.set(0, new FieldSpec("label", column, "String", false, 100, null));
            assertThrows(IllegalArgumentException.class,
                    () -> CompleteBackendEntitySchema.from(schemaDescriptor(descriptor, descriptor.tableName(), fields)));
        }
        var duplicate = new ArrayList<>(descriptor.fields());
        duplicate.set(0, new FieldSpec("label", "retired_on", "String", false, 100, null));
        assertThrows(IllegalArgumentException.class,
                () -> CompleteBackendEntitySchema.from(schemaDescriptor(descriptor, descriptor.tableName(), duplicate)));
        var fields = new ArrayList<>(descriptor.fields());
        fields.set(1, new FieldSpec("condition", "x".repeat(54), "StockUnitCondition", false, null,
                "StockUnitCondition"));
        assertThrows(IllegalArgumentException.class,
                () -> CompleteBackendEntitySchema.from(schemaDescriptor(descriptor, descriptor.tableName(), fields)));
    }

    @Test void schemaProjectionReservesAppOwnedTablesAndChecksDerivedAuditName() throws Exception {
        var descriptor = preflight().describe(version(snapshot("condition")), specification(), OutputMode.BACKEND);
        for (String name : CompleteBackendPersistenceNames.SUPPORT_TABLES) {
            var failure = assertThrows(IllegalArgumentException.class,
                    () -> CompleteBackendEntitySchema.from(schemaDescriptor(descriptor, name, descriptor.fields())));
            assertTrue(failure.getMessage().contains("reserved app-owned table"), failure.getMessage());
        }
        var ordinary = CompleteBackendEntitySchema.from(schemaDescriptor(descriptor, "warehouse_entry", descriptor.fields()));
        assertNull(ordinary.auditTableName());
        var audited = auditedSchemaDescriptor(descriptor, "warehouse_entry");
        assertEquals("warehouse_entry_aud", CompleteBackendEntitySchema.from(audited).auditTableName());
        var tooLong = auditedSchemaDescriptor(descriptor, "a".repeat(60));
        var failure = assertThrows(IllegalArgumentException.class,
                () -> CompleteBackendEntitySchema.from(tooLong));
        assertTrue(failure.getMessage().contains("derived audit table"), failure.getMessage());
    }

    @Test void auditedEntityRejectsEnversMetadataColumnsBeforeRendering() throws Exception {
        var descriptor = preflight().describe(version(snapshot("condition")), specification(), OutputMode.BACKEND);
        var audited = auditedSchemaDescriptor(descriptor, "stock_unit");
        for (String column : List.of("rev", "revtype")) {
            var fields = new ArrayList<>(audited.fields());
            fields.set(0, new FieldSpec("label", column, "String", false, 100, null));
            var failure = assertThrows(IllegalArgumentException.class,
                    () -> CompleteBackendEntitySchema.from(schemaDescriptor(audited, "stock_unit", fields)));
            assertTrue(failure.getMessage().contains("Envers audit metadata column"), failure.getMessage());
        }
    }

    @Test void migrationRejectsSupportIndexAndSequenceNamesBeforeOutput() throws Exception {
        var descriptor = preflight().describe(version(snapshot("condition")), specification(), OutputMode.BACKEND);
        for (String name : List.of("pk_user_accounts", "uq_user_accounts_username",
                "pk_app_revision", "app_revision_rev_seq", "flyway_schema_history_pk",
                "flyway_schema_history_s_idx")) {
            var candidate = schemaDescriptor(descriptor, name, descriptor.fields());
            assertThrows(IllegalArgumentException.class,
                    () -> new CompleteBackendInitialMigrationRenderer().render(candidate,
                            GenerationFilePlan.forDescriptor(candidate)), name);
        }
        var valid = schemaDescriptor(descriptor, "warehouse_entry", descriptor.fields());
        assertTrue(new CompleteBackendInitialMigrationRenderer().render(valid,
                GenerationFilePlan.forDescriptor(valid)).containsKey(
                "backend/src/main/resources/db/migration/V1__initial_schema.sql"));
        var audited = auditedSchemaDescriptor(descriptor, "warehouse_entry");
        assertTrue(new CompleteBackendInitialMigrationRenderer().render(audited,
                GenerationFilePlan.forDescriptor(audited)).containsKey(
                "backend/src/main/resources/db/migration/V1__initial_schema.sql"));
    }

    @Test void unauditedMigrationDoesNotValidateUnusedAuditCheckNames() throws Exception {
        var descriptor = preflight().describe(version(snapshot("condition")), specification(), OutputMode.BACKEND);
        String table = "a".repeat(45);
        var plain = schemaDescriptor(descriptor, table, descriptor.fields());
        assertEquals(63, CompleteBackendEntitySchema.from(plain).constraints().get(0).name().length());
        assertTrue(new CompleteBackendInitialMigrationRenderer().render(plain,
                GenerationFilePlan.forDescriptor(plain)).keySet().stream()
                .anyMatch(path -> path.endsWith("V1__initial_schema.sql")));
        var audited = schemaDescriptor(auditedSchemaDescriptor(descriptor, table), table, descriptor.fields());
        var failure = assertThrows(IllegalArgumentException.class,
                () -> new CompleteBackendInitialMigrationRenderer().render(audited,
                        GenerationFilePlan.forDescriptor(audited)));
        assertEquals("Invalid or reserved PostgreSQL identifier for audit CHECK constraint",
                failure.getMessage());
    }

    private static CompleteOutputDescriptor auditedSchemaDescriptor(CompleteOutputDescriptor d, String table) {
        return new CompleteOutputDescriptor(d.metadataVersionId(), d.metadataVersionNumber(), d.metadataSchemaVersion(),
                d.specificationVersion(), d.validatedSnapshotJson(), d.pageId(), d.pageName(), d.pageRoute(),
                d.projectId(), d.projectName(), d.framework(), d.catalogVersion(), d.catalogAssets(),
                d.catalogProviderVersion(), d.standardRefs(), d.mode(), d.basePackage(), d.entityName(), table,
                true, d.fields(), d.angularClassName(), d.angularTableColumns(), d.contractId(),
                d.contractVersion(), d.contractResourceSha256(), d.contractDtoPackage(), d.contractServicePackage(),
                d.contractInterfacePackage(), d.contractServiceName(), d.contractInterfaceName(), d.dtoName(),
                d.pageDtoName(), d.createRequestName(), d.updateRequestName(), d.createRules(), d.createFields(),
                d.updateRules(), d.editableFields(), d.responseFields(), d.operations());
    }

    private static CompleteOutputDescriptor schemaDescriptor(CompleteOutputDescriptor d, String table,
                                                               List<FieldSpec> fields) {
        return new CompleteOutputDescriptor(d.metadataVersionId(), d.metadataVersionNumber(), d.metadataSchemaVersion(),
                d.specificationVersion(), d.validatedSnapshotJson(), d.pageId(), d.pageName(), d.pageRoute(),
                d.projectId(), d.projectName(), d.framework(), d.catalogVersion(), d.catalogAssets(),
                d.catalogProviderVersion(), d.standardRefs(), d.mode(), d.basePackage(), d.entityName(), table,
                d.enversAudited(), fields, d.angularClassName(), d.angularTableColumns(), d.contractId(),
                d.contractVersion(), d.contractResourceSha256(), d.contractDtoPackage(), d.contractServicePackage(),
                d.contractInterfacePackage(), d.contractServiceName(), d.contractInterfaceName(), d.dtoName(),
                d.pageDtoName(), d.createRequestName(), d.updateRequestName(), d.createRules(), d.createFields(),
                d.updateRules(), d.editableFields(), d.responseFields(), d.operations());
    }

    @Test void persistenceProfileRejectsBigDecimalBeforeDescriptorOrOutput() throws Exception {
        var base = specification();
        var fields = new ArrayList<>(base.entity().fields());
        fields.set(0, new FieldSpec("label", "label", "BigDecimal", false, null, null));
        var changed = new GenerationSpecification(base.specVersion(), base.basePackage(),
                new EntitySpec(base.entity().className(), base.entity().tableName(), fields, false),
                base.openApi(), base.operationRoles(), base.angular());
        for (var mode : List.of(OutputMode.BACKEND, OutputMode.FULL_STACK)) {
            var failure = assertThrows(GenerationValidationException.class,
                    () -> preflight().describe(version(snapshot("condition")), changed, mode));
            assertTrue(failure.diagnostics().stream().anyMatch(value -> value.contains("BigDecimal requires explicit precision and scale")));
        }
    }

    @Test void completeOutputChecksEnumValuesAgainstExplicitAndDefaultColumnLength() throws Exception {
        List<String> defaultLengthErrors = new ArrayList<>();
        GenerationCapabilityPreflight.validateEnumColumnLength(
                new FieldSpec("condition", "condition", "StockUnitCondition", false, null, "StockUnitCondition"),
                List.of("X".repeat(256)), 1, defaultLengthErrors);
        assertEquals(1, defaultLengthErrors.size());
        assertTrue(defaultLengthErrors.get(0).contains("exceeds varchar(255)"));
        List<String> invalidValueErrors = new ArrayList<>();
        GenerationCapabilityPreflight.validateEnumColumnLength(
                new FieldSpec("condition", "condition", "StockUnitCondition", false, null, "StockUnitCondition"),
                List.of("bad-value"), 1, invalidValueErrors);
        assertTrue(invalidValueErrors.stream().anyMatch(value -> value.contains("unsupported selected enum value")));
        var base = specification();
        var fields = new ArrayList<>(base.entity().fields());
        fields.set(1, new FieldSpec("condition", "condition", "StockUnitCondition", false, 3, "StockUnitCondition"));
        var shortColumn = new GenerationSpecification(base.specVersion(), base.basePackage(),
                new EntitySpec(base.entity().className(), base.entity().tableName(), fields, false),
                base.openApi(), base.operationRoles(), base.angular());
        var failure = assertThrows(GenerationValidationException.class,
                () -> preflight().describe(version(snapshot("condition")), shortColumn, OutputMode.BACKEND));
        assertTrue(failure.diagnostics().stream().anyMatch(value -> value.contains("enum value exceeds varchar(3)")));
        fields.set(1, new FieldSpec("condition", "condition", "StockUnitCondition", false, 6, "StockUnitCondition"));
        var fitting = new GenerationSpecification(base.specVersion(), base.basePackage(),
                new EntitySpec(base.entity().className(), base.entity().tableName(), fields, false),
                base.openApi(), base.operationRoles(), base.angular());
        var legacyLength = assertThrows(GenerationValidationException.class,
                () -> new GenerationContextBuilder(mapper, new MetadataSchemaValidator(),
                        new PackagedContractRegistry("/generation/stock-unit-contracts.yaml"))
                        .build(version(snapshot("condition")), fitting));
        assertTrue(legacyLength.diagnostics().stream().anyMatch(value -> value.contains("requires positive String length")));
        var descriptor = preflight().describe(version(snapshot("condition")), fitting, OutputMode.BACKEND);
        var files = new CompleteBackendDefinitionRenderer().render(descriptor, GenerationFilePlan.forDescriptor(descriptor)).files();
        String entity = new String(files.entrySet().stream().filter(entry -> entry.getKey().endsWith("/entity/StockUnit.java"))
                .findFirst().orElseThrow().getValue(), StandardCharsets.UTF_8);
        assertTrue(entity.contains("@JdbcTypeCode(SqlTypes.VARCHAR)\n    @Column(name = \"condition\", nullable = false, length = 6)"));
        assertTrue(entity.contains("private String label;"));
        assertTrue(entity.contains("private LocalDate retiredOn;"));
        assertFalse(entity.contains("@JdbcTypeCode(SqlTypes.VARCHAR)\n    @Column(name = \"label\""));
        assertEquals("7.2.24.Final", org.hibernate.Version.getVersionString());
        assertEquals(java.sql.Types.VARCHAR, org.hibernate.type.SqlTypes.VARCHAR);
    }

    @Test void emittedEnumResolvesToVarcharWithPinnedHibernateWithoutJdbc(@TempDir Path scratch) throws Exception {
        var descriptor = preflight().describe(version(snapshot("condition")), specification(), OutputMode.BACKEND);
        var files = new CompleteBackendDefinitionRenderer()
                .render(descriptor, GenerationFilePlan.forDescriptor(descriptor)).files();
        List<String> sources = new ArrayList<>();
        for (var entry : files.entrySet()) {
            if (!entry.getKey().endsWith(".java")) continue;
            Path file = scratch.resolve(entry.getKey());
            Files.createDirectories(file.getParent());
            Files.write(file, entry.getValue());
            sources.add(file.toString());
        }
        Path classes = Files.createDirectories(scratch.resolve("classes"));
        List<String> compiler = new ArrayList<>(List.of("-classpath", System.getProperty("java.class.path"),
                "-d", classes.toString()));
        compiler.addAll(sources);
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null, compiler.toArray(String[]::new)));
        try (var loader = new URLClassLoader(new java.net.URL[]{classes.toUri().toURL()}, getClass().getClassLoader())) {
            Class<?> entity = loader.loadClass("com.example.inventory.entity.StockUnit");
            var field = entity.getDeclaredField("condition");
            assertEquals(jakarta.persistence.EnumType.STRING,
                    field.getAnnotation(jakarta.persistence.Enumerated.class).value());
            assertEquals(org.hibernate.type.SqlTypes.VARCHAR,
                    field.getAnnotation(org.hibernate.annotations.JdbcTypeCode.class).value());
            ClassLoader previousLoader = Thread.currentThread().getContextClassLoader();
            Thread.currentThread().setContextClassLoader(loader);
            try {
                var registry = new org.hibernate.boot.registry.StandardServiceRegistryBuilder()
                        .applySetting("hibernate.dialect", "org.hibernate.dialect.PostgreSQLDialect")
                        .applySetting("hibernate.boot.allow_jdbc_metadata_access", "false")
                        .applySetting("hibernate.classLoaders", List.of(loader))
                        .build();
                try {
                    var metadata = new org.hibernate.boot.MetadataSources(registry)
                            .addAnnotatedClass(entity).buildMetadata();
                    var value = (org.hibernate.mapping.BasicValue) metadata
                            .getEntityBinding(entity.getName()).getProperty("condition").getValue();
                    assertEquals(java.sql.Types.VARCHAR, value.resolve().getJdbcType().getJdbcTypeCode());
                } finally {
                    org.hibernate.boot.registry.StandardServiceRegistryBuilder.destroy(registry);
                }
            } finally {
                Thread.currentThread().setContextClassLoader(previousLoader);
            }
        }
    }

    @Test void runtimeScaffoldIsPlannedSecretFreeAndCopiesExactContractBytes() throws Exception {
        var descriptor = preflight().describe(version(snapshot("condition")), specification(), OutputMode.BACKEND);
        var plan = GenerationFilePlan.forDescriptor(descriptor);
        var renderer = new CompleteBackendRuntimeRenderer(new PackagedContractRegistry(
                "/generation/stock-unit-contracts.yaml"));
        var files = renderer.render(descriptor, plan);
        String contractPath = "contracts/stock-unit-api-2.0.0.yaml";
        assertEquals(Set.of("README.md", contractPath, "backend/pom.xml",
                "backend/src/main/resources/application.yml",
                "backend/src/main/java/com/example/inventory/Application.java"), files.keySet());
        try (InputStream input = getClass().getResourceAsStream("/openapi/stock-unit-api.yaml")) {
            assertNotNull(input);
            assertArrayEquals(input.readAllBytes(), files.get(contractPath));
        }
        assertEquals(descriptor.contractResourceSha256(), HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(files.get(contractPath))));
        String pom = new String(files.get("backend/pom.xml"), StandardCharsets.UTF_8);
        String config = new String(files.get("backend/src/main/resources/application.yml"), StandardCharsets.UTF_8);
        String readme = new String(files.get("README.md"), StandardCharsets.UTF_8);
        assertTrue(pom.contains("<version>4.0.8</version>"));
        assertTrue(pom.contains("<artifactId>flyway-database-postgresql</artifactId><version>11.14.1</version>"));
        assertTrue(pom.contains("<artifactId>postgresql</artifactId><version>42.7.13</version>"));
        assertTrue(pom.contains("<artifactId>springdoc-openapi-starter-webmvc-api</artifactId><version>3.0.3</version>"));
        assertTrue(pom.contains("<artifactId>maven-compiler-plugin</artifactId><version>3.14.1</version>"));
        assertTrue(pom.contains("<artifactId>maven-surefire-plugin</artifactId><version>3.5.6</version>"));
        assertFalse(pom.contains("hibernate-envers"));
        assertFalse(pom.matches("(?s).*(LATEST|SNAPSHOT|<version>\\[|<version>\\().*"));
        var document = DocumentBuilderFactory.newInstance().newDocumentBuilder()
                .parse(new ByteArrayInputStream(files.get("backend/pom.xml")));
        var dependencies = document.getElementsByTagName("dependency");
        assertEquals(8, dependencies.getLength());
        for (int i = 0; i < dependencies.getLength(); i++) {
            var dependency = (Element) dependencies.item(i);
            assertTrue(dependency.getElementsByTagName("version").item(0).getTextContent()
                    .matches("[0-9]+\\.[0-9]+\\.[0-9]+(?:\\.Final)?"));
        }
        var plugins = document.getElementsByTagName("plugin");
        assertEquals(3, plugins.getLength());
        for (int i = 0; i < plugins.getLength(); i++)
            assertTrue(((Element) plugins.item(i)).getElementsByTagName("version").item(0).getTextContent()
                    .matches("[0-9]+\\.[0-9]+\\.[0-9]+"));
        for (String key : List.of("GENERATED_APP_DB_URL", "GENERATED_APP_DB_USER",
                "GENERATED_APP_DB_PASSWORD", "GENERATED_APP_JWT_SECRET")) {
            assertTrue(config.contains("${" + key + "}"));
            assertTrue(readme.contains(key));
            assertFalse(config.contains("${" + key + ":"), "No default may be embedded");
        }
        assertTrue(config.contains("ddl-auto: validate"));
        assertTrue(config.contains("clean-disabled: true"));
        assertTrue(readme.contains("cannot yet start"));
        String all = files.values().stream().map(bytes -> new String(bytes, StandardCharsets.UTF_8))
                .reduce("", String::concat);
        files.entrySet().stream().filter(entry -> !entry.getKey().startsWith("contracts/"))
                .forEach(entry -> assertFalse(new String(entry.getValue(), StandardCharsets.UTF_8).contains("\r")));
        assertFalse(all.contains("OPENROUTER_API_KEY"));
        assertFalse(all.contains("eg.mts.gsuif"));
        assertFalse(all.contains("AssetTicket") || all.contains("Gadget") || all.contains("WorkOrder"));
        var again = renderer.render(descriptor, plan);
        for (String path : files.keySet()) assertArrayEquals(files.get(path), again.get(path));
        byte original = files.get(contractPath)[0]; files.get(contractPath)[0] ^= 1;
        assertEquals(original, renderer.render(descriptor, plan).get(contractPath)[0]);
    }

    @Test void runtimeScaffoldRejectsResourceDriftAndOwnershipErrors() throws Exception {
        var descriptor = preflight().describe(version(snapshot("condition")), specification(), OutputMode.BACKEND);
        var plan = GenerationFilePlan.forDescriptor(descriptor);
        var registry = new PackagedContractRegistry("/generation/stock-unit-contracts.yaml");
        var renderer = new CompleteBackendRuntimeRenderer(registry);
        var files = renderer.render(descriptor, plan);
        var frontend = preflight().describe(version(snapshot("condition")), specification(), OutputMode.FRONTEND);
        assertThrows(IllegalArgumentException.class, () -> renderer.render(frontend,
                GenerationFilePlan.forDescriptor(frontend)));
        var changed = new CompleteBackendRuntimeRenderer(registry, path -> {
            try (InputStream input = getClass().getResourceAsStream(path)) {
                byte[] bytes = input.readAllBytes(); bytes[0] ^= 1; return bytes;
            } catch (Exception ex) { throw new IllegalStateException(ex); }
        });
        assertThrows(IllegalArgumentException.class, () -> changed.render(descriptor, plan));
        var missing = new GenerationFilePlan(plan.entries().stream()
                .filter(entry -> !entry.path().equals("backend/pom.xml")).toList());
        assertThrows(IllegalArgumentException.class, () -> renderer.render(descriptor, missing));
        var wrongOwner = new GenerationFilePlan(plan.entries().stream().map(entry ->
                entry.path().equals("README.md") ? new Entry(entry.path(), Owner.MODEL, entry.purpose()) : entry).toList());
        assertThrows(IllegalArgumentException.class, () -> renderer.render(descriptor, wrongOwner));
        var extra = new TreeMap<>(files); extra.put("backend/Unexpected.txt", new byte[] {1});
        assertThrows(IllegalArgumentException.class, () -> CompleteBackendRuntimeRenderer.validate(plan,
                files.keySet(), extra));
        var omitted = new TreeMap<>(files); omitted.remove("README.md");
        assertThrows(IllegalArgumentException.class, () -> CompleteBackendRuntimeRenderer.validate(plan,
                files.keySet(), omitted));
    }

    @Test void runtimeScaffoldIncludesEnversOnlyWhenDescriptorRequiresIt() throws Exception {
        var base = specification();
        var audited = new GenerationSpecification(base.specVersion(), base.basePackage(),
                new EntitySpec(base.entity().className(), base.entity().tableName(),
                        base.entity().fields(), true), base.openApi(), base.operationRoles(), base.angular());
        var descriptor = preflight().describe(version(snapshot("condition")), audited, OutputMode.BACKEND);
        String pom = new String(new CompleteBackendRuntimeRenderer(new PackagedContractRegistry(
                "/generation/stock-unit-contracts.yaml"))
                .render(descriptor, GenerationFilePlan.forDescriptor(descriptor)).get("backend/pom.xml"),
                StandardCharsets.UTF_8);
        assertTrue(pom.contains("<artifactId>hibernate-envers</artifactId><version>7.2.24.Final</version>"));
    }

    @Test void apiAndControllerUseExactOperationsAndOwnedDeterministicBytes() throws Exception {
        var descriptor = preflight().describe(version(snapshot("condition")), specification(), OutputMode.BACKEND);
        var plan = GenerationFilePlan.forDescriptor(descriptor);
        var renderer = new CompleteBackendApiRenderer();
        var files = renderer.render(descriptor, plan);
        assertEquals(2, files.size());
        String api = new String(files.values().stream().filter(bytes -> new String(bytes, StandardCharsets.UTF_8)
                .contains("public interface")).findFirst().orElseThrow(), StandardCharsets.UTF_8);
        String controller = new String(files.values().stream().filter(bytes -> new String(bytes, StandardCharsets.UTF_8)
                .contains("class StockUnitController")).findFirst().orElseThrow(), StandardCharsets.UTF_8);
        for (String id : ids) assertTrue(api.contains(id + "("), id);
        assertTrue(api.contains("RequestMethod.GET, path = \"/api/v1/stock-units\""));
        assertTrue(api.contains("RequestMethod.POST, path = \"/api/v1/stock-units\""));
        assertTrue(api.contains("RequestMethod.PUT, path = \"/api/v1/stock-units/{id}\""));
        assertTrue(api.contains("RequestMethod.DELETE, path = \"/api/v1/stock-units/{id}\""));
        assertTrue(api.contains("RequestMethod.POST, path = \"/api/v1/stock-units\", produces = \"application/json\", consumes = \"application/json\""));
        assertTrue(api.contains("RequestMethod.PUT, path = \"/api/v1/stock-units/{id}\", produces = \"application/json\", consumes = \"application/json\""));
        assertTrue(api.contains("name = \"condition\", required = true"));
        assertTrue(api.contains("name = \"label\", required = false"));
        assertTrue(controller.contains("service.list(condition, label)"));
        assertTrue(controller.contains("service.create(request)"));
        assertTrue(controller.contains("service.getById(id)"));
        assertTrue(controller.contains("service.update(id, request)"));
        assertTrue(controller.contains("service.delete(id)"));
        assertTrue(controller.contains("ApiResponse.<Void>success(null, \"Success\", 200)"));
        assertTrue(controller.contains("status(201)"));
        assertTrue(controller.contains("hasAnyAuthority('ROLE_ADMIN')"));
        assertFalse(api.contains("eg.mts.gsuif"));
        assertFalse(controller.contains("AssetTicket") || controller.contains("Gadget") || controller.contains("WorkOrder"));
        var again = renderer.render(descriptor, plan);
        for (String path : files.keySet()) assertArrayEquals(files.get(path), again.get(path));
        byte original = files.values().iterator().next()[0];
        files.values().iterator().next()[0] ^= 1;
        assertEquals(original, renderer.render(descriptor, plan).values().iterator().next()[0]);
        var missing = new GenerationFilePlan(plan.entries().stream().filter(e -> !e.path().endsWith("StockUnitApi.java")).toList());
        assertThrows(IllegalArgumentException.class, () -> renderer.render(descriptor, missing));
        var wrongOwner = new GenerationFilePlan(plan.entries().stream().map(e -> e.path().endsWith("StockUnitController.java")
                ? new Entry(e.path(), Owner.MODEL, e.purpose()) : e).toList());
        assertThrows(IllegalArgumentException.class, () -> renderer.render(descriptor, wrongOwner));
        var extra = new TreeMap<>(again);
        extra.put("backend/Unexpected.java", new byte[] {1});
        assertThrows(IllegalArgumentException.class, () -> CompleteBackendApiRenderer.validate(plan, again.keySet(), extra));
    }

    @Test void transportRolesComeOnlyFromFrozenDescriptor() throws Exception {
        var base = specification();
        var roles = new TreeMap<>(base.operationRoles());
        roles.put("inspectStockUnit", Set.of(SupportedRole.ROLE_USER));
        var changed = new GenerationSpecification(base.specVersion(), base.basePackage(), base.entity(),
                base.openApi(), roles, base.angular());
        var descriptor = preflight().describe(version(snapshot("condition")), changed, OutputMode.BACKEND);
        roles.put("inspectStockUnit", Set.of(SupportedRole.ROLE_ADMIN));
        String controller = new String(new CompleteBackendApiRenderer()
                .render(descriptor, GenerationFilePlan.forDescriptor(descriptor)).entrySet().stream()
                .filter(e -> e.getKey().endsWith("Controller.java")).findFirst().orElseThrow().getValue(),
                StandardCharsets.UTF_8);
        assertTrue(controller.contains("hasAnyAuthority('ROLE_USER')\")\n    public org.springframework.http.ResponseEntity<com.example.inventory.api.ApiResponse<com.example.inventory.dto.StockUnitDto>> inspectStockUnit"));
        assertTrue(controller.contains("hasAnyAuthority('ROLE_ADMIN')"));
    }

    @Test void generatedControllerDelegatesAndPreservesSuccessEnvelopeAndDeclaredRoles(@TempDir Path scratch) throws Exception {
        var descriptor = preflight().describe(version(snapshot("condition")), specification(), OutputMode.BACKEND);
        var plan = GenerationFilePlan.forDescriptor(descriptor);
        var source = new TreeMap<>(new CompleteBackendDefinitionRenderer().render(descriptor, plan).files());
        source.putAll(new CompleteBackendApiRenderer().render(descriptor, plan));
        Path classes = Files.createDirectories(scratch.resolve("classes"));
        List<String> inputs = new ArrayList<>();
        for (var entry : source.entrySet()) {
            Path file = scratch.resolve(entry.getKey());
            Files.createDirectories(file.getParent()); Files.write(file, entry.getValue()); inputs.add(file.toString());
        }
        List<String> args = new ArrayList<>(List.of("-classpath", System.getProperty("java.class.path"),
                "-d", classes.toString()));
        args.addAll(inputs);
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null, args.toArray(String[]::new)));
        try (var loader = new URLClassLoader(new java.net.URL[]{classes.toUri().toURL()}, getClass().getClassLoader())) {
            ClassLoader previousLoader = Thread.currentThread().getContextClassLoader();
            Thread.currentThread().setContextClassLoader(loader);
            try {
            Class<?> serviceType = loader.loadClass("com.example.inventory.service.StockUnitService");
            Class<?> controllerType = loader.loadClass("com.example.inventory.controller.StockUnitController");
            Class<?> apiType = loader.loadClass("com.example.inventory.api.StockUnitApi");
            Class<?> dtoType = loader.loadClass("com.example.inventory.dto.StockUnitDto");
            Class<?> pageType = loader.loadClass("com.example.inventory.dto.StockUnitPage");
            Class<?> conditionType = loader.loadClass("com.example.inventory.dto.StockUnitCondition");
            Object condition = conditionType.getEnumConstants()[0];
            Object dto = dtoType.getConstructors()[0].newInstance(UUID.randomUUID(), "sample", condition, null);
            Object page = pageType.getConstructors()[0].newInstance(List.of(dto), 1, 1L, 1, 0);
            AtomicReference<String> call = new AtomicReference<>();
            AtomicInteger serviceCalls = new AtomicInteger();
            Object service = Proxy.newProxyInstance(loader, new Class<?>[]{serviceType}, (proxy, method, values) -> {
                serviceCalls.incrementAndGet();
                call.set(method.getName() + ":" + (values == null ? 0 : values.length));
                return method.getName().equals("list") ? page : method.getName().equals("delete") ? null : dto;
            });
            try (var context = new AnnotationConfigApplicationContext()) {
                context.register(GeneratedMethodSecurityOnly.class);
                context.registerBean((Class) controllerType, () -> {
                    try { return controllerType.getConstructor(serviceType).newInstance(service); }
                    catch (ReflectiveOperationException ex) { throw new IllegalStateException(ex); }
                });
                context.refresh();
                Object controller = context.getBean(apiType);
                SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                        "admin", "unused", List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))));
                String[] serviceMethods = {"list:2", "create:1", "getById:1", "update:2", "delete:1"};
                Object[][] values = {{condition, "a"}, {null}, {UUID.randomUUID()}, {UUID.randomUUID(), null}, {UUID.randomUUID()}};
                for (int i = 0; i < ids.size(); i++) {
                    String operationId = ids.get(i);
                    var method = java.util.Arrays.stream(apiType.getMethods())
                            .filter(m -> m.getName().equals(operationId)).findFirst().orElseThrow();
                    ResponseEntity<?> response = (ResponseEntity<?>) method.invoke(controller, values[i]);
                    assertEquals(i == 1 ? 201 : 200, response.getStatusCode().value());
                    assertEquals(serviceMethods[i], call.get());
                    Object envelope = response.getBody();
                    assertEquals(5, envelope.getClass().getRecordComponents().length);
                    assertEquals(response.getStatusCode().value(), envelope.getClass().getMethod("statusCode").invoke(envelope));
                    assertEquals("OK", envelope.getClass().getMethod("status").invoke(envelope));
                    assertNull(envelope.getClass().getMethod("errors").invoke(envelope));
                    assertEquals(i == 4 ? null : i == 0 ? page : dto,
                            envelope.getClass().getMethod("body").invoke(envelope));
                }
                SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                        "user", "unused", List.of(new SimpleGrantedAuthority("ROLE_USER"))));
                var read = java.util.Arrays.stream(apiType.getMethods())
                        .filter(m -> m.getName().equals("inspectStockUnit")).findFirst().orElseThrow();
                int callsBeforeDenied = serviceCalls.get();
                var denied = assertThrows(java.lang.reflect.InvocationTargetException.class,
                        () -> read.invoke(controller, UUID.randomUUID()));
                assertInstanceOf(AccessDeniedException.class, denied.getCause());
                assertEquals(callsBeforeDenied, serviceCalls.get(), "Denied call must not reach service");
            } finally { SecurityContextHolder.clearContext(); }
            } finally { Thread.currentThread().setContextClassLoader(previousLoader); }
        }
    }

    @Test void mockMvcInheritsInterfaceMappingsAndBindsTemporalQueryArguments(@TempDir Path scratch) throws Exception {
        var registry = new PackagedContractRegistry("/generation/stock-unit-temporal-contracts.yaml");
        var preflight = new GenerationCapabilityPreflight(
                new GenerationContextBuilder(mapper, new MetadataSchemaValidator(), registry), registry);
        var metadata = snapshot("condition");
        ObjectNode query = (ObjectNode) metadata.withArray("apiBindings").get(0).path("requestMapping").path("query");
        query.put("observedOn", "observedOn");
        query.put("changedAt", "changedAt");
        var descriptor = preflight.describe(version(metadata), specification(), OutputMode.BACKEND);
        var plan = GenerationFilePlan.forDescriptor(descriptor);
        var source = new TreeMap<>(new CompleteBackendDefinitionRenderer().render(descriptor, plan).files());
        source.putAll(new CompleteBackendApiRenderer().render(descriptor, plan));
        String api = new String(source.entrySet().stream().filter(e -> e.getKey().endsWith("StockUnitApi.java"))
                .findFirst().orElseThrow().getValue(), StandardCharsets.UTF_8);
        assertTrue(api.contains("DateTimeFormat.ISO.DATE)"));
        assertTrue(api.contains("DateTimeFormat.ISO.DATE_TIME)"));
        Path classes = Files.createDirectories(scratch.resolve("classes"));
        List<String> inputs = new ArrayList<>();
        for (var entry : source.entrySet()) {
            Path file = scratch.resolve(entry.getKey());
            Files.createDirectories(file.getParent()); Files.write(file, entry.getValue()); inputs.add(file.toString());
        }
        List<String> arguments = new ArrayList<>(List.of("-classpath", System.getProperty("java.class.path"),
                "-d", classes.toString()));
        arguments.addAll(inputs);
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null, arguments.toArray(String[]::new)));
        try (var loader = new URLClassLoader(new java.net.URL[]{classes.toUri().toURL()}, getClass().getClassLoader())) {
            ClassLoader previousLoader = Thread.currentThread().getContextClassLoader();
            Thread.currentThread().setContextClassLoader(loader);
            try {
                Class<?> serviceType = loader.loadClass("com.example.inventory.service.StockUnitService");
                Class<?> controllerType = loader.loadClass("com.example.inventory.controller.StockUnitController");
                Class<?> pageType = loader.loadClass("com.example.inventory.dto.StockUnitPage");
                Class<?> conditionType = loader.loadClass("com.example.inventory.dto.StockUnitCondition");
                Object page = pageType.getConstructors()[0].newInstance(List.of(), 0, 0L, 0, 0);
                AtomicReference<Object[]> received = new AtomicReference<>();
                AtomicInteger calls = new AtomicInteger();
                Object service = Proxy.newProxyInstance(loader, new Class<?>[]{serviceType}, (proxy, method, values) -> {
                    calls.incrementAndGet();
                    received.set(values == null ? new Object[0] : values.clone());
                    return method.getName().equals("list") ? page : null;
                });
                Object controller = controllerType.getConstructor(serviceType).newInstance(service);
                var mvc = MockMvcBuilders.standaloneSetup(controller).build();
                mvc.perform(get("/api/v1/stock-units").param("condition", "OPEN"))
                        .andExpect(status().isBadRequest());
                assertEquals(0, calls.get());
                mvc.perform(get("/api/v1/stock-units").param("condition", "OPEN")
                                .param("observedOn", "2026-10-15"))
                        .andExpect(status().isOk());
                assertEquals(1, calls.get());
                assertArrayEquals(new Object[]{null, conditionType.getEnumConstants()[0], null,
                        LocalDate.of(2026, 10, 15)}, received.get());
                mvc.perform(get("/api/v1/stock-units").param("condition", "CLOSED")
                                .param("label", "warehouse").param("observedOn", "2026-10-16")
                                .param("changedAt", "2026-10-15T12:34:56"))
                        .andExpect(status().isOk());
                assertEquals(2, calls.get());
                assertArrayEquals(new Object[]{LocalDateTime.of(2026, 10, 15, 12, 34, 56),
                        conditionType.getEnumConstants()[1], "warehouse", LocalDate.of(2026, 10, 16)}, received.get());
                mvc.perform(get("/api/v1/stock-units").param("condition", "OPEN")
                                .param("observedOn", "15-10-2026"))
                        .andExpect(status().isBadRequest());
                assertEquals(2, calls.get());
            } finally { Thread.currentThread().setContextClassLoader(previousLoader); }
        }
    }

    @Test void errorRendererOwnsOnlyItsSubsetAndUsesSelectedContractFields(@TempDir Path scratch) throws Exception {
        var descriptor = preflight().describe(version(snapshot("condition")), specification(), OutputMode.BACKEND);
        var plan = GenerationFilePlan.forDescriptor(descriptor);
        var handlerPath = "backend/src/main/java/com/example/inventory/exception/GlobalExceptionHandler.java";
        var missingPath = "backend/src/main/java/com/example/inventory/exception/RequestedRecordNotFoundException.java";
        assertFalse(plan.entries().stream().anyMatch(e -> e.path().endsWith("api/GlobalExceptionHandler.java")));
        assertEquals(Owner.DETERMINISTIC, plan.entries().stream().filter(e -> e.path().equals(handlerPath))
                .findFirst().orElseThrow().owner());
        var renderer = new CompleteBackendErrorRenderer();
        var files = renderer.render(descriptor, plan);
        assertEquals(Set.of(handlerPath, missingPath), files.keySet());
        String handler = new String(files.get(handlerPath), StandardCharsets.UTF_8);
        assertTrue(handler.contains("package com.example.inventory.exception;"));
        assertTrue(handler.contains("import com.example.inventory.api.ApiResponse;"));
        assertTrue(handler.contains("\"condition\"") && handler.contains("\"retiredOn\""));
        assertFalse(handler.contains("AssetTicket") || handler.contains("WorkOrder") || handler.contains("Gadget"));
        assertFalse(handler.contains("\r"));
        String serviceDefinition = new String(new CompleteBackendDefinitionRenderer().render(descriptor, plan).files()
                .entrySet().stream().filter(e -> e.getKey().endsWith("StockUnitService.java"))
                .findFirst().orElseThrow().getValue(), StandardCharsets.UTF_8);
        assertTrue(serviceDefinition.contains("RequestedRecordNotFoundException"));
        for (String path : files.keySet()) assertArrayEquals(files.get(path), renderer.render(descriptor, plan).get(path));
        byte original = files.get(handlerPath)[0];
        files.get(handlerPath)[0] ^= 1;
        assertEquals(original, renderer.render(descriptor, plan).get(handlerPath)[0]);
        assertThrows(UnsupportedOperationException.class, files::clear);
        var wrongOwner = new GenerationFilePlan(plan.entries().stream().map(e -> e.path().equals(handlerPath)
                ? new Entry(e.path(), Owner.MODEL, e.purpose()) : e).toList());
        assertThrows(IllegalArgumentException.class, () -> renderer.render(descriptor, wrongOwner));
        var absent = new GenerationFilePlan(plan.entries().stream().filter(e -> !e.path().equals(missingPath)).toList());
        assertThrows(IllegalArgumentException.class, () -> renderer.render(descriptor, absent));

        var gadgetRegistry = new PackagedContractRegistry("/generation/gadget-contracts.yaml");
        var gadgetPreflight = new GenerationCapabilityPreflight(
                new GenerationContextBuilder(mapper, new MetadataSchemaValidator(), gadgetRegistry), gadgetRegistry);
        var names = List.of("fetchGadgets", "submitGadget", "findGadget", "replaceGadget", "removeGadget");
        Map<UUID, String> bindings = new java.util.LinkedHashMap<>();
        Map<String, Set<SupportedRole>> roles = new java.util.LinkedHashMap<>();
        for (int i = 0; i < names.size(); i++) {
            bindings.put(new UUID(0, i + 1), names.get(i));
            roles.put(names.get(i), Set.of(SupportedRole.ROLE_ADMIN));
        }
        var spec = new GenerationSpecification("1.0.0", "com.example.fixture",
                new EntitySpec("Gadget", "gadget", List.of(
                        new FieldSpec("title", "title", "String", false, 100, null),
                        new FieldSpec("status", "status", "GadgetStatus", false, null, "GadgetStatus"),
                        new FieldSpec("note", "note", "String", true, 100, null)), false),
                new OpenApiSpec("gadget-api", "2.0.0", bindings), roles, new AngularSpec("Gadget", Map.of()));
        var metadata = snapshot("condition");
        for (int i = 0; i < 5; i++) {
            var binding = (ObjectNode) metadata.withArray("apiBindings").get(i);
            binding.put("endpointUrl", binding.path("endpointUrl").asText().replace("stock-units", "gadgets"));
            if (i == 0) {
                var query = (ObjectNode) binding.path("requestMapping").path("query");
                query.removeAll(); query.put("status", "status");
            }
            if (i == 1 || i == 3) {
                var body = (ObjectNode) binding.path("requestMapping").path("body");
                body.removeAll(); body.put("title", "title").put("status", "status").put("note", "note");
            }
        }
        var gadget = gadgetPreflight.describe(version(metadata), spec, OutputMode.BACKEND);
        var gadgetFiles = renderer.render(gadget, GenerationFilePlan.forDescriptor(gadget));
        String gadgetHandler = new String(gadgetFiles.entrySet().stream()
                .filter(e -> e.getKey().endsWith("GlobalExceptionHandler.java")).findFirst().orElseThrow().getValue(),
                StandardCharsets.UTF_8);
        assertTrue(gadgetHandler.contains("package com.example.fixture.exception;"));
        assertTrue(gadgetHandler.contains("\"title\"") && gadgetHandler.contains("\"status\""));
        assertFalse(gadgetHandler.contains("\"condition\"") || gadgetHandler.contains("\"retiredOn\""));
        var gadgetSources = new TreeMap<>(new CompleteBackendDefinitionRenderer()
                .render(gadget, GenerationFilePlan.forDescriptor(gadget)).files());
        gadgetSources.putAll(new CompleteBackendApiRenderer().render(gadget, GenerationFilePlan.forDescriptor(gadget)));
        gadgetSources.putAll(gadgetFiles);
        Path classes = Files.createDirectories(scratch.resolve("classes"));
        List<String> sourcePaths = new ArrayList<>();
        for (var entry : gadgetSources.entrySet()) {
            Path file = scratch.resolve(entry.getKey());
            Files.createDirectories(file.getParent()); Files.write(file, entry.getValue()); sourcePaths.add(file.toString());
        }
        List<String> compiler = new ArrayList<>(List.of("-classpath", System.getProperty("java.class.path"),
                "-d", classes.toString()));
        compiler.addAll(sourcePaths);
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null, compiler.toArray(String[]::new)));
        try (var loader = new URLClassLoader(new java.net.URL[]{classes.toUri().toURL()}, getClass().getClassLoader())) {
            ClassLoader previous = Thread.currentThread().getContextClassLoader();
            Thread.currentThread().setContextClassLoader(loader);
            try {
                Class<?> serviceType = loader.loadClass("com.example.fixture.service.GadgetService");
                Class<?> controllerType = loader.loadClass("com.example.fixture.controller.GadgetController");
                Class<?> handlerType = loader.loadClass("com.example.fixture.exception.GlobalExceptionHandler");
                AtomicInteger calls = new AtomicInteger();
                Object service = Proxy.newProxyInstance(loader, new Class<?>[]{serviceType}, (proxy, method, args) -> {
                    calls.incrementAndGet(); return null;
                });
                var validator = new org.springframework.validation.beanvalidation.LocalValidatorFactoryBean();
                validator.afterPropertiesSet();
                try {
                    var json = tools.jackson.databind.json.JsonMapper.builder().build();
                    var mvc = MockMvcBuilders.standaloneSetup(controllerType.getConstructor(serviceType).newInstance(service))
                            .setControllerAdvice(handlerType.getConstructor().newInstance()).setValidator(validator)
                            .setMessageConverters(new org.springframework.http.converter.json.JacksonJsonHttpMessageConverter(json))
                            .build();
                    var response = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                            .post("/api/v1/gadgets").contentType("application/json")
                            .content("{\"title\":null,\"status\":\"OPEN\",\"note\":null}"))
                            .andExpect(status().isBadRequest()).andReturn().getResponse();
                    assertGeneratedError(json, response, 400, "BAD_REQUEST", "Invalid request", "title", "Invalid value");
                    assertEquals(0, calls.get());
                } finally { validator.close(); }
            } finally { Thread.currentThread().setContextClassLoader(previous); }
        }
    }

    @Test void emittedErrorHandlerReturnsSafeExactEnvelopes(@TempDir Path scratch) throws Exception {
        var descriptor = preflight().describe(version(snapshot("condition")), specification(), OutputMode.BACKEND);
        var plan = GenerationFilePlan.forDescriptor(descriptor);
        var files = new TreeMap<>(new CompleteBackendDefinitionRenderer().render(descriptor, plan).files());
        files.putAll(new CompleteBackendApiRenderer().render(descriptor, plan));
        files.putAll(new CompleteBackendErrorRenderer().render(descriptor, plan));
        Path classes = Files.createDirectories(scratch.resolve("classes"));
        List<String> sources = new ArrayList<>();
        for (var entry : files.entrySet()) {
            Path file = scratch.resolve(entry.getKey());
            Files.createDirectories(file.getParent()); Files.write(file, entry.getValue()); sources.add(file.toString());
        }
        List<String> compiler = new ArrayList<>(List.of("-classpath", System.getProperty("java.class.path"),
                "-d", classes.toString()));
        compiler.addAll(sources);
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null, compiler.toArray(String[]::new)));
        try (var loader = new URLClassLoader(new java.net.URL[]{classes.toUri().toURL()}, getClass().getClassLoader())) {
            ClassLoader previous = Thread.currentThread().getContextClassLoader();
            Thread.currentThread().setContextClassLoader(loader);
            try {
                Class<?> serviceType = loader.loadClass("com.example.inventory.service.StockUnitService");
                Class<?> controllerType = loader.loadClass("com.example.inventory.controller.StockUnitController");
                Class<?> handlerType = loader.loadClass("com.example.inventory.exception.GlobalExceptionHandler");
                Class<?> missingType = loader.loadClass("com.example.inventory.exception.RequestedRecordNotFoundException");
                AtomicInteger calls = new AtomicInteger();
                AtomicReference<RuntimeException> failure = new AtomicReference<>();
                Object service = Proxy.newProxyInstance(loader, new Class<?>[]{serviceType}, (proxy, method, args) -> {
                    calls.incrementAndGet();
                    if (failure.get() != null) throw failure.get();
                    return null;
                });
                Object controller = controllerType.getConstructor(serviceType).newInstance(service);
                var json = tools.jackson.databind.json.JsonMapper.builder().build();
                var validator = new org.springframework.validation.beanvalidation.LocalValidatorFactoryBean();
                validator.afterPropertiesSet();
                try {
                    var mvc = MockMvcBuilders.standaloneSetup(controller).setControllerAdvice(handlerType.getConstructor().newInstance())
                            .setValidator(validator)
                            .setMessageConverters(new org.springframework.http.converter.json.JacksonJsonHttpMessageConverter(json))
                            .build();
                    var badField = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                            .post("/api/v1/stock-units").contentType("application/json")
                            .content("{\"label\":null,\"condition\":\"OPEN\",\"retiredOn\":null}"))
                            .andExpect(status().isBadRequest()).andReturn().getResponse();
                    assertGeneratedError(json, badField, 400, "BAD_REQUEST", "Invalid request", "label", "Invalid value");
                    var omitted = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                            .put("/api/v1/stock-units/00000000-0000-0000-0000-000000000001")
                            .contentType("application/json").content("{\"label\":\"A\",\"condition\":\"OPEN\"}"))
                            .andExpect(status().isBadRequest()).andReturn().getResponse();
                    assertGeneratedError(json, omitted, 400, "BAD_REQUEST", "Invalid request", "_global", "Invalid request body");
                    var missingQuery = mvc.perform(get("/api/v1/stock-units"))
                            .andExpect(status().isBadRequest()).andReturn().getResponse();
                    assertGeneratedError(json, missingQuery, 400, "BAD_REQUEST", "Invalid request",
                            "condition", "Required parameter is missing");
                    var invalidQuery = mvc.perform(get("/api/v1/stock-units").param("condition", "rejected-secret"))
                            .andExpect(status().isBadRequest()).andReturn().getResponse();
                    assertGeneratedError(json, invalidQuery, 400, "BAD_REQUEST", "Invalid request",
                            "condition", "Invalid value");
                    assertFalse(invalidQuery.getContentAsString().contains("rejected-secret"));
                    var malformed = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                            .post("/api/v1/stock-units").contentType("application/json").content("{bad-json"))
                            .andExpect(status().isBadRequest()).andReturn().getResponse();
                    assertGeneratedError(json, malformed, 400, "BAD_REQUEST", "Invalid request",
                            "_global", "Invalid request body");
                    assertEquals(0, calls.get(), "Invalid requests must not reach the service");
                    failure.set((RuntimeException) missingType.getConstructor().newInstance());
                    var notFound = mvc.perform(get("/api/v1/stock-units/00000000-0000-0000-0000-000000000001"))
                            .andExpect(status().isNotFound()).andReturn().getResponse();
                    assertGeneratedError(json, notFound, 404, "NOT_FOUND", "Resource not found",
                            "_global", "Resource not found");
                    failure.set(new jakarta.persistence.EntityNotFoundException("persistence secret"));
                    var unrelated = mvc.perform(get("/api/v1/stock-units/00000000-0000-0000-0000-000000000001"))
                            .andExpect(status().isInternalServerError()).andReturn().getResponse();
                    assertGeneratedError(json, unrelated, 500, "INTERNAL_SERVER_ERROR", "Unexpected server error",
                            "_global", "Unexpected server error");
                    assertFalse(unrelated.getContentAsString().contains("persistence secret"));
                    failure.set(new IllegalStateException("provider secret"));
                    var unexpected = mvc.perform(get("/api/v1/stock-units/00000000-0000-0000-0000-000000000001"))
                            .andExpect(status().isInternalServerError()).andReturn().getResponse();
                    assertGeneratedError(json, unexpected, 500, "INTERNAL_SERVER_ERROR", "Unexpected server error",
                            "_global", "Unexpected server error");
                    assertFalse(unexpected.getContentAsString().contains("provider secret"));
                    failure.set(null);
                    var methodNotAllowed = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                            .patch("/api/v1/stock-units/00000000-0000-0000-0000-000000000001"))
                            .andExpect(status().isMethodNotAllowed()).andReturn().getResponse();
                    assertGeneratedError(json, methodNotAllowed, 405, "METHOD_NOT_ALLOWED", "Method not allowed",
                            "_global", "Method not allowed");
                    String allow = methodNotAllowed.getHeader("Allow");
                    assertNotNull(allow);
                    Set<String> methods = java.util.Arrays.stream(allow.split(","))
                            .map(String::trim).collect(java.util.stream.Collectors.toSet());
                    assertTrue(methods.containsAll(Set.of("GET", "PUT", "DELETE")), allow);
                    assertFalse(methods.contains("PATCH"), allow);
                    var unsupportedMedia = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                            .post("/api/v1/stock-units").contentType("text/plain").content("invalid"))
                            .andExpect(status().isUnsupportedMediaType()).andReturn().getResponse();
                    assertGeneratedError(json, unsupportedMedia, 415, "UNSUPPORTED_MEDIA_TYPE", "Unsupported media type",
                            "_global", "Unsupported media type");
                    assertTrue(unsupportedMedia.getHeaders("Accept").stream()
                            .anyMatch(value -> value.contains("application/json")));
                } finally { validator.close(); }
            } finally { Thread.currentThread().setContextClassLoader(previous); }
        }
    }

    private static void assertGeneratedError(tools.jackson.databind.json.JsonMapper json,
                                             org.springframework.mock.web.MockHttpServletResponse response,
                                             int code, String statusName, String message, String key, String value) throws Exception {
        var body = json.readTree(response.getContentAsByteArray());
        assertEquals(5, body.size());
        assertEquals(statusName, body.get("status").asText());
        assertEquals(message, body.get("clientMessage").asText());
        assertEquals(code, body.get("statusCode").asInt());
        assertTrue(body.get("body").isNull());
        assertEquals(1, body.get("errors").size());
        assertEquals(value, body.get("errors").get(key).asText());
    }

    @Test void emittedRequestsDistinguishRequiredPresenceAndNullWithPinnedJackson(@TempDir Path scratch) throws Exception {
        var descriptor = preflight().describe(version(snapshot("condition")), specification(), OutputMode.BACKEND);
        var plan = GenerationFilePlan.forDescriptor(descriptor);
        var files = new TreeMap<>(new CompleteBackendDefinitionRenderer().render(descriptor, plan).files());
        files.putAll(new CompleteBackendApiRenderer().render(descriptor, plan));
        Path classes = Files.createDirectories(scratch.resolve("classes"));
        List<String> sources = new ArrayList<>();
        for (var entry : files.entrySet()) {
            Path file = scratch.resolve(entry.getKey());
            Files.createDirectories(file.getParent());
            Files.write(file, entry.getValue());
            sources.add(file.toString());
        }
        List<String> compiler = new ArrayList<>(List.of("-classpath", System.getProperty("java.class.path"),
                "-d", classes.toString()));
        compiler.addAll(sources);
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null, compiler.toArray(String[]::new)));
        try (var loader = new URLClassLoader(new java.net.URL[]{classes.toUri().toURL()}, getClass().getClassLoader())) {
            ClassLoader previous = Thread.currentThread().getContextClassLoader();
            Thread.currentThread().setContextClassLoader(loader);
            try {
                var json = tools.jackson.databind.json.JsonMapper.builder().build();
                var validator = new org.springframework.validation.beanvalidation.LocalValidatorFactoryBean();
                validator.afterPropertiesSet();
                try {
                    for (String requestName : List.of("CreateStockUnitRequest", "UpdateStockUnitRequest")) {
                        Class<?> requestType = loader.loadClass("com.example.inventory.dto." + requestName);
                        Object explicitNull = json.readValue("{\"label\":\"A\",\"condition\":\"OPEN\",\"retiredOn\":null}", requestType);
                        assertNull(requestType.getMethod("retiredOn").invoke(explicitNull));
                        assertTrue(validator.validate(explicitNull).isEmpty());
                        assertThrows(tools.jackson.core.JacksonException.class,
                                () -> json.readValue("{\"label\":\"A\",\"condition\":\"OPEN\"}", requestType),
                                "Required nullable field must reject omission");
                        for (String missing : List.of(
                                "{\"condition\":\"OPEN\",\"retiredOn\":null}",
                                "{\"label\":\"A\",\"retiredOn\":null}"))
                            assertThrows(tools.jackson.core.JacksonException.class,
                                    () -> json.readValue(missing, requestType));
                        for (var fieldAndJson : Map.of(
                                "label", "{\"label\":null,\"condition\":\"OPEN\",\"retiredOn\":null}",
                                "condition", "{\"label\":\"A\",\"condition\":null,\"retiredOn\":null}").entrySet()) {
                            Object nullNonnullable = json.readValue(fieldAndJson.getValue(), requestType);
                            assertTrue(validator.validate(nullNonnullable).stream().anyMatch(v ->
                                    v.getPropertyPath().toString().equals(fieldAndJson.getKey())));
                        }
                    }
                    Class<?> serviceType = loader.loadClass("com.example.inventory.service.StockUnitService");
                    Class<?> controllerType = loader.loadClass("com.example.inventory.controller.StockUnitController");
                    AtomicInteger calls = new AtomicInteger();
                    AtomicReference<Object[]> received = new AtomicReference<>();
                    Object service = Proxy.newProxyInstance(loader, new Class<?>[]{serviceType}, (proxy, method, args) -> {
                        calls.incrementAndGet();
                        received.set(args == null ? null : args.clone());
                        return null;
                    });
                    Object controller = controllerType.getConstructor(serviceType).newInstance(service);
                    var mvc = MockMvcBuilders.standaloneSetup(controller).setValidator(validator)
                            .setMessageConverters(new org.springframework.http.converter.json.JacksonJsonHttpMessageConverter(json))
                            .build();
                    String valid = "{\"label\":\"A\",\"condition\":\"OPEN\",\"retiredOn\":null}";
                    String omittedNullable = "{\"label\":\"A\",\"condition\":\"OPEN\"}";
                    String missingRequired = "{\"condition\":\"OPEN\",\"retiredOn\":null}";
                    String nullRequired = "{\"label\":null,\"condition\":\"OPEN\",\"retiredOn\":null}";
                    String missingCondition = "{\"label\":\"A\",\"retiredOn\":null}";
                    String nullCondition = "{\"label\":\"A\",\"condition\":null,\"retiredOn\":null}";
                    for (String path : List.of("/api/v1/stock-units", "/api/v1/stock-units/00000000-0000-0000-0000-000000000001")) {
                        boolean create = !path.contains("/00000000-");
                        int callsBeforePath = calls.get();
                        for (String invalid : List.of(omittedNullable, missingRequired, nullRequired,
                                missingCondition, nullCondition)) {
                            var request = create ? org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(path)
                                    : org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put(path);
                            mvc.perform(request.contentType(org.springframework.http.MediaType.APPLICATION_JSON).content(invalid))
                                    .andExpect(status().isBadRequest());
                            assertEquals(callsBeforePath, calls.get(), "Rejected request must not invoke the service");
                        }
                        var request = create ? org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(path)
                                : org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put(path);
                        mvc.perform(request.contentType(org.springframework.http.MediaType.APPLICATION_JSON).content(valid))
                                .andExpect(create ? status().isCreated() : status().isOk());
                        assertEquals(callsBeforePath + 1, calls.get());
                        Object accepted = received.get()[create ? 0 : 1];
                        assertNull(accepted.getClass().getMethod("retiredOn").invoke(accepted));
                    }
                } finally { validator.close(); }
            } finally { Thread.currentThread().setContextClassLoader(previous); }
        }
    }

    @Test void backendDefinitionsUseOnlyRequiredSubsetAndBindExactBytes() throws Exception {
        var descriptor = preflight().describe(version(snapshot("condition")), specification(), OutputMode.BACKEND);
        var plan = GenerationFilePlan.forDescriptor(descriptor);
        var rendered = new CompleteBackendDefinitionRenderer().render(descriptor, plan);
        var required = CompleteBackendDefinitionRenderer.requiredDefinitionPaths(descriptor,
                rendered.handoff().specification());
        assertEquals(required, rendered.files().keySet());
        assertEquals(rendered.handoff().definitions().size(), required.size());
        assertTrue(required.stream().anyMatch(path -> path.endsWith("entity/AuditableEntity.java")));
        var audit = rendered.handoff().definitions().stream()
                .filter(definition -> definition.role().equals("audit base")).findFirst().orElseThrow();
        assertTrue(audit.path().endsWith("entity/AuditableEntity.java"));
        assertArrayEquals(rendered.files().get(audit.path()), audit.bytes());
        assertFalse(required.contains("backend/pom.xml"));
        assertTrue(plan.entries().stream().anyMatch(e -> e.path().equals("backend/pom.xml")));
        String all = rendered.files().values().stream()
                .map(bytes -> new String(bytes, StandardCharsets.UTF_8)).reduce("", String::concat);
        assertFalse(all.contains("eg.mts.gsuif"));
        assertFalse(all.contains("AssetTicket"));
        assertFalse(all.contains("Gadget"));
        assertFalse(all.contains("scanStockUnits")); // operation IDs stay in the typed handoff, not source signatures
        assertEquals(ids, rendered.handoff().specification().operations().stream().map(
                CompleteOutputDescriptor.Operation::operationId).toList());
        assertTrue(all.contains("StockUnitPage list(com.example.inventory.dto.StockUnitCondition condition, String label)"));
        assertTrue(all.contains("required = true"));
        assertTrue(all.contains("java.time.LocalDate retiredOn"));
        assertTrue(all.contains("enum StockUnitCondition { OPEN, CLOSED }"));
        assertFalse(rendered.files().keySet().stream().anyMatch(p -> p.endsWith("StockUnitLabel.java")));
        for (var definition : rendered.handoff().definitions()) {
            byte[] emitted = rendered.files().get(definition.path());
            assertArrayEquals(emitted, definition.bytes());
            assertEquals(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(emitted)),
                    definition.sha256());
        }
        var again = new CompleteBackendDefinitionRenderer().render(descriptor, plan);
        for (String path : required) assertArrayEquals(rendered.files().get(path), again.files().get(path));
        var mutable = rendered.files().get(rendered.handoff().definitions().getFirst().path());
        byte original = mutable[0]; mutable[0] ^= 1;
        assertEquals(original, rendered.files().get(rendered.handoff().definitions().getFirst().path())[0]);
        var handoffBytes = rendered.handoff().definitions().getFirst().bytes();
        handoffBytes[0] ^= 1;
        assertEquals(original, rendered.handoff().definitions().getFirst().bytes()[0]);
    }

    @Test void definitionSubsetRejectsMissingExtraAndIncorrectOwnership() throws Exception {
        var descriptor = preflight().describe(version(snapshot("condition")), specification(), OutputMode.BACKEND);
        var plan = GenerationFilePlan.forDescriptor(descriptor);
        var rendered = new CompleteBackendDefinitionRenderer().render(descriptor, plan);
        var handoff = rendered.handoff().specification();
        var missing = new TreeMap<>(rendered.files());
        missing.remove(handoff.definitions().getFirst().path());
        assertThrows(IllegalArgumentException.class, () -> CompleteBackendDefinitionRenderer.validateDefinitions(
                descriptor, plan, handoff, missing));
        var extra = new TreeMap<>(rendered.files());
        extra.put("backend/src/main/java/com/example/inventory/Unexpected.java", new byte[] {1});
        assertThrows(IllegalArgumentException.class, () -> CompleteBackendDefinitionRenderer.validateDefinitions(
                descriptor, plan, handoff, extra));
        var wrongOwner = new GenerationFilePlan(plan.entries().stream().map(entry ->
                entry.path().endsWith("entity/AuditableEntity.java")
                        ? new Entry(entry.path(), Owner.MODEL, entry.purpose()) : entry).toList());
        assertThrows(IllegalArgumentException.class, () -> CompleteBackendDefinitionRenderer.validateDefinitions(
                descriptor, wrongOwner, handoff, rendered.files()));
        var noAudit = new GenerationFilePlan(plan.entries().stream()
                .filter(entry -> !entry.path().endsWith("entity/AuditableEntity.java")).toList());
        assertThrows(IllegalArgumentException.class, () -> CompleteBackendDefinitionRenderer.validateDefinitions(
                descriptor, noAudit, handoff, rendered.files()));
        var withoutDeferred = new GenerationFilePlan(plan.entries().stream()
                .filter(entry -> !entry.path().equals("backend/pom.xml")
                        && !entry.path().endsWith("Controller.java")).toList());
        assertEquals(rendered.files().keySet(), new CompleteBackendDefinitionRenderer()
                .render(descriptor, withoutDeferred).files().keySet());
    }

    @Test void deterministicDefinitionsCompileWithoutFixtureSources(@TempDir Path scratch) throws Exception {
        var descriptor = preflight().describe(version(snapshot("condition")), specification(), OutputMode.BACKEND);
        var rendered = new CompleteBackendDefinitionRenderer().render(descriptor,
                GenerationFilePlan.forDescriptor(descriptor));
        List<String> sources = new ArrayList<>();
        for (var entry : rendered.files().entrySet()) {
            Path file = scratch.resolve(entry.getKey());
            Files.createDirectories(file.getParent());
            Files.write(file, entry.getValue());
            sources.add(file.toString());
        }
        Path classes = Files.createDirectories(scratch.resolve("classes"));
        List<String> arguments = new ArrayList<>(List.of("-classpath", System.getProperty("java.class.path"),
                "-d", classes.toString()));
        arguments.addAll(sources);
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null,
                arguments.toArray(String[]::new)));
    }

    @Test void exactContractAndRenamedQueryProduceStableDescriptorAndHandoff() throws Exception {
        var preflight = preflight();
        var spec = specification();
        var version = version(snapshot("condition"));
        var descriptor = preflight.describe(version, spec, OutputMode.BACKEND);
        assertEquals("stock-unit-api", descriptor.contractId());
        assertEquals("2.0.0", descriptor.contractVersion());
        assertEquals("com.example.inventory.dto", descriptor.contractDtoPackage());
        assertEquals("StockUnitService", descriptor.contractServiceName());
        assertEquals(1, descriptor.metadataVersionNumber());
        assertEquals("/inventory", descriptor.pageRoute());
        assertEquals(64, descriptor.contractResourceSha256().length());
        assertEquals(List.of("OPEN", "CLOSED"), descriptor.editableFields().get("condition").values());
        assertTrue(descriptor.updateRules().get("retiredOn").required());
        assertTrue(descriptor.updateRules().get("retiredOn").nullable());
        assertTrue(descriptor.createRules().get("retiredOn").required());
        assertTrue(descriptor.createFields().get("retiredOn").nullable());
        assertTrue(descriptor.editableFields().get("retiredOn").nullable());
        assertEquals(List.of(CrudAction.LIST, CrudAction.CREATE, CrudAction.READ, CrudAction.UPDATE, CrudAction.DELETE),
                descriptor.operations().stream().map(CompleteOutputDescriptor.Operation::action).toList());
        assertEquals(ids, descriptor.operations().stream().map(CompleteOutputDescriptor.Operation::operationId).toList());
        assertEquals(Set.of("condition", "label"), descriptor.operations().getFirst().queryFields().keySet());
        assertTrue(descriptor.operations().getFirst().queryFields().get("condition").required());
        assertEquals("StockUnitCondition", descriptor.operations().getFirst().queryFields().get("condition").schema().enumName());
        var scalar = descriptor.operations().getFirst().queryFields().get("label");
        assertFalse(scalar.required());
        assertEquals("string", scalar.schema().type());
        assertEquals("", scalar.schema().enumName());
        assertTrue(scalar.schema().values().isEmpty());
        assertEquals(201, descriptor.operations().get(1).successStatus());
        assertEquals("StockUnitDtoResponse", descriptor.operations().get(1).successEnvelope());
        assertEquals(Map.of(400, "StockUnitErrorResponse", 404, "StockUnitErrorResponse"),
                descriptor.operations().get(3).errorEnvelopes());
        assertEquals(descriptor, preflight.describe(version, spec, OutputMode.BACKEND));

        var plan = GenerationFilePlan.forDescriptor(descriptor);
        String modelPath = "backend/src/main/java/com/example/inventory/service/StockUnitServiceImpl.java";
        assertEquals(List.of(modelPath), plan.entries().stream().filter(e -> e.owner() == Owner.MODEL)
                .map(Entry::path).toList());
        assertTrue(plan.entries().stream().anyMatch(e -> e.path().endsWith("UpdateStockUnitRequest.java")
                && e.owner() == Owner.DETERMINISTIC));
        assertTrue(plan.entries().stream().noneMatch(e -> e.path().endsWith("StockUnitLabel.java")));
        var handoff = ModelHandoffSpec.from(descriptor, plan);
        assertEquals(List.of(modelPath), handoff.writablePaths());
        assertEquals(10, handoff.definitions().size());
        assertTrue(handoff.definitions().stream().anyMatch(d -> d.role().equals("audit base")
                && d.path().endsWith("entity/AuditableEntity.java")));
        assertTrue(handoff.definitions().stream().anyMatch(d -> d.role().equals("page DTO")
                && d.path().endsWith("StockUnitPage.java")));
        assertTrue(handoff.definitions().stream().anyMatch(d -> d.role().equals("contract enum")
                && d.path().endsWith("StockUnitCondition.java")));
        assertTrue(handoff.definitions().stream().noneMatch(d -> d.path().endsWith("StockUnitLabel.java")));
        assertTrue(handoff.definitions().stream().allMatch(d -> plan.entries().stream().anyMatch(e ->
                e.path().equals(d.path()) && e.owner() == Owner.DETERMINISTIC)));
        assertEquals("date", handoff.editableFields().get("retiredOn").format());
        assertTrue(handoff.updateRules().get("retiredOn").required());
        assertEquals(descriptor.contractResourceSha256(), handoff.contractResourceSha256());
        assertEquals(List.of("label", "condition", "retiredOn"),
                handoff.fields().stream().map(FieldSpec::name).toList());
        assertTrue(handoff.definitions().stream().noneMatch(d -> d.path().contains("Gadget") || d.path().contains("AssetTicket")));
        var withoutPage = new GenerationFilePlan(plan.entries().stream()
                .filter(e -> !e.path().endsWith("StockUnitPage.java")).toList());
        assertThrows(IllegalArgumentException.class, () -> ModelHandoffSpec.from(descriptor, withoutPage));
        var withoutEnum = new GenerationFilePlan(plan.entries().stream()
                .filter(e -> !e.path().endsWith("StockUnitCondition.java")).toList());
        assertThrows(IllegalArgumentException.class, () -> ModelHandoffSpec.from(descriptor, withoutEnum));
        var withoutAudit = new GenerationFilePlan(plan.entries().stream()
                .filter(e -> !e.path().endsWith("entity/AuditableEntity.java")).toList());
        assertThrows(IllegalArgumentException.class, () -> ModelHandoffSpec.from(descriptor, withoutAudit));
    }

    @Test void registeredContractRejectsJavaKeywordQueryAndOperationNames() throws Exception {
        var queryRegistry = new PackagedContractRegistry(
                "/generation/stock-unit-keyword-query-contracts.yaml");
        var queryPreflight = new GenerationCapabilityPreflight(
                new GenerationContextBuilder(mapper, new MetadataSchemaValidator(), queryRegistry), queryRegistry);
        var invalidQuery = assertThrows(GenerationValidationException.class,
                () -> queryPreflight.describe(version(snapshot("class")), specification(), OutputMode.BACKEND));
        assertTrue(invalidQuery.getMessage().contains("parameters.class: unsupported parameter shape"),
                invalidQuery.getMessage());
        assertThrows(IllegalStateException.class, () -> new PackagedContractRegistry(
                "/generation/stock-unit-keyword-operation-contracts.yaml"));
        var valid = preflight().describe(version(snapshot("condition")), specification(), OutputMode.BACKEND);
        assertTrue(new CompleteBackendApiRenderer().render(valid, GenerationFilePlan.forDescriptor(valid))
                .keySet().stream().anyMatch(path -> path.endsWith("StockUnitApi.java")));
    }

    @Test void unsupportedRequestConstraintsFailBeforeRendering() throws Exception {
        var registry = new PackagedContractRegistry("/generation/stock-unit-constrained-contracts.yaml");
        var preflight = new GenerationCapabilityPreflight(
                new GenerationContextBuilder(mapper, new MetadataSchemaValidator(), registry), registry);
        var failure = assertThrows(GenerationValidationException.class,
                () -> preflight.describe(version(snapshot("condition")), specification(), OutputMode.BACKEND));
        assertTrue(failure.diagnostics().stream().anyMatch(message ->
                message.contains("contract.create.label: unsupported request-field schema")));
        assertTrue(failure.diagnostics().stream().anyMatch(message ->
                message.contains("contract.update.label: unsupported request-field schema")));
    }

    @Test void modesAndPathAliasesHaveExclusiveOwnership() throws Exception {
        var spec = specification();
        var version = version(snapshot("condition"));
        var frontend = GenerationFilePlan.forDescriptor(preflight().describe(version, spec, OutputMode.FRONTEND));
        assertTrue(frontend.entries().stream().noneMatch(e -> e.owner() == Owner.MODEL));
        assertTrue(frontend.entries().stream().anyMatch(e -> e.path().endsWith("stock-unit.behavior.ts")));
        var full = GenerationFilePlan.forDescriptor(preflight().describe(version, spec, OutputMode.FULL_STACK));
        assertEquals(1, full.entries().stream().filter(e -> e.owner() == Owner.MODEL).count());
        assertThrows(IllegalArgumentException.class, () -> new GenerationFilePlan(List.of(
                new Entry("backend/src/App.java", Owner.DETERMINISTIC, "a"),
                new Entry("BACKEND/src/app.java", Owner.MODEL, "b"))));
        assertThrows(IllegalArgumentException.class, () -> new GenerationFilePlan(List.of(
                new Entry("../escape.java", Owner.MODEL, "bad"))));
        assertThrows(IllegalArgumentException.class, () -> new GenerationFilePlan(List.of(
                new Entry("backend/CON.java", Owner.MODEL, "bad"))));
        assertThrows(IllegalArgumentException.class, () -> new GenerationFilePlan(List.of(
                new Entry("backend/file", Owner.MODEL, "file"),
                new Entry("BACKEND/FILE/child.java", Owner.DETERMINISTIC, "child"))));
    }

    @Test void mismatchedQueryFieldAndFieldContractFailBeforeDescriptor() throws Exception {
        var version = version(snapshot("status"));
        var failure = assertThrows(GenerationValidationException.class,
                () -> preflight().describe(version, specification(), OutputMode.BACKEND));
        assertTrue(failure.diagnostics().stream().anyMatch(s -> s.contains("query.condition")));
        var spec = specification();
        var fields = new ArrayList<>(spec.entity().fields());
        fields.set(0, new FieldSpec("label", "label", "Integer", false, null, null));
        var changed = new GenerationSpecification(spec.specVersion(), spec.basePackage(),
                new EntitySpec("StockUnit", "stock_unit", fields, false), spec.openApi(), spec.operationRoles(), spec.angular());
        failure = assertThrows(GenerationValidationException.class,
                () -> preflight().describe(version(snapshot("condition")), changed, OutputMode.BACKEND));
        assertTrue(failure.diagnostics().stream().anyMatch(s -> s.contains("contract.update.label: type or format differs")));
    }

    @Test void descriptorFreezesValidatedUiBindingAuditAndCatalogInputs() throws Exception {
        var snapshot = snapshot("condition");
        addBoundLabel(snapshot, "Stock label");
        var version = version(snapshot);
        var spec = specification();
        var first = preflight().describe(version, spec, OutputMode.FULL_STACK);
        assertTrue(first.validatedSnapshotJson().contains("Stock label"));
        assertTrue(first.validatedSnapshotJson().contains("linkedComponentIds"));
        assertEquals("StockUnit", first.angularClassName());
        assertEquals("spring-angular", first.framework());
        assertFalse(first.catalogAssets().isEmpty());
        assertFalse(first.enversAudited());

        var audited = new GenerationSpecification(spec.specVersion(), spec.basePackage(),
                new EntitySpec(spec.entity().className(), spec.entity().tableName(), spec.entity().fields(), true),
                spec.openApi(), spec.operationRoles(), spec.angular());
        assertNotEquals(first, preflight().describe(version, audited, OutputMode.FULL_STACK));
        var alternateUi = new GenerationSpecification(spec.specVersion(), spec.basePackage(), spec.entity(),
                spec.openApi(), spec.operationRoles(), new AngularSpec("StockUnitView", Map.of()));
        assertNotEquals(first, preflight().describe(version, alternateUi, OutputMode.FULL_STACK));
        var changedSnapshot = snapshot("condition");
        addBoundLabel(changedSnapshot, "Different label");
        assertNotEquals(first, preflight().describe(version(changedSnapshot), spec, OutputMode.FULL_STACK));
        var changedPage = version(snapshot);
        changedPage.getPage().setRoute("/different-inventory");
        assertNotEquals(first, preflight().describe(changedPage, spec, OutputMode.FULL_STACK));
    }

    @Test void equivalentMapInsertionOrdersCanonicalizeAndInputsCannotMutateDescriptor() throws Exception {
        var spec = specification();
        var firstSnapshot = snapshot("condition");
        var first = preflight().describe(version(firstSnapshot), spec, OutputMode.FULL_STACK);
        Map<UUID, String> reversedBindings = new java.util.LinkedHashMap<>();
        Map<String, Set<SupportedRole>> reversedRoles = new java.util.LinkedHashMap<>();
        for (int i = ids.size() - 1; i >= 0; i--) {
            reversedBindings.put(new UUID(0, i + 1), ids.get(i));
            reversedRoles.put(ids.get(i), Set.of(SupportedRole.ROLE_ADMIN));
        }
        var reordered = new GenerationSpecification(spec.specVersion(), spec.basePackage(), spec.entity(),
                new OpenApiSpec(spec.openApi().contractId(), spec.openApi().contractVersion(), reversedBindings),
                reversedRoles, spec.angular());
        var reorderedSnapshot = mapper.createObjectNode();
        reorderedSnapshot.set("apiBindings", firstSnapshot.path("apiBindings").deepCopy());
        reorderedSnapshot.set("components", firstSnapshot.path("components").deepCopy());
        var equivalent = preflight().describe(version(reorderedSnapshot), reordered, OutputMode.FULL_STACK);
        assertEquals(first, equivalent);
        assertEquals(GenerationFilePlan.forDescriptor(first), GenerationFilePlan.forDescriptor(equivalent));
        assertEquals(ModelHandoffSpec.from(first, GenerationFilePlan.forDescriptor(first)),
                ModelHandoffSpec.from(equivalent, GenerationFilePlan.forDescriptor(equivalent)));
        var renderer = new CompleteBackendDefinitionRenderer();
        var firstRendered = renderer.render(first, GenerationFilePlan.forDescriptor(first));
        var equivalentRendered = renderer.render(equivalent, GenerationFilePlan.forDescriptor(equivalent));
        assertEquals(firstRendered.files().keySet(), equivalentRendered.files().keySet());
        for (String path : firstRendered.files().keySet())
            assertArrayEquals(firstRendered.files().get(path), equivalentRendered.files().get(path));
        var runtime = new CompleteBackendRuntimeRenderer(new PackagedContractRegistry(
                "/generation/stock-unit-contracts.yaml"));
        var firstRuntime = runtime.render(first, GenerationFilePlan.forDescriptor(first));
        var equivalentRuntime = runtime.render(equivalent, GenerationFilePlan.forDescriptor(equivalent));
        assertEquals(firstRuntime.keySet(), equivalentRuntime.keySet());
        for (String path : firstRuntime.keySet())
            assertArrayEquals(firstRuntime.get(path), equivalentRuntime.get(path));

        var mutableFields = new ArrayList<>(spec.entity().fields());
        var mutableBindings = new java.util.LinkedHashMap<>(spec.openApi().bindingOperations());
        var mutableRoles = new java.util.LinkedHashMap<>(spec.operationRoles());
        var mutableColumns = new java.util.LinkedHashMap<UUID, List<String>>();
        var mutable = new GenerationSpecification(spec.specVersion(), spec.basePackage(),
                new EntitySpec("StockUnit", "stock_unit", mutableFields, false),
                new OpenApiSpec("stock-unit-api", "2.0.0", mutableBindings), mutableRoles,
                new AngularSpec("StockUnit", mutableColumns));
        var mutableVersion = version(snapshot("condition"));
        var frozen = preflight().describe(mutableVersion, mutable, OutputMode.FULL_STACK);
        mutableFields.clear(); mutableBindings.clear(); mutableRoles.clear();
        mutableColumns.put(new UUID(0, 77), new ArrayList<>(List.of("label")));
        mutableVersion.setSnapshot("{}");
        assertEquals(3, frozen.fields().size());
        assertEquals(5, frozen.operations().size());
        assertTrue(frozen.angularTableColumns().isEmpty());
        assertTrue(frozen.validatedSnapshotJson().contains("stock-units"));
        assertThrows(UnsupportedOperationException.class, () -> frozen.fields().clear());
        assertThrows(UnsupportedOperationException.class, () -> frozen.catalogAssets().clear());
        assertThrows(UnsupportedOperationException.class, () -> frozen.angularTableColumns().clear());
    }

    @Test void unsupportedQueryAndPathParameterShapesFailExplicitly() {
        Map<String, Object> schemas = Map.of("Condition", Map.of("type", "string", "enum", List.of("OPEN", "CLOSED")),
                "Label", Map.of("type", "string"));
        List<String> errors = new ArrayList<>();
        var valid = PackagedContractLoader.parseQueryParameters(List.of(Map.of(
                "name", "condition", "in", "query", "required", true,
                "schema", Map.of("$ref", "#/components/schemas/Condition")), Map.of(
                "name", "label", "in", "query", "required", false,
                "schema", Map.of("$ref", "#/components/schemas/Label"))),
                CrudAction.LIST, schemas, "scanStockUnits", errors);
        assertTrue(errors.isEmpty(), errors.toString());
        assertTrue(valid.get("condition").required());
        assertEquals("Condition", valid.get("condition").schema().enumName());
        assertFalse(valid.get("label").required());
        assertEquals("", valid.get("label").schema().enumName());

        var unsupported = List.of(
                Map.of("name", "tags", "in", "query", "schema", Map.of("type", "array", "items", Map.of("type", "string"))),
                Map.of("name", "condition", "in", "header", "schema", Map.of("type", "string")),
                Map.of("name", "condition", "in", "query", "style", "deepObject", "schema", Map.of("type", "string")),
                Map.of("name", "condition", "in", "query", "schema", Map.of("type", "string", "enum", List.of(1, 2))));
        var rejected = PackagedContractLoader.parseQueryParameters(unsupported, CrudAction.LIST,
                schemas, "scanStockUnits", errors);
        assertTrue(rejected.isEmpty());
        assertEquals(4, errors.size(), errors.toString());
        assertTrue(errors.stream().allMatch(e -> e.contains("unsupported parameter")));
        PackagedContractLoader.validatePathParameters("/api/v1/stock-units/{id}", List.of(Map.of(
                "name", "id", "in", "path", "required", false,
                "schema", Map.of("type", "string", "format", "uuid"))), errors);
        assertTrue(errors.stream().anyMatch(e -> e.contains("unsupported path parameter shape")));
    }

    private void addBoundLabel(ObjectNode snapshot, String label) {
        UUID id = new UUID(0, 100);
        ObjectNode component = snapshot.withArray("components").addObject();
        component.put("id", id.toString()); component.put("type", "text-field");
        component.put("fieldKey", "label"); component.put("label", label);
        component.putObject("position").put("row", 0).put("col", 0);
        component.putObject("size").put("width", 6).put("height", 1);
        component.put("visibility", true); component.put("disabled", false);
        ((ObjectNode) snapshot.withArray("apiBindings").get(0)).putArray("linkedComponentIds").add(id.toString());
    }

    private GenerationCapabilityPreflight preflight() {
        var registry = new PackagedContractRegistry("/generation/stock-unit-contracts.yaml");
        return new GenerationCapabilityPreflight(
                new GenerationContextBuilder(mapper, new MetadataSchemaValidator(), registry), registry);
    }

    private GenerationSpecification specification() {
        Map<UUID, String> bindings = new java.util.LinkedHashMap<>();
        Map<String, Set<SupportedRole>> roles = new java.util.LinkedHashMap<>();
        for (int i = 0; i < ids.size(); i++) {
            bindings.put(new UUID(0, i + 1), ids.get(i));
            roles.put(ids.get(i), Set.of(SupportedRole.ROLE_ADMIN));
        }
        return new GenerationSpecification("1.0.0", "com.example.inventory",
                new EntitySpec("StockUnit", "stock_unit", List.of(
                        new FieldSpec("label", "label", "String", false, 100, null),
                        new FieldSpec("condition", "condition", "StockUnitCondition", false, null, "StockUnitCondition"),
                        new FieldSpec("retiredOn", "retired_on", "LocalDate", true, null, null)), false),
                new OpenApiSpec("stock-unit-api", "2.0.0", bindings), roles,
                new AngularSpec("StockUnit", Map.of()));
    }

    private ObjectNode snapshot(String queryName) {
        var snapshot = mapper.createObjectNode();
        snapshot.putArray("components");
        var bindings = snapshot.putArray("apiBindings");
        String[] verbs = {"GET", "POST", "GET", "PUT", "DELETE"};
        for (int i = 0; i < verbs.length; i++) {
            ObjectNode binding = bindings.addObject();
            binding.put("id", new UUID(0, i + 1).toString());
            binding.put("httpMethod", verbs[i]);
            binding.put("endpointUrl", "/api/v1/stock-units" + (i > 1 ? "/{id}" : ""));
            binding.putObject("headers");
            ObjectNode request = binding.putObject("requestMapping");
            if (i == 0) request.putObject("query").put(queryName, queryName).put("label", "label");
            if (i > 1) request.putObject("path").put("id", "id");
            if (i == 1 || i == 3) request.putObject("body")
                    .put("label", "label").put("condition", "condition").put("retiredOn", "retiredOn");
            ObjectNode response = binding.putObject("responseMapping");
            if (i == 0) response.put("list", "body.data"); else response.put("item", "body");
        }
        return snapshot;
    }

    private MetadataVersion version(ObjectNode snapshot) throws Exception {
        var project = new GsuifProject(); project.setName("Fixture");
        var page = new GsuifPage(); page.setProject(project);
        page.setName("Inventory"); page.setRoute("/inventory");
        var version = new MetadataVersion(); version.setPage(page);
        version.setVersion(1); version.setSchemaVersion("1.0.0");
        version.setSnapshot(mapper.writeValueAsString(snapshot));
        return version;
    }
}
