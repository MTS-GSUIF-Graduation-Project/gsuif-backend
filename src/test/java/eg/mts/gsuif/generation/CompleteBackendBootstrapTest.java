package eg.mts.gsuif.generation;

import eg.mts.gsuif.generation.GenerationCapabilityPreflight.OutputMode;
import eg.mts.gsuif.generation.GenerationFilePlan.Entry;
import eg.mts.gsuif.generation.GenerationFilePlan.Owner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import javax.tools.ToolProvider;

import static org.junit.jupiter.api.Assertions.*;

class CompleteBackendBootstrapTest {
    private final CompleteOutputTestFixture fixture = new CompleteOutputTestFixture();

    private CompleteOutputDescriptor descriptor() throws Exception {
        return fixture.preflight().describe(fixture.version(fixture.snapshot("condition")),
                fixture.specification(), OutputMode.BACKEND);
    }

    @Test void ownedBootstrapSubsetIsDeterministicAndIsolated() throws Exception {
        var descriptor = descriptor();
        var plan = GenerationFilePlan.forDescriptor(descriptor);
        var files = new CompleteBackendBootstrapRenderer().render(descriptor, plan);
        assertEquals(2, files.size());
        assertTrue(files.keySet().stream().allMatch(path -> path.contains("/bootstrap/")));
        assertTrue(files.values().stream().map(bytes -> new String(bytes, StandardCharsets.UTF_8))
                .noneMatch(source -> source.contains("AssetTicket") || source.contains("Gadget")
                        || source.contains("WorkOrder") || source.contains("OPENROUTER_API_KEY")));
        for (var file : files.entrySet())
            assertArrayEquals(file.getValue(), new CompleteBackendBootstrapRenderer().render(descriptor, plan)
                    .get(file.getKey()));
        String path = files.keySet().iterator().next();
        byte[] original = files.get(path).clone();
        files.get(path)[0] ^= 1;
        assertArrayEquals(original, new CompleteBackendBootstrapRenderer().render(descriptor, plan).get(path));
        var missing = new GenerationFilePlan(plan.entries().stream()
                .filter(entry -> !entry.path().equals(path)).toList());
        assertThrows(IllegalArgumentException.class,
                () -> new CompleteBackendBootstrapRenderer().render(descriptor, missing));
        var wrongOwner = new GenerationFilePlan(plan.entries().stream().map(entry -> entry.path().equals(path)
                ? new Entry(entry.path(), Owner.MODEL, entry.purpose()) : entry).toList());
        assertThrows(IllegalArgumentException.class,
                () -> new CompleteBackendBootstrapRenderer().render(descriptor, wrongOwner));
    }

    @Test void emittedCommandCompilesAndRejectsExtraOrPasswordArgumentsWithoutStartingContext(@TempDir Path scratch)
            throws Exception {
        try (var loader = compiled(scratch)) {
            var command = loader.loadClass("com.example.inventory.bootstrap.FirstAdminBootstrap");
            var readerType = loader.loadClass("com.example.inventory.bootstrap.FirstAdminBootstrap$PasswordReader");
            Object reader = java.lang.reflect.Proxy.newProxyInstance(loader, new Class<?>[]{readerType},
                    (proxy, method, args) -> { fail("Rejected arguments must not read a password"); return null; });
            var run = command.getMethod("run", String[].class, Map.class, readerType);
            var originalErr = System.err;
            var output = new ByteArrayOutputStream();
            try {
                System.setErr(new PrintStream(output, true, StandardCharsets.UTF_8));
                assertEquals(2, run.invoke(null, new String[]{"bootstrap-admin", "--password=disposable"},
                        Map.of(), reader));
                assertEquals(2, run.invoke(null, new String[]{"other-command"}, Map.of(), reader));
            } finally {
                System.setErr(originalErr);
            }
            assertEquals("Bootstrap command rejected\nBootstrap command rejected\n",
                    output.toString(StandardCharsets.UTF_8).replace("\r\n", "\n"));
            var application = loader.loadClass("com.example.inventory.Application");
            var scan = application.getAnnotation(org.springframework.context.annotation.ComponentScan.class);
            assertNotNull(scan);
            assertTrue(java.util.Arrays.stream(scan.excludeFilters())
                    .flatMap(filter -> java.util.Arrays.stream(filter.pattern()))
                    .anyMatch(pattern -> pattern.contains("bootstrap")));
            assertTrue(java.util.Arrays.stream(scan.excludeFilters())
                    .flatMap(filter -> java.util.Arrays.stream(filter.pattern()))
                    .anyMatch(pattern -> java.util.regex.Pattern.matches(pattern,
                            "com.example.inventory.bootstrap.FirstAdminBootstrapService")));
            var service = loader.loadClass("com.example.inventory.bootstrap.FirstAdminBootstrapService");
            var method = service.getMethod("createIfNoAdmin", String.class, char[].class);
            assertEquals(org.springframework.transaction.annotation.Isolation.SERIALIZABLE,
                    method.getAnnotation(org.springframework.transaction.annotation.Transactional.class).isolation());
            var failed = invokeCommandProcess(scratch.resolve("classes"), Map.of(
                    "GENERATED_APP_DB_URL", "jdbc:postgresql://127.0.0.1:1/postgres?connectTimeout=1",
                    "GENERATED_APP_DB_USER", "disposable",
                    "GENERATED_APP_DB_PASSWORD", "disposable-db-password",
                    "GENERATED_APP_BOOTSTRAP_USERNAME", "Operator",
                    "GENERATED_APP_BOOTSTRAP_PASSWORD", "disposable-bootstrap-password"), "bootstrap-admin");
            assertEquals(4, failed.exit());
            assertEquals("Bootstrap failed; no success reported", failed.output().trim());
        }
    }

    @Test void emittedCommandUsesOwnDisposablePostgresAndSerializesFirstAdmin(@TempDir Path scratch)
            throws Exception {
        String baseUrl = System.getenv("SCRUM65_BOOTSTRAP_TEST_URL");
        org.junit.jupiter.api.Assumptions.assumeTrue(baseUrl != null
                && baseUrl.startsWith("jdbc:postgresql://127.0.0.1:"),
                "Requires a newly created disposable localhost PostgreSQL container");
        String dbPassword = System.getenv("SCRUM65_BOOTSTRAP_TEST_PASSWORD");
        assertNotNull(dbPassword);
        var descriptor = descriptor();
        var plan = GenerationFilePlan.forDescriptor(descriptor);
        var migration = new CompleteBackendInitialMigrationRenderer().render(descriptor, plan);
        String schema = "bootstrap_" + java.util.UUID.randomUUID().toString().replace("-", "");
        try (var connection = java.sql.DriverManager.getConnection(baseUrl, "postgres", dbPassword);
             var statement = connection.createStatement()) {
            statement.execute("CREATE SCHEMA " + schema);
        }
        String url = baseUrl + (baseUrl.contains("?") ? "&" : "?") + "currentSchema=" + schema;
        try (var loader = compiled(scratch)) {
            Path classes = scratch.resolve("classes");
            Path migrationDirectory = scratch.resolve("migration");
            Files.createDirectories(migrationDirectory);
            for (var entry : migration.entrySet()) {
                if (!entry.getKey().endsWith(".sql")) continue;
                Path target = classes.resolve("db/migration/V1__initial_schema.sql");
                Files.createDirectories(target.getParent());
                Files.write(target, entry.getValue());
                Files.write(migrationDirectory.resolve("V1__initial_schema.sql"), entry.getValue());
            }
            assertEquals(1, org.flywaydb.core.Flyway.configure().dataSource(url, "postgres", dbPassword)
                    .locations("filesystem:" + migrationDirectory).load().migrate().migrationsExecuted);
            var command = loader.loadClass("com.example.inventory.bootstrap.FirstAdminBootstrap");
            var readerType = loader.loadClass("com.example.inventory.bootstrap.FirstAdminBootstrap$PasswordReader");
            Object reader = java.lang.reflect.Proxy.newProxyInstance(loader, new Class<?>[]{readerType},
                    (proxy, method, args) -> { fail("Ephemeral test secret should bypass console"); return null; });
            var run = command.getMethod("run", String[].class, Map.class, readerType);
            Map<String, String> base = Map.of(
                    "GENERATED_APP_DB_URL", url,
                    "GENERATED_APP_DB_USER", "postgres",
                    "GENERATED_APP_DB_PASSWORD", dbPassword,
                    "GENERATED_APP_BOOTSTRAP_PASSWORD", "disposable-bootstrap-password");
            assertEquals(2, invoke(loader, run, reader, Map.of(), "bootstrap-admin"),
                    "The command must reject missing app DB configuration");
            try (var connection = java.sql.DriverManager.getConnection(url, "postgres", dbPassword);
                 var statement = connection.createStatement();
                 var rows = statement.executeQuery("SELECT count(*) FROM user_accounts")) {
                assertTrue(rows.next());
                assertEquals(0, rows.getLong(1), "V1 must not seed an account");
            }
            ClassLoader normalPrevious = Thread.currentThread().getContextClassLoader();
            Thread.currentThread().setContextClassLoader(loader);
            try (var normal = new org.springframework.boot.builder.SpringApplicationBuilder(
                    loader.loadClass("com.example.inventory.Application"))
                    .web(org.springframework.boot.WebApplicationType.NONE)
                    .properties("spring.main.banner-mode=off", "logging.level.root=OFF",
                            "spring.autoconfigure.exclude="
                                    + "org.springframework.boot.security.autoconfigure.SecurityAutoConfiguration,"
                                    + "org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration")
                    .initializers(application -> application.getEnvironment().getPropertySources().addFirst(
                            new org.springframework.core.env.MapPropertySource("normal-startup-db", Map.of(
                                    "spring.datasource.url", url,
                                    "spring.datasource.username", "postgres",
                                    "spring.datasource.password", dbPassword,
                                    "spring.jpa.hibernate.ddl-auto", "validate"))))
                    .run()) {
                assertFalse(normal.containsBean("firstAdminBootstrapService"));
                assertFalse(normal.containsBean("firstAdminBootstrap.BootstrapContext"));
            } finally {
                Thread.currentThread().setContextClassLoader(normalPrevious);
            }
            try (var connection = java.sql.DriverManager.getConnection(url, "postgres", dbPassword);
                 var statement = connection.createStatement();
                 var rows = statement.executeQuery("SELECT count(*) FROM user_accounts")) {
                assertTrue(rows.next());
                assertEquals(0, rows.getLong(1), "Normal startup must not create accounts");
            }
            var first = new java.util.HashMap<>(base);
            first.put("GENERATED_APP_BOOTSTRAP_USERNAME", "FirstAdmin");
            first.put("SPRING_FLYWAY_URL", "jdbc:postgresql://127.0.0.1:1/unrelated?connectTimeout=1");
            first.put("SPRING_DATASOURCE_HIKARI_JDBC_URL",
                    "jdbc:postgresql://127.0.0.1:1/unrelated?connectTimeout=1");
            var created = invokeCommandProcess(classes, first, "bootstrap-admin");
            assertEquals(0, created.exit());
            assertEquals("Bootstrap admin created", created.output().trim());
            ClassLoader previous = Thread.currentThread().getContextClassLoader();
            Thread.currentThread().setContextClassLoader(loader);
            try (var context = new org.springframework.boot.builder.SpringApplicationBuilder(
                    loader.loadClass("com.example.inventory.bootstrap.FirstAdminBootstrap$BootstrapContext"))
                    .web(org.springframework.boot.WebApplicationType.NONE)
                    .properties("spring.main.banner-mode=off", "logging.level.root=OFF")
                    .initializers(application -> application.getEnvironment().getPropertySources().addFirst(
                            new org.springframework.core.env.MapPropertySource("bootstrap-proxy-check", Map.of(
                                    "spring.datasource.url", url,
                                    "spring.datasource.username", "postgres",
                                    "spring.datasource.password", dbPassword,
                                    "spring.jpa.hibernate.ddl-auto", "validate"))))
                    .run()) {
                Object service = context.getBean(loader.loadClass(
                        "com.example.inventory.bootstrap.FirstAdminBootstrapService"));
                assertTrue(org.springframework.aop.support.AopUtils.isAopProxy(service));
                assertTrue(java.util.Arrays.stream(((org.springframework.aop.framework.Advised) service).getAdvisors())
                        .anyMatch(advisor -> advisor.getAdvice()
                                instanceof org.springframework.transaction.interceptor.TransactionInterceptor));
                assertFalse(context.containsBean("securityMode"));
                assertFalse(context.containsBean("jwtService"));
                assertFalse(context.containsBean("generatedAppAuditorAware"));
            } finally {
                Thread.currentThread().setContextClassLoader(previous);
            }
            try (var connection = java.sql.DriverManager.getConnection(url, "postgres", dbPassword);
                 var statement = connection.createStatement();
                 var rows = statement.executeQuery("SELECT u.username, u.enabled, u.password_hash, r.role "
                         + "FROM user_accounts u JOIN user_account_roles r ON r.user_id=u.id")) {
                assertTrue(rows.next());
                assertEquals("FirstAdmin", rows.getString(1));
                assertTrue(rows.getBoolean(2));
                assertTrue(new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder()
                        .matches("disposable-bootstrap-password", rows.getString(3)));
                assertEquals("ROLE_ADMIN", rows.getString(4));
                assertFalse(rows.next());
            }
            var refused = invokeCommandProcess(classes, first, "bootstrap-admin");
            assertEquals(3, refused.exit());
            assertEquals("Bootstrap refused: an admin already exists", refused.output().trim());
            try (var connection = java.sql.DriverManager.getConnection(url, "postgres", dbPassword);
                 var statement = connection.createStatement()) {
                statement.executeUpdate("UPDATE user_accounts SET enabled=false WHERE username='FirstAdmin'");
            }
            assertEquals(3, invokeCommandProcess(classes, first, "bootstrap-admin").exit(),
                    "A disabled admin still prevents another bootstrap");
            var extra = invokeCommandProcess(classes, first, "bootstrap-admin", "--password=disposable");
            assertEquals(2, extra.exit());
            assertEquals("Bootstrap command rejected", extra.output().trim());
            try (var connection = java.sql.DriverManager.getConnection(url, "postgres", dbPassword);
                 var statement = connection.createStatement()) {
                statement.executeUpdate("DELETE FROM user_account_roles");
                statement.executeUpdate("DELETE FROM user_accounts");
                statement.execute("CREATE FUNCTION bootstrap_wait() RETURNS trigger LANGUAGE plpgsql AS $$ "
                        + "BEGIN PERFORM pg_advisory_xact_lock(650065); RETURN NEW; END $$");
                statement.execute("CREATE TRIGGER bootstrap_hold BEFORE INSERT ON user_accounts "
                        + "FOR EACH ROW EXECUTE FUNCTION bootstrap_wait()");
            }
            var second = new java.util.HashMap<>(base);
            second.put("GENERATED_APP_BOOTSTRAP_USERNAME", "SecondAdmin");
            var third = new java.util.HashMap<>(base);
            third.put("GENERATED_APP_BOOTSTRAP_USERNAME", "ThirdAdmin");
            var executor = java.util.concurrent.Executors.newFixedThreadPool(2);
            try (var gate = java.sql.DriverManager.getConnection(url, "postgres", dbPassword)) {
                try (var lock = gate.createStatement()) {
                    lock.execute("SELECT pg_advisory_lock(650065)");
                }
                var start = new java.util.concurrent.CountDownLatch(1);
                var a = executor.submit(() -> { start.await(); return invoke(loader, run, reader, second,
                        "bootstrap-admin"); });
                var b = executor.submit(() -> { start.await(); return invoke(loader, run, reader, third,
                        "bootstrap-admin"); });
                start.countDown();
                long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(45);
                int blocked = 0;
                while (System.nanoTime() < deadline) {
                    try (var inspect = gate.createStatement();
                         var rows = inspect.executeQuery("SELECT count(*) FROM pg_stat_activity "
                                 + "WHERE wait_event_type='Lock' AND wait_event='advisory' "
                                 + "AND query ILIKE 'insert into user_accounts%'")) {
                        assertTrue(rows.next());
                        blocked = rows.getInt(1);
                    }
                    if (blocked == 2) break;
                    Thread.sleep(100);
                }
                try (var release = gate.createStatement()) {
                    release.execute("SELECT pg_advisory_unlock(650065)");
                }
                assertEquals(2, blocked, "Both independent transactions must pass the no-admin check first");
                int resultA = a.get(45, java.util.concurrent.TimeUnit.SECONDS);
                int resultB = b.get(45, java.util.concurrent.TimeUnit.SECONDS);
                assertEquals(1, (resultA == 0 ? 1 : 0) + (resultB == 0 ? 1 : 0));
                assertTrue((resultA == 3 || resultA == 4) && resultB == 0
                        || (resultB == 3 || resultB == 4) && resultA == 0);
            } finally {
                executor.shutdownNow();
            }
            try (var connection = java.sql.DriverManager.getConnection(url, "postgres", dbPassword);
                 var statement = connection.createStatement();
                 var rows = statement.executeQuery("SELECT count(*) FROM user_account_roles WHERE role='ROLE_ADMIN'")) {
                assertTrue(rows.next());
                assertEquals(1, rows.getLong(1));
            }
        }
    }

    private static int invoke(ClassLoader loader, java.lang.reflect.Method run, Object reader,
            Map<String, String> environment, String... arguments) throws Exception {
        ClassLoader previous = Thread.currentThread().getContextClassLoader();
        Thread.currentThread().setContextClassLoader(loader);
        try {
            return (int) run.invoke(null, arguments, environment, reader);
        } finally {
            Thread.currentThread().setContextClassLoader(previous);
        }
    }

    private record ProcessResult(int exit, String output) { }

    private static ProcessResult invokeCommandProcess(Path classes, Map<String, String> environment,
            String... arguments) throws Exception {
        var command = new ArrayList<>(List.of(Path.of(System.getProperty("java.home"), "bin", "java")
                        .toString(), "-cp", classes + java.io.File.pathSeparator
                        + System.getProperty("java.class.path"), "com.example.inventory.Application"));
        command.addAll(List.of(arguments));
        var builder = new ProcessBuilder(command).redirectErrorStream(true);
        builder.environment().remove("OPENROUTER_API_KEY");
        builder.environment().remove("GENERATED_APP_JWT_SECRET");
        builder.environment().remove("GENERATED_APP_DEV_ACTOR");
        builder.environment().putAll(environment);
        var process = builder.start();
        if (!process.waitFor(45, java.util.concurrent.TimeUnit.SECONDS)) {
            process.destroyForcibly();
            fail("Emitted bootstrap command exceeded its test deadline");
        }
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8)
                .replace("\r\n", "\n");
        assertFalse(output.contains(environment.getOrDefault("GENERATED_APP_BOOTSTRAP_PASSWORD", "!absent!")));
        assertFalse(output.contains(environment.getOrDefault("GENERATED_APP_DB_PASSWORD", "!absent!")));
        assertFalse(output.contains(environment.getOrDefault("GENERATED_APP_DB_URL", "!absent!")));
        return new ProcessResult(process.exitValue(), output);
    }

    private URLClassLoader compiled(Path scratch) throws Exception {
        var descriptor = descriptor();
        var plan = GenerationFilePlan.forDescriptor(descriptor);
        Map<String, byte[]> sources = new TreeMap<>(new CompleteBackendBootstrapRenderer().render(descriptor, plan));
        var security = new CompleteBackendSecurityRenderer().render(descriptor, plan);
        security.forEach((path, bytes) -> {
            if (path.endsWith("/PasswordEncodingConfig.java") || path.endsWith("/UserAccountRepository.java"))
                sources.put(path, bytes);
        });
        new CompleteBackendInitialMigrationRenderer().render(descriptor, plan).forEach((path, bytes) -> {
            if (path.endsWith("/UserAccount.java")) sources.put(path, bytes);
        });
        new CompleteBackendRuntimeRenderer(new PackagedContractRegistry(
                "/generation/stock-unit-contracts.yaml")).render(descriptor, plan).forEach((path, bytes) -> {
            if (path.endsWith("/Application.java")) sources.put(path, bytes);
        });
        Path classes = Files.createDirectories(scratch.resolve("classes"));
        List<String> arguments = new ArrayList<>(List.of("-classpath", System.getProperty("java.class.path"),
                "-d", classes.toString()));
        for (var source : sources.entrySet()) {
            Path path = scratch.resolve(source.getKey());
            Files.createDirectories(path.getParent());
            Files.write(path, source.getValue());
            arguments.add(path.toString());
        }
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null, arguments.toArray(String[]::new)));
        return new URLClassLoader(new java.net.URL[]{classes.toUri().toURL()}, getClass().getClassLoader());
    }
}
