package eg.mts.gsuif.generation;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import eg.mts.gsuif.generation.GenerationCapabilityPreflight.OutputMode;
import eg.mts.gsuif.generation.GenerationFilePlan.Entry;
import eg.mts.gsuif.generation.GenerationFilePlan.Owner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.aop.aspectj.annotation.AspectJProxyFactory;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.servlet.HandlerMapping;

import javax.tools.ToolProvider;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.*;

class CompleteBackendObservabilityTest {
    private final CompleteOutputTestFixture fixture = new CompleteOutputTestFixture();

    @Test void ownedBytesAreDeterministicAndMissingOrWrongOwnersFail() throws Exception {
        var descriptor = descriptor();
        var plan = GenerationFilePlan.forDescriptor(descriptor);
        var renderer = new CompleteBackendObservabilityRenderer();
        var files = renderer.render(descriptor, plan);
        assertEquals(7, files.size());
        String logback = new String(files.get("backend/src/main/resources/logback-spring.xml"), StandardCharsets.UTF_8);
        assertTrue(logback.contains("MaskingPatternLayout"));
        assertTrue(logback.contains("DefaultHandlerExceptionResolver\" level=\"ERROR"));
        assertTrue(logback.contains("%nopex"));
        assertTrue(files.keySet().stream().allMatch(path -> plan.entries().stream()
                .anyMatch(entry -> entry.path().equals(path) && entry.owner() == Owner.DETERMINISTIC)));
        files.forEach((path, bytes) -> assertArrayEquals(bytes, renderer.render(descriptor, plan).get(path)));
        String path = files.keySet().iterator().next();
        byte first = files.get(path)[0];
        files.get(path)[0] ^= 1;
        assertEquals(first, renderer.render(descriptor, plan).get(path)[0]);
        var wrong = new GenerationFilePlan(plan.entries().stream().map(entry -> entry.path().equals(path)
                ? new Entry(path, Owner.MODEL, entry.purpose()) : entry).toList());
        assertThrows(IllegalArgumentException.class, () -> renderer.render(descriptor, wrong));
        var missing = new GenerationFilePlan(plan.entries().stream().filter(entry -> !entry.path().equals(path)).toList());
        assertThrows(IllegalArgumentException.class, () -> renderer.render(descriptor, missing));
        assertThrows(UnsupportedOperationException.class, files::clear);
    }

    @Test void emittedLayoutMasksDisposableSecretsInFormattedOutput(@TempDir Path scratch) throws Exception {
        try (var loader = compiled(scratch)) {
            Class<?> layoutType = loader.loadClass("com.example.inventory.logging.MaskingPatternLayout");
            var layout = (ch.qos.logback.classic.PatternLayout) layoutType.getConstructor().newInstance();
            layout.setContext((ch.qos.logback.classic.LoggerContext) LoggerFactory.getILoggerFactory());
            layout.setPattern("%msg%n%nopex");
            layout.start();
            var event = new ch.qos.logback.classic.spi.LoggingEvent("fixture",
                    (Logger) LoggerFactory.getLogger("fixture"), ch.qos.logback.classic.Level.INFO,
                    "password=disposable-pass token=disposable-token Authorization: Bearer disposable-bearer "
                            + "{\"secret\":\"disposable-json\"}", null, null);
            String output = layout.doLayout(event);
            for (String secret : List.of("disposable-pass", "disposable-token", "disposable-bearer", "disposable-json"))
                assertFalse(output.contains(secret), output);
            assertTrue(output.contains("[REDACTED]"));
            String json = format(layout, "{\"password\":\"alpha beta, gamma \\\"quoted\\\"\",\"safe\":\"kept\"}");
            assertFalse(json.contains("alpha") || json.contains("beta") || json.contains("gamma")
                    || json.contains("quoted"), json);
            assertTrue(json.contains("\"safe\":\"kept\""), json);
            assertTrue(json.contains("\"password\":\"[REDACTED]\""), json);
            String quoted = format(layout, "'token'='left, middle; end' \"api_key\"=\"one,two; three\" other=visible");
            for (String secret : List.of("left", "middle", "end", "one", "two", "three"))
                assertFalse(quoted.contains(secret), quoted);
            assertTrue(quoted.contains("other=visible"), quoted);
            assertTrue(quoted.contains("'token'='[REDACTED]'"), quoted);
            assertTrue(quoted.contains("\"api_key\"=\"[REDACTED]\""), quoted);
            String basic = format(layout, "Authorization: Basic dXNlcjpwYXNz; other=visible");
            String bearer = format(layout, "Authorization: Bearer eyJhbGciOiJIUzI1NiJ9; other=visible");
            assertFalse(basic.contains("dXNlcjpwYXNz") || basic.contains("Basic"), basic);
            assertFalse(bearer.contains("eyJhbGciOiJIUzI1NiJ9") || bearer.contains("Bearer"), bearer);
            assertTrue(basic.contains("other=visible") && bearer.contains("other=visible"));
            assertFalse(format(layout, "Basic disposable-basic Bearer disposable-bearer").contains("disposable-basic"));
            var thrown = new ch.qos.logback.classic.spi.LoggingEvent("fixture",
                    (Logger) LoggerFactory.getLogger("fixture"), ch.qos.logback.classic.Level.ERROR,
                    "fixed failure", new IllegalStateException("unlabelled-hostile-secret"), null);
            assertFalse(layout.doLayout(thrown).contains("unlabelled-hostile-secret"));
        }
    }

    private static String format(ch.qos.logback.classic.PatternLayout layout, String message) {
        return layout.doLayout(new ch.qos.logback.classic.spi.LoggingEvent("fixture",
                (Logger) LoggerFactory.getLogger("fixture"), ch.qos.logback.classic.Level.INFO,
                message, null, null));
    }

    @Test void emittedControllerRunsAspectThroughSpringProxyWithoutLoggingObjects(@TempDir Path scratch) throws Exception {
        try (var loader = compiled(scratch)) {
            String root = "com.example.inventory";
            Class<?> service = loader.loadClass(root + ".service.StockUnitService");
            Class<?> controller = loader.loadClass(root + ".controller.StockUnitController");
            Class<?> aspect = loader.loadClass(root + ".aspect.LoggingAspect");
            var calls = new java.util.concurrent.atomic.AtomicInteger();
            Object serviceMock = Proxy.newProxyInstance(loader, new Class<?>[]{service}, (proxy, method, args) -> {
                calls.incrementAndGet();
                return null;
            });
            Object target = controller.getConstructor(service).newInstance(serviceMock);
            var factory = new AspectJProxyFactory(target);
            factory.setProxyTargetClass(true);
            factory.addAspect(aspect);
            Object proxied = factory.getProxy(loader);
            assertTrue(org.springframework.aop.support.AopUtils.isAopProxy(proxied));
            try (Captured logs = new Captured(root + ".aspect.LoggingAspect")) {
                var list = descriptor().operations().stream()
                        .filter(op -> op.action() == PackagedContractRegistry.CrudAction.LIST).findFirst().orElseThrow();
                var method = java.util.Arrays.stream(controller.getMethods())
                        .filter(candidate -> candidate.getName().equals(list.operationId())).findFirst().orElseThrow();
                Object[] args = new Object[method.getParameterCount()];
                method.invoke(proxied, args);
                assertEquals(1, calls.get());
                String output = logs.text();
                assertTrue(output.contains("event=entry"), output);
                assertTrue(output.contains("outcome=success durationMs="), output);
                assertFalse(output.contains("password") || output.contains("Bearer") || output.contains("token="));
            }
        }
    }

    @Test void correlationUsesOnlyRouteTemplateAndRestoresMdcOnSuccessAndFailure(@TempDir Path scratch) throws Exception {
        try (var loader = compiled(scratch)) {
            var type = loader.loadClass("com.example.inventory.filter.CorrelationIdFilter");
            var filter = (jakarta.servlet.Filter) type.getConstructor().newInstance();
            try (Captured logs = new Captured(type.getName())) {
                MDC.put("correlationId", "outer");
                try {
                    var request = new MockHttpServletRequest("GET", "/stock-units/hostile-password=secret?token=unsafe");
                    request.addHeader("X-Correlation-Id", "bad secret");
                    request.addHeader("Authorization", "Bearer disposable-token");
                    request.setAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE, "/stock-units/{id}");
                    var response = new MockHttpServletResponse();
                    filter.doFilter(request, response, (req, res) -> assertNotEquals("outer", MDC.get("correlationId")));
                    assertTrue(response.getHeader("X-Correlation-Id").matches("[a-f0-9-]{36}"));
                    assertEquals("outer", MDC.get("correlationId"));
                    var failing = new MockHttpServletRequest("POST", "/secret-value?password=unsafe");
                    failing.addHeader("X-Correlation-Id", "bounded_ID-1");
                    var failureResponse = new MockHttpServletResponse();
                    assertThrows(jakarta.servlet.ServletException.class, () -> filter.doFilter(failing, failureResponse,
                            (req, res) -> { throw new jakarta.servlet.ServletException("hostile token=unsafe"); }));
                    assertEquals("bounded_ID-1", failureResponse.getHeader("X-Correlation-Id"));
                    assertEquals("outer", MDC.get("correlationId"));
                    String output = logs.text();
                    assertTrue(output.contains("route=/stock-units/{id}"));
                    assertTrue(output.contains("route=unmatched"));
                    for (String secret : List.of("disposable-token", "secret-value", "password=secret", "token=unsafe"))
                        assertFalse(output.contains(secret), output);
                } finally {
                    MDC.remove("correlationId");
                }
                assertNull(MDC.get("correlationId"));
            }
        }
    }

    @Test void unexpectedFailureLogsBoundedFramesWithoutMessageOrCause(@TempDir Path scratch) throws Exception {
        try (var loader = compiled(scratch)) {
            Class<?> handler = loader.loadClass("com.example.inventory.exception.GlobalExceptionHandler");
            try (Captured logs = new Captured(handler.getName())) {
                Exception failure = new IllegalStateException("password=disposable-secret Authorization: Bearer disposable-token",
                        new IllegalArgumentException("cause-secret"));
                Object result = handler.getMethod("unexpected", Exception.class).invoke(handler.getConstructor().newInstance(), failure);
                assertEquals(500, ((org.springframework.http.ResponseEntity<?>) result).getStatusCode().value());
                String output = logs.text();
                assertTrue(output.contains("unexpected failure type=IllegalStateException"));
                assertTrue(output.contains("stack[0]"));
                for (String secret : List.of("disposable-secret", "disposable-token", "cause-secret", "Authorization"))
                    assertFalse(output.contains(secret), output);
                assertTrue(logs.events.size() <= 13);
            }
        }
    }

    private CompleteOutputDescriptor descriptor() throws Exception {
        return fixture.preflight().describe(fixture.version(fixture.snapshot("condition")),
                fixture.specification(), OutputMode.BACKEND);
    }

    private URLClassLoader compiled(Path scratch) throws Exception {
        var descriptor = descriptor();
        var plan = GenerationFilePlan.forDescriptor(descriptor);
        Map<String, byte[]> sources = new TreeMap<>(new CompleteBackendDefinitionRenderer().render(descriptor, plan).files());
        sources.putAll(new CompleteBackendApiRenderer().render(descriptor, plan));
        String errorResponsePath = "backend/src/main/java/"
                + descriptor.basePackage().replace('.', '/') + "/api/ErrorApiResponse.java";
        sources.put(errorResponsePath, java.util.Objects.requireNonNull(
                new CompleteBackendOpenApiRenderer().render(descriptor, plan).get(errorResponsePath)));
        sources.putAll(new CompleteBackendErrorRenderer().render(descriptor, plan));
        sources.putAll(new CompleteBackendObservabilityRenderer().render(descriptor, plan));
        Path classes = Files.createDirectories(scratch.resolve("classes"));
        List<String> paths = new ArrayList<>();
        for (var entry : sources.entrySet()) {
            if (!entry.getKey().endsWith(".java")) continue;
            Path file = scratch.resolve(entry.getKey());
            Files.createDirectories(file.getParent());
            Files.write(file, entry.getValue());
            paths.add(file.toString());
        }
        List<String> command = new ArrayList<>(List.of("-classpath", System.getProperty("java.class.path"),
                "-d", classes.toString()));
        command.addAll(paths);
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null, command.toArray(String[]::new)));
        return new URLClassLoader(new java.net.URL[]{classes.toUri().toURL()}, getClass().getClassLoader());
    }

    private static final class Captured implements AutoCloseable {
        private final Logger logger;
        private final ListAppender<ILoggingEvent> appender = new ListAppender<>();
        private final List<ILoggingEvent> events = appender.list;

        private Captured(String name) {
            logger = (Logger) LoggerFactory.getLogger(name);
            appender.start();
            logger.addAppender(appender);
        }

        String text() {
            return events.stream().map(ILoggingEvent::getFormattedMessage).reduce("", (a, b) -> a + "\n" + b);
        }

        @Override public void close() { logger.detachAppender(appender); appender.stop(); }
    }
}
