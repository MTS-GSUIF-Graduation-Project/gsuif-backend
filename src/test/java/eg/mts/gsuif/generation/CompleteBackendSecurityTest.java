package eg.mts.gsuif.generation;

import eg.mts.gsuif.generation.GenerationCapabilityPreflight.OutputMode;
import eg.mts.gsuif.generation.GenerationFilePlan.Entry;
import eg.mts.gsuif.generation.GenerationFilePlan.Owner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.WebApplicationType;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.lang.reflect.InvocationTargetException;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import javax.tools.ToolProvider;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

class CompleteBackendSecurityTest {
    private final CompleteOutputTestFixture fixture = new CompleteOutputTestFixture();

    @Configuration(proxyBeanMethods = false)
    @org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(
            name = "gsuif.security.probe", havingValue = "true")
    @EnableAutoConfiguration(exclude = org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration.class)
    static class BootProbe { }

    @RestController
    static class HttpProbe {
        @GetMapping("/probe") public String probe() { return "ok"; }
    }

    static class MethodProbe {
        @org.springframework.security.access.prepost.PreAuthorize("hasAuthority('ROLE_ADMIN')")
        public String admin() { return "ok"; }
    }

    private CompleteOutputDescriptor descriptor() throws Exception {
        return fixture.preflight().describe(fixture.version(fixture.snapshot("condition")),
                fixture.specification(), OutputMode.BACKEND);
    }

    @Test void plannedSubsetIsDeterministicOwnedAndSecretFree() throws Exception {
        var descriptor = descriptor();
        var plan = GenerationFilePlan.forDescriptor(descriptor);
        var renderer = new CompleteBackendSecurityRenderer();
        var files = renderer.render(descriptor, plan);
        assertEquals(6, files.size());
        assertTrue(files.keySet().stream().allMatch(path -> path.contains("/security/") && path.endsWith(".java")));
        String all = files.values().stream().map(bytes -> new String(bytes, StandardCharsets.UTF_8))
                .reduce("", String::concat);
        assertFalse(all.contains("AssetTicket") || all.contains("Gadget") || all.contains("WorkOrder"));
        assertFalse(all.contains("OPENROUTER_API_KEY") || all.contains("GENERATED_APP_JWT_SECRET"));
        assertTrue(all.contains("findByUsername(String username)"));
        assertTrue(all.contains("@Transactional(readOnly = true)"));
        assertTrue(all.contains("httpBasic(basic -> basic.disable())"));
        assertTrue(all.contains("formLogin(form -> form.disable())"));
        assertTrue(all.contains("anyRequest().denyAll()"));
        var again = renderer.render(descriptor, plan);
        for (var path : files.keySet()) assertArrayEquals(files.get(path), again.get(path));
        byte original = files.values().iterator().next()[0];
        files.values().iterator().next()[0] ^= 1;
        assertEquals(original, renderer.render(descriptor, plan).values().iterator().next()[0]);
        var absent = new GenerationFilePlan(plan.entries().stream()
                .filter(entry -> !entry.path().endsWith("/security/AccountIdentityService.java")).toList());
        assertThrows(IllegalArgumentException.class, () -> renderer.render(descriptor, absent));
        var wrong = new GenerationFilePlan(plan.entries().stream().map(entry ->
                entry.path().endsWith("/security/SecurityConfig.java")
                        ? new Entry(entry.path(), Owner.MODEL, entry.purpose()) : entry).toList());
        assertThrows(IllegalArgumentException.class, () -> renderer.render(descriptor, wrong));
        var frontend = fixture.preflight().describe(fixture.version(fixture.snapshot("condition")),
                fixture.specification(), OutputMode.FRONTEND);
        assertThrows(IllegalArgumentException.class, () -> renderer.render(frontend,
                GenerationFilePlan.forDescriptor(frontend)));
    }

    @Test void emittedModeRejectsConflictsAndNonLoopbackDev(@TempDir Path scratch) throws Exception {
        try (var loader = compiled(scratch)) {
            Class<?> mode = loader.loadClass("com.example.inventory.security.SecurityMode");
            assertFalse(isDev(mode, new MockEnvironment().withProperty("spring.profiles.default", "prod")));
            assertTrue(isDev(mode, new MockEnvironment().withProperty("server.address", "127.0.0.1")
                    .withProperty("spring.profiles.active", "dev")));
            for (String address : List.of("0.0.0.0", "192.0.2.1", "localhost", "::1")) {
                var env = new MockEnvironment().withProperty("server.address", address);
                env.setActiveProfiles("dev");
                assertThrows(InvocationTargetException.class, () -> mode.getConstructor(org.springframework.core.env.Environment.class)
                        .newInstance(env));
            }
            var mixed = new MockEnvironment().withProperty("server.address", "127.0.0.1");
            mixed.setActiveProfiles("dev", "prod");
            assertThrows(InvocationTargetException.class, () -> mode.getConstructor(org.springframework.core.env.Environment.class)
                    .newInstance(mixed));
            var additive = new MockEnvironment(); additive.setActiveProfiles("prod", "observability");
            assertFalse(isDev(mode, additive));
            var unknown = new MockEnvironment(); unknown.setActiveProfiles("local");
            assertThrows(InvocationTargetException.class, () -> mode.getConstructor(org.springframework.core.env.Environment.class)
                    .newInstance(unknown));
            var overridden = new MockEnvironment().withProperty("GENERATED_APP_SECURITY_MODE", "dev");
            overridden.setActiveProfiles("prod");
            assertThrows(InvocationTargetException.class, () -> mode.getConstructor(org.springframework.core.env.Environment.class)
                    .newInstance(overridden));
        }
    }

    @Test void emittedAccountLookupCopiesRolesAndRejectsDisabledWithoutChangingUsername(@TempDir Path scratch) throws Exception {
        try (var loader = compiled(scratch)) {
            String root = "com.example.inventory.security.";
            Class<?> accountType = loader.loadClass(root + "UserAccount");
            Class<?> repoType = loader.loadClass(root + "UserAccountRepository");
            Class<?> serviceType = loader.loadClass(root + "AccountIdentityService");
            Object account = accountType.getConstructor().newInstance();
            UUID id = UUID.randomUUID();
            var idField = accountType.getDeclaredField("id"); idField.setAccessible(true); idField.set(account, id);
            accountType.getMethod("setUsername", String.class).invoke(account, "Alice");
            accountType.getMethod("setEnabled", boolean.class).invoke(account, true);
            Set roles = (Set) accountType.getMethod("getRoles").invoke(account);
            roles.add(Enum.valueOf((Class) loader.loadClass(root + "UserAccount$Role"), "ROLE_USER"));
            Object repository = mock(repoType);
            when(repoType.getMethod("findByUsername", String.class).invoke(repository, "Alice"))
                    .thenReturn(Optional.of(account));
            Object service = serviceType.getConstructor(repoType).newInstance(repository);
            UserDetailsService users = (UserDetailsService) service;
            var principal = users.loadUserByUsername("Alice");
            assertEquals("Alice", principal.getUsername());
            assertNull(principal.getPassword());
            assertEquals(id, principal.getClass().getMethod("id").invoke(principal));
            assertEquals(Set.of("ROLE_USER"), principal.getAuthorities().stream()
                    .map(a -> a.getAuthority()).collect(java.util.stream.Collectors.toSet()));
            roles.clear();
            assertEquals(1, principal.getAuthorities().size());
            assertTrue(principal.getClass().isRecord());
            var transaction = serviceType.getMethod("loadUserByUsername", String.class)
                    .getAnnotation(org.springframework.transaction.annotation.Transactional.class);
            assertTrue(transaction.readOnly());
            assertNotNull(repoType.getMethod("findByUsername", String.class)
                    .getAnnotation(org.springframework.data.jpa.repository.EntityGraph.class));
            assertThrows(org.springframework.security.core.userdetails.UsernameNotFoundException.class,
                    () -> users.loadUserByUsername("alice"));
            accountType.getMethod("setEnabled", boolean.class).invoke(account, false);
            assertThrows(org.springframework.security.core.userdetails.UsernameNotFoundException.class,
                    () -> users.loadUserByUsername("Alice"));
        }
    }

    @Test void emittedBootSecurityIsOpenOnlyForLoopbackDevAndHasNoDefaultLogin(@TempDir Path scratch) throws Exception {
        String inheritedActiveProfiles = System.getProperty("spring.profiles.active");
        try (var loader = compiled(scratch)) {
            var descriptor = descriptor();
            var runtime = new CompleteBackendRuntimeRenderer(new PackagedContractRegistry(
                    "/generation/stock-unit-contracts.yaml"))
                    .render(descriptor, GenerationFilePlan.forDescriptor(descriptor));
            Path config = scratch.resolve("generated-application.yml");
            Files.write(config, runtime.get("backend/src/main/resources/application.yml"));
            ClassLoader previous = Thread.currentThread().getContextClassLoader();
            Thread.currentThread().setContextClassLoader(loader);
            try {
                Class<?> security = loader.loadClass("com.example.inventory.security.SecurityConfig");
                Class<?> mode = loader.loadClass("com.example.inventory.security.SecurityMode");
                Class<?> enforced = loader.loadClass("com.example.inventory.security.EnforcedMethodSecurityConfig");
                Class<?> identity = loader.loadClass("com.example.inventory.security.AccountIdentityService");
                Class<?> repository = loader.loadClass("com.example.inventory.security.UserAccountRepository");
                for (String profile : List.of("dev", "test", "prod", "default")) {
                    var builder = new SpringApplicationBuilder(
                            BootProbe.class, security, mode, enforced, identity, HttpProbe.class, MethodProbe.class)
                            .web(WebApplicationType.SERVLET)
                            .properties("spring.main.banner-mode=off")
                            .initializers(application -> application.getBeanFactory()
                                    .registerSingleton("accountRepositoryProbe", mock(repository)));
                    try (ConfigurableApplicationContext context = builder.run(
                            "--spring.profiles.active=" + (profile.equals("default") ? "" : profile),
                            "--spring.config.location=" + config.toUri(), "--server.port=0",
                            "--spring.main.banner-mode=off", "--logging.level.root=OFF",
                            "--gsuif.security.probe=true")) {
                        assertArrayEquals(profile.equals("default") ? new String[0] : new String[] {profile},
                                context.getEnvironment().getActiveProfiles());
                        if (profile.equals("default"))
                            assertArrayEquals(new String[] {"prod"}, context.getEnvironment().getDefaultProfiles());
                        var mvc = MockMvcBuilders.webAppContextSetup((WebApplicationContext) context)
                                .apply(SecurityMockMvcConfigurers.springSecurity()).build();
                        assertEquals(1, context.getBeansOfType(UserDetailsService.class).size());
                        assertNotNull(context.getBean("springSecurityFilterChain"));
                        assertTrue(context.getBeansOfType(UserDetailsService.class).values().stream()
                                .noneMatch(bean -> bean.getClass().getName().contains("InMemoryUserDetailsManager")));
                        assertFalse(context.containsBean("inMemoryUserDetailsManager"));
                        var http = mvc.perform(get("/probe")).andReturn().getResponse();
                        if (profile.equals("dev")) {
                            assertEquals(200, http.getStatus());
                            assertEquals("ok", context.getBean(MethodProbe.class).admin());
                            assertThrows(org.springframework.security.core.userdetails.UsernameNotFoundException.class,
                                    () -> context.getBean(UserDetailsService.class).loadUserByUsername("anyone"));
                            assertEquals(404, mvc.perform(post("/login")).andReturn().getResponse().getStatus());
                        } else {
                            assertEquals(401, http.getStatus());
                            var envelope = new com.fasterxml.jackson.databind.ObjectMapper()
                                    .readTree(http.getContentAsString());
                            assertEquals(5, envelope.size());
                            assertEquals("UNAUTHORIZED", envelope.get("status").asText());
                            assertEquals("Authentication required", envelope.get("clientMessage").asText());
                            assertEquals(401, envelope.get("statusCode").asInt());
                            assertTrue(envelope.get("body").isNull());
                            assertEquals(1, envelope.get("errors").size());
                            assertEquals("Authentication required", envelope.get("errors").get("_global").asText());
                            assertThrows(org.springframework.security.authentication.AuthenticationCredentialsNotFoundException.class,
                                    () -> context.getBean(MethodProbe.class).admin());
                            var user = new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(
                                    "Alice", "unused", List.of(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_USER")));
                            org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(user);
                            try {
                                assertThrows(org.springframework.security.authorization.AuthorizationDeniedException.class,
                                        () -> context.getBean(MethodProbe.class).admin());
                                var forbidden = mvc.perform(get("/probe")
                                        .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors
                                                .authentication(user))).andReturn().getResponse();
                                assertEquals(403, forbidden.getStatus());
                                var deniedEnvelope = new com.fasterxml.jackson.databind.ObjectMapper()
                                        .readTree(forbidden.getContentAsString());
                                assertEquals(5, deniedEnvelope.size());
                                assertEquals("FORBIDDEN", deniedEnvelope.get("status").asText());
                                assertEquals("Access denied", deniedEnvelope.get("clientMessage").asText());
                                assertEquals(403, deniedEnvelope.get("statusCode").asInt());
                                assertTrue(deniedEnvelope.get("body").isNull());
                                assertEquals(1, deniedEnvelope.get("errors").size());
                                assertEquals("Access denied", deniedEnvelope.get("errors").get("_global").asText());
                                var admin = new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(
                                        "Alice", "unused", List.of(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_ADMIN")));
                                org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(admin);
                                assertEquals("ok", context.getBean(MethodProbe.class).admin());
                                assertEquals(403, mvc.perform(get("/probe")
                                        .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors
                                                .authentication(admin))).andReturn().getResponse().getStatus());
                            } finally {
                                org.springframework.security.core.context.SecurityContextHolder.clearContext();
                            }
                            assertEquals(401, mvc.perform(post("/login")).andReturn().getResponse().getStatus());
                        }
                        assertFalse(http.containsHeader("WWW-Authenticate"));
                        var basic = mvc.perform(get("/probe").header("Authorization", "Basic YTpi"))
                                .andReturn().getResponse();
                        assertEquals(profile.equals("dev") ? 200 : 401, basic.getStatus());
                        assertFalse(basic.containsHeader("WWW-Authenticate"));
                    }
                }
                assertThrows(Exception.class, () -> new SpringApplicationBuilder(
                        BootProbe.class, security, mode, enforced, identity, HttpProbe.class, MethodProbe.class)
                        .web(WebApplicationType.SERVLET)
                        .initializers(application -> application.getBeanFactory()
                                .registerSingleton("accountRepositoryProbe", mock(repository)))
                        .run("--spring.profiles.active=dev", "--spring.config.location=" + config.toUri(), "--server.port=0",
                                "--server.address=0.0.0.0", "--spring.main.banner-mode=off",
                                "--logging.level.root=OFF", "--gsuif.security.probe=true"));
            } finally {
                Thread.currentThread().setContextClassLoader(previous);
            }
        }
        assertEquals(inheritedActiveProfiles, System.getProperty("spring.profiles.active"));
    }

    private boolean isDev(Class<?> mode, MockEnvironment environment) throws Exception {
        environment.setDefaultProfiles("prod");
        if ("dev".equals(environment.getProperty("spring.profiles.active"))) environment.setActiveProfiles("dev");
        Object instance = mode.getConstructor(org.springframework.core.env.Environment.class).newInstance(environment);
        return (boolean) mode.getMethod("isDev").invoke(instance);
    }

    private URLClassLoader compiled(Path scratch) throws Exception {
        var descriptor = descriptor();
        var plan = GenerationFilePlan.forDescriptor(descriptor);
        Map<String, byte[]> sources = new TreeMap<>(new CompleteBackendSecurityRenderer().render(descriptor, plan));
        sources.putAll(new CompleteBackendDefinitionRenderer().render(descriptor, plan).files().entrySet().stream()
                .filter(entry -> entry.getKey().endsWith("/ApiResponse.java"))
                .collect(java.util.stream.Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue)));
        sources.putAll(new CompleteBackendInitialMigrationRenderer().render(descriptor, plan).entrySet().stream()
                .filter(entry -> entry.getKey().endsWith("/UserAccount.java"))
                .collect(java.util.stream.Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue)));
        Path classes = Files.createDirectories(scratch.resolve("classes"));
        List<String> arguments = new ArrayList<>(List.of("-classpath", System.getProperty("java.class.path"),
                "-d", classes.toString()));
        for (var source : sources.entrySet()) {
            Path path = scratch.resolve(source.getKey());
            Files.createDirectories(path.getParent()); Files.write(path, source.getValue());
            arguments.add(path.toString());
        }
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null, arguments.toArray(String[]::new)));
        return new URLClassLoader(new java.net.URL[] { classes.toUri().toURL() }, getClass().getClassLoader());
    }
}
