package eg.mts.gsuif.generation;

import eg.mts.gsuif.generation.GenerationCapabilityPreflight.OutputMode;
import eg.mts.gsuif.generation.GenerationFilePlan.Entry;
import eg.mts.gsuif.generation.GenerationFilePlan.Owner;
import eg.mts.gsuif.generation.GenerationSpecification.*;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.TreeMap;
import java.io.InputStream;
import java.io.ByteArrayInputStream;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Element;

import static org.junit.jupiter.api.Assertions.*;

class CompleteBackendRuntimeTest {
    private final CompleteOutputTestFixture fixture = new CompleteOutputTestFixture();

    @Test void runtimeScaffoldIsPlannedSecretFreeAndCopiesExactContractBytes() throws Exception {
        var descriptor = fixture.preflight().describe(fixture.version(fixture.snapshot("condition")), fixture.specification(), OutputMode.BACKEND);
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
        assertEquals(9, dependencies.getLength());
        assertTrue(pom.contains("<artifactId>spring-security-oauth2-jose</artifactId><version>7.0.7</version>"));
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
                "GENERATED_APP_DB_PASSWORD")) {
            assertTrue(config.contains("${" + key + "}"));
            assertTrue(readme.contains(key));
            assertFalse(config.contains("${" + key + ":"), "No default may be embedded");
        }
        assertFalse(config.contains("GENERATED_APP_JWT_SECRET"));
        assertTrue(readme.contains("GENERATED_APP_JWT_SECRET"));
        assertTrue(config.contains("default: prod"));
        assertTrue(config.contains("address: 127.0.0.1"));
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
        var descriptor = fixture.preflight().describe(fixture.version(fixture.snapshot("condition")), fixture.specification(), OutputMode.BACKEND);
        var plan = GenerationFilePlan.forDescriptor(descriptor);
        var registry = new PackagedContractRegistry("/generation/stock-unit-contracts.yaml");
        var renderer = new CompleteBackendRuntimeRenderer(registry);
        var files = renderer.render(descriptor, plan);
        var frontend = fixture.preflight().describe(fixture.version(fixture.snapshot("condition")), fixture.specification(), OutputMode.FRONTEND);
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
        var base = fixture.specification();
        var audited = new GenerationSpecification(base.specVersion(), base.basePackage(),
                new EntitySpec(base.entity().className(), base.entity().tableName(),
                        base.entity().fields(), true), base.openApi(), base.operationRoles(), base.angular());
        var descriptor = fixture.preflight().describe(fixture.version(fixture.snapshot("condition")), audited, OutputMode.BACKEND);
        String pom = new String(new CompleteBackendRuntimeRenderer(new PackagedContractRegistry(
                "/generation/stock-unit-contracts.yaml"))
                .render(descriptor, GenerationFilePlan.forDescriptor(descriptor)).get("backend/pom.xml"),
                StandardCharsets.UTF_8);
        assertTrue(pom.contains("<artifactId>hibernate-envers</artifactId><version>7.2.24.Final</version>"));
    }

}
