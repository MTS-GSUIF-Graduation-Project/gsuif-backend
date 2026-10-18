package eg.mts.gsuif.generation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.LongSupplier;
import java.util.regex.Pattern;

/** Internal backend-only model call. It does not publish, assemble, or persist results. */
final class OpenRouterBackendModelAdapter {
    enum Failure { CONFIG, TRANSPORT, HTTP_REJECTED, RESPONSE_TOO_LARGE, RETRY_EXHAUSTED, DEADLINE,
        INVALID_PROVIDER_RESPONSE, INVALID_MODEL_OUTPUT, COMPILATION, DEPENDENCY_CONFIG,
        DEPENDENCY_TIMEOUT, DEPENDENCY_UNAVAILABLE, DEPENDENCY_INTEGRITY }
    static final class Failed extends RuntimeException {
        private final Failure reason;
        private final BackendModelOutputValidator.Reason validatorReason;
        Failed(Failure reason) { this(reason, null); }
        Failed(Failure reason, BackendModelOutputValidator.Reason validatorReason) {
            super(reason.name());
            this.reason = reason;
            this.validatorReason = validatorReason;
        }
        Failure reason() { return reason; }
        BackendModelOutputValidator.Reason validatorReason() { return validatorReason; }
    }
    record Reply(int status, byte[] body) {
        Reply { body = body.clone(); }
        @Override public byte[] body() { return body.clone(); }
    }
    @FunctionalInterface interface Transport {
        Reply send(byte[] payload, String key, Duration timeout) throws IOException, InterruptedException;
    }
    @FunctionalInterface interface Sleeper { void sleep(Duration duration) throws InterruptedException; }
    @FunctionalInterface interface IsolatedCompiler {
        boolean compile(BackendModelInput input, byte[] source, Duration remaining);
        default BackendCompileClasspath manifest() { return null; }
    }
    @FunctionalInterface interface CompilerFactory {
        IsolatedCompiler prepare(BackendModelInput input);
    }
    @FunctionalInterface interface ModelContentObserver {
        void received(byte[] content);
        default void normalized(Normalization code) { }
    }
    enum Normalization { CANONICAL_FILES_OBJECT, TOP_LEVEL_ARRAY_WRAPPED }
    private record NormalizedContent(byte[] bytes, Normalization code) { }

    static final String MODEL = "qwen/qwen3-coder";
    static final String PROVIDER_ID = "openrouter";
    static final String PROVIDER_API_VERSION = "api-v1";
    private static final int MAX_TOKENS = 4096;
    private static final int MAX_RESPONSE_BYTES = 65536;
    private static final long OVERALL_NANOS = Duration.ofSeconds(250).toNanos();
    private static final Duration RESPONSE_LIMIT = Duration.ofSeconds(120);
    private static final Duration RETRY_DELAY = Duration.ofSeconds(2);
    private final ObjectMapper json = new ObjectMapper();
    private final Transport transport;
    private final CompilerFactory compilerFactory;
    private final LongSupplier clock;
    private final Sleeper sleeper;
    private final ModelContentObserver contentObserver;

    OpenRouterBackendModelAdapter(Transport transport, IsolatedCompiler compiler,
            LongSupplier clock, Sleeper sleeper) {
        this(transport, input -> compiler, clock, sleeper);
    }

    OpenRouterBackendModelAdapter(Transport transport, CompilerFactory compilerFactory,
            LongSupplier clock, Sleeper sleeper) {
        this(transport, compilerFactory, clock, sleeper, ignored -> { });
    }

    OpenRouterBackendModelAdapter(Transport transport, CompilerFactory compilerFactory,
            LongSupplier clock, Sleeper sleeper, ModelContentObserver contentObserver) {
        this.transport = java.util.Objects.requireNonNull(transport);
        this.compilerFactory = java.util.Objects.requireNonNull(compilerFactory);
        this.clock = java.util.Objects.requireNonNull(clock);
        this.sleeper = java.util.Objects.requireNonNull(sleeper);
        this.contentObserver = java.util.Objects.requireNonNull(contentObserver);
    }

    static OpenRouterBackendModelAdapter local(String pinnedImageId) {
        var docker = new DockerBackendJavaCompiler(pinnedImageId);
        var provisioner = BackendMavenDependencyProvisioner.local();
        return new OpenRouterBackendModelAdapter(new HttpsTransport(), input -> {
            BackendCompileClasspath dependencies = provisioner.provision(input);
            return new IsolatedCompiler() {
                @Override public boolean compile(BackendModelInput selected, byte[] source, Duration remaining) {
                    return docker.compile(selected, source, dependencies, remaining);
                }
                @Override public BackendCompileClasspath manifest() { return dependencies; }
            };
        },
                System::nanoTime, duration -> Thread.sleep(duration.toMillis()));
    }

    static Transport httpsTransport() { return new HttpsTransport(); }

    BackendModelOutputValidator.Accepted generate(BackendModelInput input, String key, String selectedModel) {
        if (input == null || key == null || key.isBlank() || !MODEL.equals(selectedModel))
            throw new Failed(Failure.CONFIG);
        IsolatedCompiler compiler;
        try { compiler = java.util.Objects.requireNonNull(compilerFactory.prepare(input)); }
        catch (BackendMavenDependencyProvisioner.Failed ex) {
            throw new Failed(switch (ex.reason()) {
                case CONFIGURATION -> Failure.DEPENDENCY_CONFIG;
                case TIMEOUT -> Failure.DEPENDENCY_TIMEOUT;
                case RESOLUTION, MISSING_ARTIFACT -> Failure.DEPENDENCY_UNAVAILABLE;
                case INVALID_ARTIFACT -> Failure.DEPENDENCY_INTEGRITY;
            });
        }
        BackendCompileClasspath manifest = compiler.manifest();
        if (manifest != null && !manifest.pomSha256().equals(BackendCompileClasspath.hash(input.pom())))
            throw new Failed(Failure.DEPENDENCY_INTEGRITY);
        long deadline = clock.getAsLong() + OVERALL_NANOS;
        byte[] payload = request(input, manifest);
        for (int attempt = 1; attempt <= 2; attempt++) {
            Duration timeout = remaining(deadline);
            if (timeout.compareTo(RESPONSE_LIMIT) > 0) timeout = RESPONSE_LIMIT;
            Reply reply;
            try { reply = transport.send(payload, key, timeout); }
            catch (ResponseTooLarge ex) { throw new Failed(Failure.RESPONSE_TOO_LARGE); }
            catch (IOException ex) { reply = null; }
            catch (InterruptedException ex) { Thread.currentThread().interrupt(); throw new Failed(Failure.TRANSPORT); }
            if (reply != null && reply.body().length > MAX_RESPONSE_BYTES)
                throw new Failed(Failure.RESPONSE_TOO_LARGE);
            if (reply != null && reply.status() == 200) {
                remaining(deadline);
                byte[] content = modelContent(reply.body());
                contentObserver.received(content.clone());
                NormalizedContent normalized = normalizeModelContent(content);
                contentObserver.normalized(normalized.code());
                var validator = new BackendModelOutputValidator((selected, source) ->
                        compiler.compile(selected, source, remaining(deadline)));
                try {
                    var accepted = validator.validate(input, normalized.bytes());
                    remaining(deadline);
                    return accepted;
                }
                catch (BackendModelOutputValidator.Rejected ex) {
                    throw new Failed(ex.reason() == BackendModelOutputValidator.Reason.COMPILE_FAILED
                            ? Failure.COMPILATION : Failure.INVALID_MODEL_OUTPUT, ex.reason());
                }
                catch (BackendCompileClasspath.Failed ex) { throw new Failed(Failure.DEPENDENCY_INTEGRITY); }
            }
            boolean transientFailure = reply == null || reply.status() == 429 || reply.status() >= 500 && reply.status() <= 599;
            if (!transientFailure) throw new Failed(Failure.HTTP_REJECTED);
            if (attempt == 2) throw new Failed(Failure.RETRY_EXHAUSTED);
            if (remaining(deadline).compareTo(RETRY_DELAY) <= 0) throw new Failed(Failure.DEADLINE);
            try { sleeper.sleep(RETRY_DELAY); }
            catch (InterruptedException ex) { Thread.currentThread().interrupt(); throw new Failed(Failure.TRANSPORT); }
        }
        throw new Failed(Failure.RETRY_EXHAUSTED);
    }

    private Duration remaining(long deadline) {
        long nanos = deadline - clock.getAsLong();
        if (nanos <= 0) throw new Failed(Failure.DEADLINE);
        return Duration.ofNanos(nanos);
    }

    byte[] request(BackendModelInput input) { return request(input, null); }

    byte[] request(BackendModelInput input, BackendCompileClasspath manifest) {
        ObjectNode root = json.createObjectNode();
        root.put("model", MODEL);
        root.put("max_tokens", MAX_TOKENS);
        root.put("temperature", 0);
        root.put("stream", false);
        root.putObject("provider").put("allow_fallbacks", false);
        ArrayNode messages = root.putArray("messages");
        ObjectNode message = messages.addObject();
        message.put("role", "user");
        ObjectNode instructions = json.createObjectNode();
        instructions.put("outputPath", input.writablePath());
        instructions.put("format", "Return one JSON object with only files: [{path, content}]. No other paths, prose, or fences.");
        instructions.put("rules", "Implement only the selected service interface and explicit CRUD mapping. Do not add architecture, business rules, permissions, dependencies, or endpoints.");
        instructions.put("contractId", input.descriptor().contractId());
        instructions.put("contractVersion", input.descriptor().contractVersion());
        instructions.put("contractSha256", input.descriptor().contractResourceSha256());
        instructions.put("contract", new String(input.contract(), StandardCharsets.UTF_8));
        ObjectNode framework = instructions.putObject("pinnedFramework");
        framework.put("java", pomValue(input.pom(), "<java.version>([0-9]+)</java.version>"));
        framework.put("springBootParent", pomValue(input.pom(),
                "(?s)<parent>\\s*<groupId>org\\.springframework\\.boot</groupId>\\s*"
                + "<artifactId>spring-boot-starter-parent</artifactId>\\s*<version>([0-9A-Za-z.\\-]+)</version>"));
        String starter = pomValue(input.pom(),
                "<groupId>org\\.springframework\\.boot</groupId>\\s*"
                + "<artifactId>spring-boot-starter-data-jpa</artifactId>\\s*<version>([0-9A-Za-z.\\-]+)</version>");
        framework.put("springBootDataJpaStarter", starter);
        String dataJpa = "pinned Spring Data JPA";
        if (manifest != null) {
            if (!manifest.pomSha256().equals(BackendCompileClasspath.hash(input.pom()))
                    || !starter.equals(manifestVersion(manifest, "org.springframework.boot", "spring-boot-starter-data-jpa")))
                throw new Failed(Failure.DEPENDENCY_INTEGRITY);
            dataJpa = manifestVersion(manifest, "org.springframework.data", "spring-data-jpa");
            framework.put("springDataJpa", dataJpa);
            framework.put("springDataCommons", manifestVersion(manifest,
                    "org.springframework.data", "spring-data-commons"));
            framework.put("hibernateCore", manifestVersion(manifest,
                    "org.hibernate.orm", "hibernate-core"));
        }
        instructions.put("springDataCompatibility", "With " + dataJpa
                + ", Specification.where(null) is ambiguous between Specification and PredicateSpecification "
                + "overloads. For an unrestricted filter, declare an explicitly typed Specification<Entity> "
                + "lambda: (root, query, criteriaBuilder) -> criteriaBuilder.conjunction(). "
                + "Compose additional filters from that typed value; do not use Specification.where(null).");
        ArrayNode definitions = instructions.putArray("definitions");
        for (RenderedModelHandoff.Definition definition : input.handoff().definitions()) {
            ObjectNode item = definitions.addObject();
            item.put("role", definition.role()); item.put("path", definition.path());
            item.put("sha256", definition.sha256());
            item.put("content", new String(definition.bytes(), StandardCharsets.UTF_8));
        }
        ArrayNode operations = instructions.putArray("operations");
        input.descriptor().operations().forEach(operation -> {
            ObjectNode item = operations.addObject();
            item.put("action", operation.action().name());
            item.put("operationId", operation.operationId());
            item.put("method", operation.method());
            item.put("path", operation.path());
            if (operation.action() == PackagedContractRegistry.CrudAction.LIST && operation.ordering() != null) {
                ObjectNode pagination = item.putObject("pagination");
                pagination.put("orderField", operation.ordering().field());
                pagination.put("orderDirection", operation.ordering().direction());
                pagination.put("semantics", "Apply filters before pagination; totalElements counts all matching records. "
                        + "Return empty data with accurate totals for out-of-range pages. "
                        + "Use stable ascending entity-ID ordering for an unchanged dataset.");
                for (String name : List.of("page", "size")) {
                    var parameter = operation.queryFields().get(name);
                    if (parameter == null) throw new Failed(Failure.CONFIG);
                    ObjectNode selected = pagination.putObject(name);
                    selected.put("default", parameter.defaultValue());
                    selected.put("minimum", parameter.minimum());
                    if (parameter.maximum() != null) selected.put("maximum", parameter.maximum());
                }
            }
        });
        ArrayNode fields = instructions.putArray("entityFields");
        input.descriptor().fields().forEach(field -> {
            ObjectNode item = fields.addObject();
            item.put("name", field.name()); item.put("javaType", field.javaType());
            item.put("nullable", field.nullable());
            if (field.enumType() != null) item.put("enumType", field.enumType());
        });
        ObjectNode createRules = instructions.putObject("createRules");
        input.descriptor().createRules().forEach((name, rule) -> createRules.putObject(name)
                .put("required", rule.required()).put("nullable", rule.nullable()));
        ObjectNode updateRules = instructions.putObject("fullUpdateRules");
        input.descriptor().updateRules().forEach((name, rule) -> updateRules.putObject(name)
                .put("required", rule.required()).put("nullable", rule.nullable()));
        ArrayNode dependencies = instructions.putArray("allowedDependencyGroups");
        input.handoff().specification().allowedDependencyGroups().stream().sorted()
                .forEach(dependencies::add);
        message.put("content", instructions.toString());
        try { return json.writeValueAsBytes(root); }
        catch (IOException ex) { throw new Failed(Failure.CONFIG); }
    }

    private static String pomValue(byte[] pom, String expression) {
        var matcher = Pattern.compile(expression).matcher(new String(pom, StandardCharsets.UTF_8));
        if (!matcher.find()) throw new Failed(Failure.CONFIG);
        return matcher.group(1);
    }

    private static String manifestVersion(BackendCompileClasspath manifest, String groupId, String artifactId) {
        var versions = manifest.artifacts().stream()
                .filter(artifact -> groupId.equals(artifact.groupId()) && artifactId.equals(artifact.artifactId()))
                .map(BackendCompileClasspath.Artifact::version).distinct().toList();
        if (versions.size() != 1) throw new Failed(Failure.DEPENDENCY_INTEGRITY);
        return versions.getFirst();
    }

    private byte[] modelContent(byte[] response) {
        if (response == null || response.length > MAX_RESPONSE_BYTES) throw new Failed(Failure.INVALID_PROVIDER_RESPONSE);
        try {
            JsonNode root = BackendModelOutputValidator.strictJson(json, response);
            if (!MODEL.equals(root.path("model").asText())
                    || !"stop".equals(root.path("choices").path(0).path("finish_reason").asText())
                    || !root.path("choices").path(0).path("message").path("content").isTextual()
                    || root.path("usage").path("completion_tokens").asInt(MAX_TOKENS + 1) > MAX_TOKENS)
                throw new Failed(Failure.INVALID_PROVIDER_RESPONSE);
            return root.path("choices").path(0).path("message").path("content")
                    .asText().getBytes(StandardCharsets.UTF_8);
        } catch (IOException | NullPointerException ex) { throw new Failed(Failure.INVALID_PROVIDER_RESPONSE); }
    }

    private NormalizedContent normalizeModelContent(byte[] content) {
        JsonNode root;
        try { root = BackendModelOutputValidator.strictJson(json, content); }
        catch (IOException ex) {
            throw new Failed(Failure.INVALID_MODEL_OUTPUT, BackendModelOutputValidator.Reason.INVALID_RESPONSE);
        }
        if (root != null && root.isObject())
            return new NormalizedContent(content.clone(), Normalization.CANONICAL_FILES_OBJECT);
        if (root == null || !root.isArray() || content.length > MAX_RESPONSE_BYTES - 10)
            throw new Failed(Failure.INVALID_MODEL_OUTPUT, BackendModelOutputValidator.Reason.INVALID_RESPONSE);
        byte[] prefix = "{\"files\":".getBytes(StandardCharsets.US_ASCII);
        byte[] wrapped = new byte[prefix.length + content.length + 1];
        System.arraycopy(prefix, 0, wrapped, 0, prefix.length);
        System.arraycopy(content, 0, wrapped, prefix.length, content.length);
        wrapped[wrapped.length - 1] = '}';
        return new NormalizedContent(wrapped, Normalization.TOP_LEVEL_ARRAY_WRAPPED);
    }

    static final class ResponseTooLarge extends IOException {
        ResponseTooLarge() { super("Provider response exceeded byte limit"); }
    }

    /** Stops demand and cancels HTTP before buffering beyond the cap. */
    static final class BoundedBodySubscriber implements HttpResponse.BodySubscriber<byte[]> {
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private final CompletableFuture<byte[]> result = new CompletableFuture<>();
        private Flow.Subscription subscription;

        @Override public CompletionStage<byte[]> getBody() { return result; }
        @Override public void onSubscribe(Flow.Subscription value) {
            subscription = value;
            value.request(1);
        }
        @Override public void onNext(List<ByteBuffer> chunks) {
            if (result.isDone()) return;
            for (ByteBuffer chunk : chunks) {
                if (chunk.remaining() > MAX_RESPONSE_BYTES - bytes.size()) {
                    subscription.cancel();
                    result.completeExceptionally(new ResponseTooLarge());
                    return;
                }
                byte[] next = new byte[chunk.remaining()];
                chunk.get(next);
                bytes.writeBytes(next);
            }
            subscription.request(1);
        }
        @Override public void onError(Throwable error) { result.completeExceptionally(error); }
        @Override public void onComplete() { result.complete(bytes.toByteArray()); }
    }

    private static final class HttpsTransport implements Transport {
        private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
        @Override public Reply send(byte[] payload, String key, Duration timeout) throws IOException, InterruptedException {
            HttpRequest request = HttpRequest.newBuilder(URI.create("https://openrouter.ai/api/v1/chat/completions"))
                    .timeout(timeout).header("Authorization", "Bearer " + key)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofByteArray(payload)).build();
            var pending = client.sendAsync(request, info -> new BoundedBodySubscriber());
            try {
                var response = pending.get(timeout.toNanos(), TimeUnit.NANOSECONDS);
                return new Reply(response.statusCode(), response.body());
            } catch (TimeoutException ex) { pending.cancel(true); throw new IOException("Transport timeout"); }
            catch (ExecutionException ex) {
                pending.cancel(true);
                Throwable cause = ex.getCause();
                while (cause instanceof java.util.concurrent.CompletionException && cause.getCause() != null)
                    cause = cause.getCause();
                if (cause instanceof ResponseTooLarge) throw new ResponseTooLarge();
                throw new IOException("Transport failure");
            }
            catch (InterruptedException ex) { pending.cancel(true); throw ex; }
        }
    }
}
