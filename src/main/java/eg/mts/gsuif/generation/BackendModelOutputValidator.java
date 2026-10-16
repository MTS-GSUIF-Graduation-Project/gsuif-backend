package eg.mts.gsuif.generation;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.source.tree.ClassTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.util.JavacTask;

import javax.lang.model.element.Modifier;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.ToolProvider;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;

/** Validates the sole model-owned source before any package assembly. */
final class BackendModelOutputValidator {
    enum Reason { INVALID_RESPONSE, MISSING_PATH, EXTRA_PATH, DUPLICATE_PATH, UNSAFE_PATH,
        WRONG_OWNER, INVALID_JAVA_STRUCTURE, COMPILE_FAILED }
    static final class Rejected extends RuntimeException {
        private final Reason reason;
        Rejected(Reason reason) { super(reason.name()); this.reason = reason; }
        Reason reason() { return reason; }
    }
    @FunctionalInterface interface Compiler { boolean compile(BackendModelInput input, byte[] source); }
    record Accepted(String path, byte[] source) {
        Accepted { source = source.clone(); }
        @Override public byte[] source() { return source.clone(); }
    }

    private static final int MAX_RESPONSE_BYTES = 65536;
    private static final int MAX_SOURCE_BYTES = 24576;
    private final ObjectMapper json = new ObjectMapper();
    private final Compiler compiler;

    BackendModelOutputValidator(Compiler compiler) {
        this.compiler = java.util.Objects.requireNonNull(compiler);
        json.enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
    }

    Accepted validate(BackendModelInput input, byte[] response) {
        if (response == null || response.length > MAX_RESPONSE_BYTES) throw new Rejected(Reason.INVALID_RESPONSE);
        JsonNode root;
        try { root = strictJson(json, response); }
        catch (Exception ex) { throw new Rejected(Reason.INVALID_RESPONSE); }
        if (root == null || !root.isObject() || root.size() != 1 || !root.path("files").isArray())
            throw new Rejected(Reason.INVALID_RESPONSE);
        JsonNode files = root.path("files");
        Set<String> seen = new HashSet<>();
        byte[] selected = null;
        for (JsonNode file : files) {
            if (!file.isObject() || file.size() != 2 || !file.path("path").isTextual()
                    || !file.path("content").isTextual()) throw new Rejected(Reason.INVALID_RESPONSE);
            String path = file.path("path").asText();
            try { GenerationArtifactStore.safeRelative(path); }
            catch (RuntimeException ex) { throw new Rejected(Reason.UNSAFE_PATH); }
            if (!seen.add(path.toLowerCase(java.util.Locale.ROOT))) throw new Rejected(Reason.DUPLICATE_PATH);
            var owner = input.plan().entries().stream().filter(entry -> entry.path().equals(path))
                    .map(GenerationFilePlan.Entry::owner).findFirst().orElse(null);
            if (owner == null) throw new Rejected(Reason.EXTRA_PATH);
            if (owner != GenerationFilePlan.Owner.MODEL) throw new Rejected(Reason.WRONG_OWNER);
            if (!path.equals(input.writablePath())) throw new Rejected(Reason.EXTRA_PATH);
            selected = file.path("content").asText().getBytes(StandardCharsets.UTF_8);
        }
        if (selected == null) throw new Rejected(Reason.MISSING_PATH);
        if (selected.length == 0 || selected.length > MAX_SOURCE_BYTES)
            throw new Rejected(Reason.INVALID_JAVA_STRUCTURE);
        requireStructure(input, new String(selected, StandardCharsets.UTF_8));
        try {
            if (!compiler.compile(input, selected)) throw new Rejected(Reason.COMPILE_FAILED);
        } catch (RuntimeException ex) {
            if (ex instanceof BackendCompileClasspath.Failed) throw ex;
            throw new Rejected(Reason.COMPILE_FAILED);
        }
        return new Accepted(input.writablePath(), selected);
    }

    static JsonNode strictJson(ObjectMapper mapper, byte[] bytes) throws IOException {
        try (JsonParser parser = mapper.createParser(bytes)) {
            parser.enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
            JsonNode root = mapper.readTree(parser);
            if (parser.nextToken() != null) throw new IOException("Trailing JSON content");
            return root;
        }
    }

    private static void requireStructure(BackendModelInput input, String source) {
        var javac = ToolProvider.getSystemJavaCompiler();
        if (javac == null) throw new Rejected(Reason.INVALID_JAVA_STRUCTURE);
        var diagnostics = new DiagnosticCollector<JavaFileObject>();
        JavaFileObject file = new SimpleJavaFileObject(URI.create("string:///ModelServiceImpl.java"),
                JavaFileObject.Kind.SOURCE) {
            @Override public CharSequence getCharContent(boolean ignoreEncodingErrors) { return source; }
        };
        try {
            JavacTask task = (JavacTask) javac.getTask(null, null, diagnostics,
                    java.util.List.of("-proc:none"), null, java.util.List.of(file));
            CompilationUnitTree unit = task.parse().iterator().next();
            if (diagnostics.getDiagnostics().stream().anyMatch(item ->
                    item.getKind() == javax.tools.Diagnostic.Kind.ERROR)
                    || unit.getPackageName() == null
                    || !unit.getPackageName().toString().equals(input.descriptor().basePackage() + ".service")
                    || unit.getTypeDecls().size() != 1
                    || !(unit.getTypeDecls().getFirst() instanceof ClassTree type)
                    || type.getKind() != com.sun.source.tree.Tree.Kind.CLASS
                    || !type.getModifiers().getFlags().contains(Modifier.PUBLIC)
                    || !type.getSimpleName().contentEquals(input.descriptor().entityName() + "ServiceImpl")
                    || type.getImplementsClause().size() != 1
                    || !type.getImplementsClause().getFirst().toString()
                            .equals(input.descriptor().contractServiceName()))
                throw new Rejected(Reason.INVALID_JAVA_STRUCTURE);
        } catch (IOException | RuntimeException ex) {
            if (ex instanceof Rejected rejected) throw rejected;
            throw new Rejected(Reason.INVALID_JAVA_STRUCTURE);
        }
    }
}
