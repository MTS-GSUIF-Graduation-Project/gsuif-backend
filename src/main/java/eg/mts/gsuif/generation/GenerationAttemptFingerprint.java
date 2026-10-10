package eg.mts.gsuif.generation;

import eg.mts.gsuif.entity.GsuifUser;
import eg.mts.gsuif.entity.MetadataVersion;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.io.IOException;
import java.util.Map;
import java.util.Set;

/** Stable, length-framed fingerprint of inputs that determine a generation/build attempt. */
final class GenerationAttemptFingerprint {
    private final MessageDigest digest;

    private GenerationAttemptFingerprint() {
        try { digest = MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException ex) { throw new IllegalStateException(ex); }
    }

    static String request(MetadataVersion version, GsuifUser user, GenerationSpecification spec,
            Set<GenerationContext.Target> targets, String framework, Map<String, byte[]> consumerInputs) {
        var hash = new GenerationAttemptFingerprint();
        hash.add("generation-request-v1");
        hash.identity(version, user);
        hash.add(framework);
        targets.stream().map(Enum::name).sorted().forEach(hash::add);
        hash.add("targets-end");
        if (spec == null) hash.add("null-specification");
        else {
            hash.add(spec.specVersion()); hash.add(spec.basePackage());
            var entity = spec.entity();
            if (entity == null) hash.add("null-entity");
            else {
                hash.add(entity.className()); hash.add(entity.tableName()); hash.add(Boolean.toString(entity.enversAudited()));
                if (entity.fields() != null) for (var field : entity.fields()) {
                    hash.add(field.name()); hash.add(field.columnName()); hash.add(field.javaType());
                    hash.add(Boolean.toString(field.nullable())); hash.add(field.columnLength() == null ? null : field.columnLength().toString());
                    hash.add(field.enumType());
                }
                hash.add("fields-end");
            }
            var openApi = spec.openApi();
            if (openApi == null) hash.add("null-openapi");
            else {
                hash.add(openApi.contractId()); hash.add(openApi.contractVersion());
                if (openApi.bindingOperations() != null) openApi.bindingOperations().entrySet().stream()
                        .sorted(Map.Entry.comparingByKey()).forEach(entry -> {
                            hash.add(entry.getKey().toString()); hash.add(entry.getValue());
                        });
                hash.add("bindings-end");
            }
            if (spec.operationRoles() != null) spec.operationRoles().entrySet().stream()
                    .sorted(Map.Entry.comparingByKey()).forEach(entry -> {
                        hash.add(entry.getKey());
                        if (entry.getValue() != null) entry.getValue().stream().map(Enum::name).sorted().forEach(hash::add);
                        hash.add("roles-end");
                    });
            hash.add("operations-end");
            var angular = spec.angular();
            if (angular == null) hash.add("null-angular");
            else {
                hash.add(angular.className());
                if (angular.tableColumns() != null) angular.tableColumns().entrySet().stream()
                        .sorted(Map.Entry.comparingByKey()).forEach(entry -> {
                            hash.add(entry.getKey().toString());
                            if (entry.getValue() != null) entry.getValue().forEach(hash::add);
                            hash.add("columns-end");
                        });
                hash.add("tables-end");
            }
        }
        hash.inputs(consumerInputs);
        var catalog = GenerationCatalog.load();
        hash.add(catalog.version()); hash.add(catalog.providerVersion());
        hash.resource("components.yaml");
        if (spec != null && spec.openApi() != null && spec.openApi().contractId() != null) {
            String contract = switch (spec.openApi().contractId()) {
                case "asset-ticket-api" -> "openapi/asset-ticket-api.yaml";
                case "service-request-api" -> "openapi/service-request-api.yaml";
                default -> null;
            };
            if (contract != null) hash.resource(contract);
        }
        java.util.stream.Stream.of(catalog.entity().path(), catalog.controller().path(),
                catalog.angularTypescript().path(), catalog.angularHtml().path()).distinct().sorted()
                .forEach(hash::resource);
        java.util.stream.Stream.of("eg/mts/gsuif/dto/ApiResponse.java", "eg/mts/gsuif/dto/PagedBody.java",
                "eg/mts/gsuif/entity/AuditableEntity.java", "eg/mts/gsuif/audit/AuditorAwareImpl.java",
                "eg/mts/gsuif/config/JpaAuditingConfig.java")
                .forEach(path -> hash.resource("consumer-support/" + path));
        return hash.finish();
    }

    static String result(MetadataVersion version, GsuifUser user, GenerationResult result,
            Map<String, byte[]> consumerInputs) {
        var hash = new GenerationAttemptFingerprint();
        hash.add("generated-result-v1"); hash.identity(version, user);
        for (var artifact : result.artifacts()) {
            hash.add(artifact.relativePath()); hash.add(artifact.bytes());
            hash.add(artifact.sha256()); hash.add(artifact.templateVersion()); hash.add(artifact.catalogVersion());
        }
        hash.add("artifacts-end");
        result.diagnostics().forEach(hash::add); hash.add("diagnostics-end");
        var contract = result.consumerBuild();
        if (contract == null) hash.add("null-consumer-build");
        else {
            hash.add(contract.contractVersion()); hash.add(Integer.toString(contract.javaVersion()));
            hash.add(contract.parent().toString());
            contract.dependencies().forEach(dependency -> hash.add(dependency.toString()));
            hash.add(contract.methodSecurityPrerequisite());
        }
        hash.inputs(consumerInputs);
        return hash.finish();
    }

    private void identity(MetadataVersion version, GsuifUser user) {
        add(version.getId().toString()); add(version.getSchemaVersion()); add(version.getSnapshot());
        add(version.getPage() == null || version.getPage().getProject() == null
                ? null : version.getPage().getProject().getName());
        add(user.getId().toString());
    }

    private void inputs(Map<String, byte[]> inputs) {
        inputs.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry -> {
            add(entry.getKey()); add(entry.getValue());
        });
        add("consumer-inputs-end");
    }

    private void resource(String path) {
        add(path);
        try (var stream = GenerationAttemptFingerprint.class.getResourceAsStream("/" + path)) {
            if (stream == null) throw new IllegalStateException("Generation input resource is missing: " + path);
            add(stream.readAllBytes());
        } catch (IOException ex) { throw new IllegalStateException("Cannot fingerprint generation input: " + path, ex); }
    }

    private void add(String value) {
        if (value == null) { digest.update(ByteBuffer.allocate(4).putInt(-1).array()); return; }
        add(value.getBytes(StandardCharsets.UTF_8));
    }

    private void add(byte[] value) {
        if (value == null) { digest.update(ByteBuffer.allocate(4).putInt(-1).array()); return; }
        digest.update(ByteBuffer.allocate(4).putInt(value.length).array()); digest.update(value);
    }

    private String finish() { return java.util.HexFormat.of().formatHex(digest.digest()); }
}
