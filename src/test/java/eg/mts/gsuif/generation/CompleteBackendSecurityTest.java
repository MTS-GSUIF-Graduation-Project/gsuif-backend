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
        assertEquals(11, files.size());
        assertTrue(files.keySet().stream().allMatch(path -> path.contains("/security/") && path.endsWith(".java")));
        String all = files.values().stream().map(bytes -> new String(bytes, StandardCharsets.UTF_8))
                .reduce("", String::concat);
        assertFalse(all.contains("AssetTicket") || all.contains("Gadget") || all.contains("WorkOrder"));
        assertFalse(all.contains("OPENROUTER_API_KEY"));
        assertTrue(all.contains("findByUsername(String username)"));
        assertTrue(all.contains("@Transactional(readOnly = true)"));
        assertTrue(all.contains("httpBasic(basic -> basic.disable())"));
        assertTrue(all.contains("formLogin(form -> form.disable())"));
        assertTrue(all.contains("anyRequest().authenticated()"));
        var again = renderer.render(descriptor, plan);
        for (var path : files.keySet()) assertArrayEquals(files.get(path), again.get(path));
        byte original = files.values().iterator().next()[0];
        files.values().iterator().next()[0] ^= 1;
        assertEquals(original, renderer.render(descriptor, plan).values().iterator().next()[0]);
        var absent = new GenerationFilePlan(plan.entries().stream()
                .filter(entry -> !entry.path().endsWith("/security/JwtService.java")).toList());
        assertThrows(IllegalArgumentException.class, () -> renderer.render(descriptor, absent));
        var wrong = new GenerationFilePlan(plan.entries().stream().map(entry ->
                entry.path().endsWith("/security/JwtService.java")
                        ? new Entry(entry.path(), Owner.MODEL, entry.purpose()) : entry).toList());
        assertThrows(IllegalArgumentException.class, () -> renderer.render(descriptor, wrong));
        for (String filename : List.of("AuthController.java", "LoginRequest.java", "LoginResponse.java",
                "JwtAuthenticationFilter.java")) {
            var missingNewPath = new GenerationFilePlan(plan.entries().stream()
                    .filter(entry -> !entry.path().endsWith("/security/" + filename)).toList());
            assertThrows(IllegalArgumentException.class, () -> renderer.render(descriptor, missingNewPath));
            var wrongOwner = new GenerationFilePlan(plan.entries().stream().map(entry ->
                    entry.path().endsWith("/security/" + filename)
                            ? new Entry(entry.path(), Owner.MODEL, entry.purpose()) : entry).toList());
            assertThrows(IllegalArgumentException.class, () -> renderer.render(descriptor, wrongOwner));
        }
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
            accountType.getMethod("setPasswordHash", String.class).invoke(account,
                    new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder().encode("disposable-password"));
            Set roles = (Set) accountType.getMethod("getRoles").invoke(account);
            roles.add(Enum.valueOf((Class) loader.loadClass(root + "UserAccount$Role"), "ROLE_USER"));
            Object repository = mock(repoType);
            when(repoType.getMethod("findByUsername", String.class).invoke(repository, "Alice"))
                    .thenReturn(Optional.of(account));
            when(repoType.getMethod("findById", UUID.class).invoke(repository, id))
                    .thenReturn(Optional.of(account));
        Object service = serviceType.getConstructor(repoType, org.springframework.security.crypto.password.PasswordEncoder.class)
                .newInstance(repository, new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder());
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
            assertNotNull(repoType.getMethod("findById", UUID.class)
                    .getAnnotation(org.springframework.data.jpa.repository.EntityGraph.class));
            var authenticate = serviceType.getMethod("authenticate", String.class, String.class);
            var verified = authenticate.invoke(service, "Alice", "disposable-password");
            assertEquals(id, verified.getClass().getMethod("id").invoke(verified));
            assertNull(verified.getClass().getMethod("getPassword").invoke(verified));
            assertFalse(verified.toString().contains("disposable-password"));
            assertFalse(verified.toString().contains("$2"));
            assertTrue(serviceType.getMethod("authenticate", String.class, String.class)
                    .getAnnotation(org.springframework.transaction.annotation.Transactional.class).readOnly());
            assertTrue(serviceType.getMethod("loadById", UUID.class)
                    .getAnnotation(org.springframework.transaction.annotation.Transactional.class).readOnly());
            for (String username : List.of("alice", "missing")) {
                var rejected = assertThrows(InvocationTargetException.class,
                        () -> authenticate.invoke(service, username, "disposable-password"));
                assertInstanceOf(org.springframework.security.authentication.BadCredentialsException.class,
                        rejected.getCause());
                assertEquals("Authentication failed", rejected.getCause().getMessage());
            }
            var wrongPassword = assertThrows(InvocationTargetException.class,
                    () -> authenticate.invoke(service, "Alice", "wrong"));
            assertInstanceOf(org.springframework.security.authentication.BadCredentialsException.class,
                    wrongPassword.getCause());
            assertEquals("Authentication failed", wrongPassword.getCause().getMessage());
            Object current = serviceType.getMethod("loadById", UUID.class).invoke(service, id);
            assertEquals(id, current.getClass().getMethod("id").invoke(current));
            assertTrue(((org.springframework.security.core.userdetails.UserDetails) current).getAuthorities().isEmpty());
            assertTrue(((UserDetailsService) service).loadUserByUsername("Alice").getAuthorities().isEmpty());
            when(repoType.getMethod("findByUsername", String.class).invoke(repository, "broken"))
                    .thenThrow(new IllegalStateException("disposable repository outage"));
            var infrastructure = assertThrows(InvocationTargetException.class,
                    () -> authenticate.invoke(service, "broken", "disposable-password"));
            assertInstanceOf(IllegalStateException.class, infrastructure.getCause());
            assertThrows(org.springframework.security.core.userdetails.UsernameNotFoundException.class,
                    () -> users.loadUserByUsername("alice"));
            accountType.getMethod("setEnabled", boolean.class).invoke(account, false);
            var disabled = assertThrows(InvocationTargetException.class,
                    () -> authenticate.invoke(service, "Alice", "disposable-password"));
            assertInstanceOf(org.springframework.security.authentication.BadCredentialsException.class, disabled.getCause());
            assertEquals("Authentication failed", disabled.getCause().getMessage());
            var disabledId = assertThrows(InvocationTargetException.class,
                    () -> serviceType.getMethod("loadById", UUID.class).invoke(service, id));
            assertInstanceOf(org.springframework.security.core.userdetails.UsernameNotFoundException.class,
                    disabledId.getCause());
            assertThrows(org.springframework.security.core.userdetails.UsernameNotFoundException.class,
                    () -> users.loadUserByUsername("Alice"));
        }
    }

    @Test void credentialVerificationAlwaysChecksBcryptBeforeSafeAuthenticationFailure(@TempDir Path scratch)
            throws Exception {
        try (var loader = compiled(scratch)) {
            String root = "com.example.inventory.security.";
            Class<?> accountType = loader.loadClass(root + "UserAccount");
            Class<?> repoType = loader.loadClass(root + "UserAccountRepository");
            Class<?> serviceType = loader.loadClass(root + "AccountIdentityService");
            Object account = accountType.getConstructor().newInstance();
            accountType.getMethod("setUsername", String.class).invoke(account, "Alice");
            accountType.getMethod("setPasswordHash", String.class).invoke(account, "real-test-hash");
            Object repository = mock(repoType);
            when(repoType.getMethod("findByUsername", String.class).invoke(repository, "disabled"))
                    .thenReturn(Optional.of(account));
            when(repoType.getMethod("findByUsername", String.class).invoke(repository, "Alice"))
                    .thenReturn(Optional.of(account));
            var passwords = mock(org.springframework.security.crypto.password.PasswordEncoder.class);
            Object service = serviceType.getConstructor(repoType, org.springframework.security.crypto.password.PasswordEncoder.class)
                    .newInstance(repository, passwords);
            var authenticate = serviceType.getMethod("authenticate", String.class, String.class);
            for (String username : List.of("missing", "disabled")) {
                var error = assertThrows(InvocationTargetException.class,
                        () -> authenticate.invoke(service, username, "disposable-password"));
                assertInstanceOf(org.springframework.security.authentication.BadCredentialsException.class,
                        error.getCause());
                assertEquals("Authentication failed", error.getCause().getMessage());
            }
            accountType.getMethod("setEnabled", boolean.class).invoke(account, true);
            var wrong = assertThrows(InvocationTargetException.class,
                    () -> authenticate.invoke(service, "Alice", "disposable-password"));
            assertInstanceOf(org.springframework.security.authentication.BadCredentialsException.class,
                    wrong.getCause());
            assertEquals("Authentication failed", wrong.getCause().getMessage());
            var hashes = org.mockito.ArgumentCaptor.forClass(String.class);
            verify(passwords, times(3)).matches(eq("disposable-password"), hashes.capture());
            assertEquals(hashes.getAllValues().get(0), hashes.getAllValues().get(1));
            assertTrue(hashes.getAllValues().get(0).matches("\\$2[aby]\\$10\\$.{53}"));
            assertEquals("real-test-hash", hashes.getAllValues().get(2));
        }
    }

    @Test void emittedTokenServiceEnforcesClaimsAndClockBoundaries(@TempDir Path scratch) throws Exception {
        try (var loader = compiled(scratch)) {
            String root = "com.example.inventory.security.";
            Class<?> serviceType = loader.loadClass(root + "JwtService");
            Class<?> principalType = loader.loadClass(root + "AuthenticatedAccount");
            byte[] fakeKey = new byte[64]; java.util.Arrays.fill(fakeKey, (byte) 7);
            String fakeSecret = java.util.Base64.getEncoder().encodeToString(fakeKey);
            var environment = new MockEnvironment().withProperty("GENERATED_APP_JWT_SECRET", fakeSecret);
            var start = java.time.Instant.parse("2026-10-16T00:00:00Z");
            var clock = new java.time.Clock() {
                private java.time.Instant current = start;
                @Override public java.time.ZoneId getZone() { return java.time.ZoneOffset.UTC; }
                @Override public java.time.Clock withZone(java.time.ZoneId zone) { return this; }
                @Override public java.time.Instant instant() { return current; }
                void advance(java.time.Duration duration) { current = current.plus(duration); }
            };
            Object codec = serviceType.getConstructor(org.springframework.core.env.Environment.class, java.time.Clock.class)
                    .newInstance(environment, clock);
            UUID id = UUID.randomUUID();
            Object principal = principalType.getConstructor(UUID.class, String.class, Set.class)
                    .newInstance(id, "Alice", Set.of());
            String token = (String) serviceType.getMethod("issue", principalType).invoke(codec, principal);
            var decoderField = serviceType.getDeclaredField("decoder"); decoderField.setAccessible(true);
            var decoded = ((org.springframework.security.oauth2.jwt.NimbusJwtDecoder)
                    decoderField.get(codec)).decode(token);
            assertEquals(id.toString(), decoded.getSubject());
            assertEquals("urn:generated-app:com.example.inventory:stock-unit-api:2.0.0", decoded.getClaimAsString("iss"));
            assertEquals(List.of("com.example.inventory"), decoded.getAudience());
            assertEquals(start, decoded.getIssuedAt());
            assertEquals(start, decoded.getNotBefore());
            assertEquals(start.plusSeconds(900), decoded.getExpiresAt());
            assertEquals(id, serviceType.getMethod("validate", String.class).invoke(codec, token));
            assertFalse(codec.toString().contains(fakeSecret));
            var payload = new com.fasterxml.jackson.databind.ObjectMapper().readTree(
                    java.util.Base64.getUrlDecoder().decode(token.split("\\.")[1]));
            assertEquals(id.toString(), payload.get("sub").asText());
            assertEquals("urn:generated-app:com.example.inventory:stock-unit-api:2.0.0", payload.get("iss").asText());
            assertEquals(List.of("com.example.inventory"), decoded.getAudience());
            assertEquals(900, payload.get("exp").asLong() - payload.get("iat").asLong());
            assertEquals(payload.get("iat").asLong(), payload.get("nbf").asLong());
            var header = new com.fasterxml.jackson.databind.ObjectMapper().readTree(
                    java.util.Base64.getUrlDecoder().decode(token.split("\\.")[0]));
            assertEquals("HS256", header.get("alg").asText());
            var encoderField = serviceType.getDeclaredField("encoder"); encoderField.setAccessible(true);
            var encoder = (org.springframework.security.oauth2.jwt.NimbusJwtEncoder) encoderField.get(codec);
            java.util.function.Function<org.springframework.security.oauth2.jwt.JwtClaimsSet, String> sign = claims ->
                    encoder.encode(org.springframework.security.oauth2.jwt.JwtEncoderParameters.from(
                            org.springframework.security.oauth2.jwt.JwsHeader.with(
                                    org.springframework.security.oauth2.jose.jws.MacAlgorithm.HS256).build(), claims))
                            .getTokenValue();
            java.util.function.Function<org.springframework.security.oauth2.jwt.JwtClaimsSet.Builder,
                    org.springframework.security.oauth2.jwt.JwtClaimsSet.Builder> standard = builder -> builder
                    .issuer("urn:generated-app:com.example.inventory:stock-unit-api:2.0.0")
                    .audience(List.of("com.example.inventory")).subject(id.toString())
                    .issuedAt(start).notBefore(start).expiresAt(start.plusSeconds(900));
            var hs384Key = new javax.crypto.spec.SecretKeySpec(fakeKey, "HmacSHA384");
            var hs384Encoder = org.springframework.security.oauth2.jwt.NimbusJwtEncoder.withSecretKey(hs384Key)
                    .algorithm(org.springframework.security.oauth2.jose.jws.MacAlgorithm.HS384).build();
            String signedHs384 = hs384Encoder.encode(org.springframework.security.oauth2.jwt.JwtEncoderParameters.from(
                    org.springframework.security.oauth2.jwt.JwsHeader.with(
                            org.springframework.security.oauth2.jose.jws.MacAlgorithm.HS384).build(),
                    standard.apply(org.springframework.security.oauth2.jwt.JwtClaimsSet.builder()).build()))
                    .getTokenValue();
            var nonHs256Header = new com.fasterxml.jackson.databind.ObjectMapper().readTree(
                    java.util.Base64.getUrlDecoder().decode(signedHs384.split("\\.")[0]));
            assertEquals("HS384", nonHs256Header.get("alg").asText());
            assertInstanceOf(org.springframework.security.authentication.BadCredentialsException.class,
                    assertThrows(InvocationTargetException.class,
                            () -> serviceType.getMethod("validate", String.class).invoke(codec, signedHs384))
                            .getCause());
            List<org.springframework.security.oauth2.jwt.JwtClaimsSet> rejectedClaims = List.of(
                    org.springframework.security.oauth2.jwt.JwtClaimsSet.builder().subject(id.toString()).build(),
                    standard.apply(org.springframework.security.oauth2.jwt.JwtClaimsSet.builder())
                            .claims(claims -> claims.remove("iat")).build(),
                    standard.apply(org.springframework.security.oauth2.jwt.JwtClaimsSet.builder())
                            .claims(claims -> claims.remove("nbf")).build(),
                    standard.apply(org.springframework.security.oauth2.jwt.JwtClaimsSet.builder())
                            .claims(claims -> claims.remove("exp")).build(),
                    org.springframework.security.oauth2.jwt.JwtClaimsSet.builder()
                            .issuer("urn:generated-app:com.example.inventory:stock-unit-api:2.0.0")
                            .audience(List.of("com.example.inventory"))
                            .issuedAt(start).notBefore(start).expiresAt(start.plusSeconds(900)).build(),
                    standard.apply(org.springframework.security.oauth2.jwt.JwtClaimsSet.builder())
                            .issuer("urn:generated-app:other:stock-unit-api:2.0.0").build(),
                    standard.apply(org.springframework.security.oauth2.jwt.JwtClaimsSet.builder())
                            .audience(List.of("other-app")).build(),
                    standard.apply(org.springframework.security.oauth2.jwt.JwtClaimsSet.builder())
                            .subject("not-a-uuid").build(),
                    standard.apply(org.springframework.security.oauth2.jwt.JwtClaimsSet.builder())
                            .notBefore(start.plusSeconds(31)).build(),
                    standard.apply(org.springframework.security.oauth2.jwt.JwtClaimsSet.builder())
                            .issuedAt(start.plusSeconds(31)).build(),
                    standard.apply(org.springframework.security.oauth2.jwt.JwtClaimsSet.builder())
                            .expiresAt(start.plusSeconds(901)).build());
            for (var claims : rejectedClaims) {
                String rejectedToken = sign.apply(claims);
                assertInstanceOf(org.springframework.security.authentication.BadCredentialsException.class,
                        assertThrows(InvocationTargetException.class,
                                () -> serviceType.getMethod("validate", String.class).invoke(codec, rejectedToken))
                                .getCause());
            }
            String[] segments = token.split("\\.");
            String tampered = segments[0] + "." + (segments[1].startsWith("A") ? "B" : "A")
                    + segments[1].substring(1) + "." + segments[2];
            assertInstanceOf(org.springframework.security.authentication.BadCredentialsException.class,
                    assertThrows(InvocationTargetException.class,
                            () -> serviceType.getMethod("validate", String.class).invoke(codec, tampered)).getCause());
            clock.advance(java.time.Duration.ofMinutes(15).plusSeconds(29));
            assertEquals(id, serviceType.getMethod("validate", String.class).invoke(codec, token));
            clock.advance(java.time.Duration.ofSeconds(1));
            assertInstanceOf(org.springframework.security.authentication.BadCredentialsException.class,
                    assertThrows(InvocationTargetException.class,
                            () -> serviceType.getMethod("validate", String.class).invoke(codec, token)).getCause());
        }
    }

    @Test void tokenSecretValidationHasNoSecretInFailureAndDevNeedsNoTokenBean(@TempDir Path scratch) throws Exception {
        try (var loader = compiled(scratch)) {
            Class<?> serviceType = loader.loadClass("com.example.inventory.security.JwtService");
            for (String invalid : List.of("", "not-base64!", java.util.Base64.getEncoder().encodeToString(new byte[8]))) {
                var error = assertThrows(InvocationTargetException.class,
                        () -> serviceType.getConstructor(org.springframework.core.env.Environment.class)
                                .newInstance(new MockEnvironment().withProperty("GENERATED_APP_JWT_SECRET", invalid)));
                assertInstanceOf(IllegalStateException.class, error.getCause());
                assertEquals("Invalid generated-app JWT secret configuration", error.getCause().getMessage());
                assertFalse(error.getCause().getMessage().contains(invalid.isEmpty() ? "not-base64!" : invalid));
            }
            var missing = assertThrows(InvocationTargetException.class,
                    () -> serviceType.getConstructor(org.springframework.core.env.Environment.class)
                            .newInstance(new MockEnvironment()));
            assertEquals("Invalid generated-app JWT secret configuration", missing.getCause().getMessage());
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
                Class<?> jwt = loader.loadClass("com.example.inventory.security.JwtService");
                Class<?> auth = loader.loadClass("com.example.inventory.security.AuthController");
                Class<?> repository = loader.loadClass("com.example.inventory.security.UserAccountRepository");
                for (String profile : List.of("dev", "test", "prod", "default")) {
                    var builder = new SpringApplicationBuilder(
                            BootProbe.class, security, mode, enforced, identity, jwt, auth, HttpProbe.class, MethodProbe.class)
                            .web(WebApplicationType.SERVLET)
                            .properties("spring.main.banner-mode=off")
                            .initializers(application -> application.getBeanFactory()
                                    .registerSingleton("accountRepositoryProbe", mock(repository)));
                    List<String> args = new ArrayList<>(List.of(
                            "--spring.profiles.active=" + (profile.equals("default") ? "" : profile),
                            "--spring.config.location=" + config.toUri(), "--server.port=0",
                            "--spring.main.banner-mode=off", "--logging.level.root=OFF",
                            "--gsuif.security.probe=true"));
                    if (!profile.equals("dev")) args.add("--GENERATED_APP_JWT_SECRET="
                            + java.util.Base64.getEncoder().encodeToString(new byte[32]));
                    try (ConfigurableApplicationContext context = builder.run(args.toArray(String[]::new))) {
                        assertArrayEquals(profile.equals("default") ? new String[0] : new String[] {profile},
                                context.getEnvironment().getActiveProfiles());
                        if (profile.equals("default"))
                            assertArrayEquals(new String[] {"prod"}, context.getEnvironment().getDefaultProfiles());
                        var mvc = MockMvcBuilders.webAppContextSetup((WebApplicationContext) context)
                                .apply(SecurityMockMvcConfigurers.springSecurity()).build();
                        assertEquals(1, context.getBeansOfType(UserDetailsService.class).size());
                        assertEquals(profile.equals("dev") ? 0 : 1, context.getBeansOfType(jwt).size());
                        assertEquals(profile.equals("dev") ? 0 : 1, context.getBeansOfType(auth).size());
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
                                assertEquals(200, forbidden.getStatus());
                                var admin = new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(
                                        "Alice", "unused", List.of(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_ADMIN")));
                                org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(admin);
                                assertEquals("ok", context.getBean(MethodProbe.class).admin());
                                assertEquals(200, mvc.perform(get("/probe")
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
                var missingSecret = new SpringApplicationBuilder(
                        BootProbe.class, security, mode, enforced, identity, jwt, auth, HttpProbe.class, MethodProbe.class)
                        .web(WebApplicationType.SERVLET)
                        .initializers(application -> application.getBeanFactory()
                                .registerSingleton("accountRepositoryProbe", mock(repository)));
                assertThrows(Exception.class, () -> missingSecret.run(
                        "--spring.profiles.active=prod", "--spring.config.location=" + config.toUri(),
                        "--server.port=0", "--spring.main.banner-mode=off", "--logging.level.root=OFF",
                        "--gsuif.security.probe=true", "--GENERATED_APP_JWT_SECRET="));
                assertThrows(Exception.class, () -> new SpringApplicationBuilder(
                        BootProbe.class, security, mode, enforced, identity, jwt, auth, HttpProbe.class, MethodProbe.class)
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

    @Test void emittedHttpLoginBearerAndCrudDenialUseCurrentAccountState(@TempDir Path scratch) throws Exception {
        try (var loader = compiled(scratch)) {
            String base = "com.example.inventory.";
            Class<?> security = loader.loadClass(base + "security.SecurityConfig");
            Class<?> mode = loader.loadClass(base + "security.SecurityMode");
            Class<?> enforced = loader.loadClass(base + "security.EnforcedMethodSecurityConfig");
            Class<?> identity = loader.loadClass(base + "security.AccountIdentityService");
            Class<?> jwt = loader.loadClass(base + "security.JwtService");
            Class<?> auth = loader.loadClass(base + "security.AuthController");
            Class<?> handler = loader.loadClass(base + "exception.GlobalExceptionHandler");
            Class<?> controller = loader.loadClass(base + "controller.StockUnitController");
            Class<?> service = loader.loadClass(base + "service.StockUnitService");
            Class<?> repository = loader.loadClass(base + "security.UserAccountRepository");
            Class<?> accountType = loader.loadClass(base + "security.UserAccount");
            UUID accountId = UUID.randomUUID();
            Object account = accountType.getConstructor().newInstance();
            var idField = accountType.getDeclaredField("id"); idField.setAccessible(true);
            idField.set(account, accountId);
            accountType.getMethod("setUsername", String.class).invoke(account, "Alice");
            accountType.getMethod("setEnabled", boolean.class).invoke(account, true);
            accountType.getMethod("setPasswordHash", String.class).invoke(account,
                    new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder().encode("disposable-password"));
            Set roles = (Set) accountType.getMethod("getRoles").invoke(account);
            Class<? extends Enum> roleType = (Class<? extends Enum>) loader.loadClass(base + "security.UserAccount$Role");
            roles.add(Enum.valueOf(roleType, "ROLE_USER"));
            var currentAccount = new java.util.concurrent.atomic.AtomicReference<>(account);
            Object accounts = mock(repository);
            when(repository.getMethod("findByUsername", String.class).invoke(accounts, "Alice"))
                    .thenAnswer(invocation -> Optional.ofNullable(currentAccount.get()));
            when(repository.getMethod("findById", UUID.class).invoke(accounts, accountId))
                    .thenAnswer(invocation -> Optional.ofNullable(currentAccount.get()));
            when(repository.getMethod("findByUsername", String.class).invoke(accounts, "broken"))
                    .thenThrow(new IllegalStateException("disposable infrastructure diagnostic"));
            var calls = new java.util.concurrent.atomic.AtomicInteger();
            UUID itemId = UUID.randomUUID();
            Class<?> conditionType = loader.loadClass(base + "dto.StockUnitCondition");
            Object dto = loader.loadClass(base + "dto.StockUnitDto")
                    .getConstructors()[0].newInstance(itemId, "sample", conditionType.getEnumConstants()[0], null);
            Object stockService = java.lang.reflect.Proxy.newProxyInstance(loader, new Class<?>[] {service},
                    (proxy, method, values) -> {
                        if (method.getDeclaringClass() == Object.class) return method.getName().equals("toString")
                                ? "stock-service-probe" : null;
                        calls.incrementAndGet();
                        return method.getName().equals("getById") ? dto : null;
                    });
            var descriptor = descriptor();
            var runtime = new CompleteBackendRuntimeRenderer(new PackagedContractRegistry(
                    "/generation/stock-unit-contracts.yaml"))
                    .render(descriptor, GenerationFilePlan.forDescriptor(descriptor));
            Path config = scratch.resolve("generated-application.yml");
            Files.write(config, runtime.get("backend/src/main/resources/application.yml"));
            byte[] fakeKey = new byte[32]; java.util.Arrays.fill(fakeKey, (byte) 9);
            String fakeSecret = java.util.Base64.getEncoder().encodeToString(fakeKey);
            ClassLoader previous = Thread.currentThread().getContextClassLoader();
            Thread.currentThread().setContextClassLoader(loader);
            try (var context = new SpringApplicationBuilder(BootProbe.class, security, mode, enforced,
                    identity, jwt, auth, handler, controller)
                    .web(WebApplicationType.SERVLET)
                    .initializers(application -> {
                        application.getBeanFactory().registerSingleton("accountRepositoryProbe", accounts);
                        application.getBeanFactory().registerSingleton("stockServiceProbe", stockService);
                    })
                    .run("--spring.profiles.active=test", "--spring.config.location=" + config.toUri(),
                            "--server.port=0", "--spring.main.banner-mode=off", "--logging.level.root=OFF",
                            "--gsuif.security.probe=true", "--GENERATED_APP_JWT_SECRET=" + fakeSecret)) {
                var mvc = MockMvcBuilders.webAppContextSetup((WebApplicationContext) context)
                        .apply(SecurityMockMvcConfigurers.springSecurity()).build();
                String login = "{\"username\":\"Alice\",\"password\":\"disposable-password\"}";
                var successResult = mvc.perform(post("/api/auth/login").contentType("application/json")
                        .content(login)).andReturn();
                var success = successResult.getResponse();
                assertEquals(200, success.getStatus());
                var body = new com.fasterxml.jackson.databind.ObjectMapper().readTree(success.getContentAsString());
                assertEquals(5, body.size());
                assertEquals("OK", body.get("status").asText());
                assertEquals(200, body.get("statusCode").asInt());
                assertEquals("Bearer", body.get("body").get("tokenType").asText());
                assertEquals(900, body.get("body").get("expiresInSeconds").asInt());
                String token = body.get("body").get("accessToken").asText();
                assertFalse(token.isBlank());
                assertNull(success.getHeader("Set-Cookie"));
                assertNull(successResult.getRequest().getSession(false));
                Class<?> requestType = loader.loadClass(base + "security.LoginRequest");
                Class<?> responseType = loader.loadClass(base + "security.LoginResponse");
                assertFalse(requestType.getConstructor(String.class, String.class)
                        .newInstance("Alice", "disposable-password").toString().contains("disposable-password"));
                assertFalse(responseType.getConstructor(String.class).newInstance(token).toString().contains(token));
                assertInstanceOf(IllegalArgumentException.class,
                        assertThrows(InvocationTargetException.class,
                                () -> responseType.getConstructor(String.class).newInstance("")).getCause());
                org.mockito.Mockito.clearInvocations(accounts);
                assertSafeEnvelope(mvc.perform(post("/api/auth/login").contentType("application/json")
                        .content("{\"username\":\"Alice\"}")).andReturn().getResponse(), 400);
                verifyNoInteractions(accounts);
                assertSafeEnvelope(mvc.perform(post("/api/auth/login").contentType("application/json")
                        .content("{\"username\":\"Alice\",\"password\":\"wrong\"}"))
                        .andReturn().getResponse(), 401);
                assertSafeEnvelope(mvc.perform(post("/api/auth/login").contentType("application/json")
                        .content("{\"username\":\"nobody\",\"password\":\"wrong\"}"))
                        .andReturn().getResponse(), 401);
                accountType.getMethod("setEnabled", boolean.class).invoke(account, false);
                assertSafeEnvelope(mvc.perform(post("/api/auth/login").contentType("application/json")
                        .content(login)).andReturn().getResponse(), 401);
                accountType.getMethod("setEnabled", boolean.class).invoke(account, true);
                var infrastructure = mvc.perform(post("/api/auth/login").contentType("application/json")
                        .content("{\"username\":\"broken\",\"password\":\"wrong\"}"))
                        .andReturn().getResponse();
                assertSafeEnvelope(infrastructure, 500);
                assertFalse(infrastructure.getContentAsString().contains("infrastructure diagnostic"));
                String itemPath = "/api/v1/stock-units/" + itemId;
                assertSafeEnvelope(mvc.perform(get(itemPath)).andReturn().getResponse(), 401);
                var session = new org.springframework.mock.web.MockHttpSession();
                var sessionContext = new org.springframework.security.core.context.SecurityContextImpl();
                sessionContext.setAuthentication(org.springframework.security.authentication
                        .UsernamePasswordAuthenticationToken.authenticated("stale", null,
                                List.of(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_ADMIN"))));
                session.setAttribute(org.springframework.security.web.context.HttpSessionSecurityContextRepository
                        .SPRING_SECURITY_CONTEXT_KEY, sessionContext);
                assertSafeEnvelope(mvc.perform(get(itemPath).session(session)).andReturn().getResponse(), 401);
                assertSafeEnvelope(mvc.perform(get(itemPath).header("Authorization", "Bearer invalid.token.value"))
                        .andReturn().getResponse(), 401);
                assertSafeEnvelope(mvc.perform(get(itemPath).header("Authorization", "Bearer " + token))
                        .andReturn().getResponse(), 403);
                assertEquals(0, calls.get());
                roles.clear(); roles.add(Enum.valueOf(roleType, "ROLE_ADMIN"));
                assertEquals(200, mvc.perform(get(itemPath).header("Authorization", "Bearer " + token))
                        .andReturn().getResponse().getStatus());
                assertEquals(1, calls.get());
                roles.clear();
                assertSafeEnvelope(mvc.perform(get(itemPath).header("Authorization", "Bearer " + token))
                        .andReturn().getResponse(), 403);
                assertEquals(1, calls.get());
                accountType.getMethod("setEnabled", boolean.class).invoke(account, false);
                assertSafeEnvelope(mvc.perform(get(itemPath).header("Authorization", "Bearer " + token))
                        .andReturn().getResponse(), 401);
                accountType.getMethod("setEnabled", boolean.class).invoke(account, true);
                currentAccount.set(null);
                assertSafeEnvelope(mvc.perform(get(itemPath).header("Authorization", "Bearer " + token))
                        .andReturn().getResponse(), 401);
                currentAccount.set(account);
                var oldClock = java.time.Clock.fixed(java.time.Instant.now().minusSeconds(1000),
                        java.time.ZoneOffset.UTC);
                Object oldCodec = jwt.getConstructor(org.springframework.core.env.Environment.class, java.time.Clock.class)
                        .newInstance(new MockEnvironment().withProperty("GENERATED_APP_JWT_SECRET", fakeSecret), oldClock);
                Object principal = loader.loadClass(base + "security.AuthenticatedAccount")
                        .getConstructor(UUID.class, String.class, Set.class).newInstance(accountId, "Alice", Set.of());
                String expired = (String) jwt.getMethod("issue", principal.getClass()).invoke(oldCodec, principal);
                assertSafeEnvelope(mvc.perform(get(itemPath).header("Authorization", "Bearer " + expired))
                        .andReturn().getResponse(), 401);
                when(repository.getMethod("findById", UUID.class).invoke(accounts, accountId))
                        .thenThrow(new IllegalStateException("disposable infrastructure diagnostic"));
                var bearerInfrastructure = mvc.perform(get(itemPath).header("Authorization", "Bearer " + token))
                        .andReturn().getResponse();
                assertSafeEnvelope(bearerInfrastructure, 500);
                assertFalse(bearerInfrastructure.getContentAsString().contains("infrastructure diagnostic"));
                assertEquals(1, calls.get());
            } finally {
                Thread.currentThread().setContextClassLoader(previous);
                org.springframework.security.core.context.SecurityContextHolder.clearContext();
            }
        }
    }

    private void assertSafeEnvelope(org.springframework.mock.web.MockHttpServletResponse response, int status)
            throws Exception {
        assertEquals(status, response.getStatus());
        var json = new com.fasterxml.jackson.databind.ObjectMapper().readTree(response.getContentAsString());
        assertEquals(5, json.size());
        assertEquals(org.springframework.http.HttpStatus.valueOf(status).name(), json.get("status").asText());
        assertEquals(status, json.get("statusCode").asInt());
        assertTrue(json.get("body").isNull());
        assertEquals(1, json.get("errors").size());
        assertTrue(json.get("errors").has("_global"));
        String message = switch (status) {
            case 400 -> "Invalid request";
            case 401 -> "Authentication required";
            case 403 -> "Access denied";
            case 500 -> "Unexpected server error";
            default -> throw new IllegalArgumentException("Unexpected test status");
        };
        assertEquals(message, json.get("clientMessage").asText());
        assertEquals(message, json.get("errors").get("_global").asText());
        assertFalse(response.getContentAsString().contains("disposable-password"));
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
        sources.putAll(new CompleteBackendDefinitionRenderer().render(descriptor, plan).files());
        sources.putAll(new CompleteBackendApiRenderer().render(descriptor, plan));
        sources.putAll(new CompleteBackendErrorRenderer().render(descriptor, plan));
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
