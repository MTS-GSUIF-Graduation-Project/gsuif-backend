package eg.mts.gsuif.aspect;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import eg.mts.gsuif.dto.CreateMetadataVersionRequest;
import eg.mts.gsuif.dto.MetadataVersionDto;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.aop.aspectj.annotation.AspectJProxyFactory;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LoggingAspectTest {

    private ListAppender<ILoggingEvent> listAppender;
    private Logger aspectLogger;
    private SampleService proxy;

    static class SampleService {
        @Loggable
        public String execute(String input) {
            return "result:" + input;
        }

        @Loggable
        public String failingMethod(String message) {
            throw new IllegalArgumentException("Failed with password: " + message);
        }

        @Loggable
        public void noArgs() {
        }

        @Loggable
        public String processObject(Object obj) {
            return "processed";
        }

        @Loggable
        public MetadataVersionDto processMetadata(CreateMetadataVersionRequest request) {
            return new MetadataVersionDto(UUID.fromString("00000000-0000-0000-0000-000000000001"),
                    UUID.fromString("00000000-0000-0000-0000-000000000002"), null, 1,
                    request.schemaVersion(), true, request.snapshot(), null, null, null, null);
        }
    }

    static class BrokenToStringObject {
        @Override
        public String toString() {
            throw new RuntimeException("Broken toString");
        }
    }

    @BeforeEach
    void setUp() {
        aspectLogger = (Logger) LoggerFactory.getLogger(LoggingAspect.class);
        listAppender = new ListAppender<>();
        listAppender.start();
        aspectLogger.addAppender(listAppender);

        LoggingAspect aspect = new LoggingAspect();
        AspectJProxyFactory factory = new AspectJProxyFactory(new SampleService());
        factory.addAspect(aspect);
        proxy = factory.getProxy();
    }

    @AfterEach
    void tearDown() {
        aspectLogger.detachAppender(listAppender);
        listAppender.stop();
    }

    @Test
    void execute_logsEntryAndExitAtInfo() {
        String result = proxy.execute("test-input");

        assertThat(result).isEqualTo("result:test-input");
        assertThat(listAppender.list).hasSize(2);

        ILoggingEvent entryEvent = listAppender.list.get(0);
        assertThat(entryEvent.getLevel()).isEqualTo(Level.INFO);
        assertThat(entryEvent.getFormattedMessage())
                .contains("→ SampleService.execute(test-input)");

        ILoggingEvent exitEvent = listAppender.list.get(1);
        assertThat(exitEvent.getLevel()).isEqualTo(Level.INFO);
        assertThat(exitEvent.getFormattedMessage())
                .matches("← SampleService\\.execute completed in \\d+ ms; result=result:test-input");
    }

    @Test
    void execute_masksSensitiveArguments() {
        proxy.execute("password=mySecretPassword123");

        ILoggingEvent entryEvent = listAppender.list.get(0);
        assertThat(entryEvent.getFormattedMessage())
                .contains("***MASKED***")
                .doesNotContain("mySecretPassword123");
    }

    @Test
    void execute_masksJsonQuotesArguments() {
        proxy.execute("{\"token\": \"jwt-token-xyz\"}");

        ILoggingEvent entryEvent = listAppender.list.get(0);
        assertThat(entryEvent.getFormattedMessage())
                .contains("***MASKED***")
                .doesNotContain("jwt-token-xyz");
    }

    @Test
    void failingMethod_logsErrorAndRethrows() {
        assertThatThrownBy(() -> proxy.failingMethod("secretPass"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Failed with password: secretPass");

        assertThat(listAppender.list).hasSize(2);

        ILoggingEvent errorEvent = listAppender.list.get(1);
        assertThat(errorEvent.getLevel()).isEqualTo(Level.ERROR);
        assertThat(errorEvent.getFormattedMessage())
                .contains("✗ SampleService.failingMethod threw IllegalArgumentException after")
                .contains("***MASKED***")
                .doesNotContain("secretPass");
    }

    @Test
    void noArgs_logsEntryWithEmptyArgs() {
        proxy.noArgs();

        assertThat(listAppender.list).hasSize(2);
        assertThat(listAppender.list.get(0).getFormattedMessage())
                .isEqualTo("→ SampleService.noArgs()");
    }

    @Test
    void processObject_whenToStringThrows_doesNotBreakInvocation() {
        BrokenToStringObject brokenObj = new BrokenToStringObject();
        String result = proxy.processObject(brokenObj);

        assertThat(result).isEqualTo("processed");
        assertThat(listAppender.list).hasSize(2);

        ILoggingEvent entryEvent = listAppender.list.get(0);
        assertThat(entryEvent.getLevel()).isEqualTo(Level.INFO);
        assertThat(entryEvent.getFormattedMessage())
                .contains("→ SampleService.processObject(BrokenToStringObject[contents=<omitted>])");
    }

    @Test
    void metadataSnapshot_isOmittedBeforeFormattingAndResultIsUnchanged() throws Exception {
        String snapshotJson = "{\"payload\":\"" + "x".repeat(5_000_000 - 14) + "\"}";
        assertThat(snapshotJson.getBytes(StandardCharsets.UTF_8)).hasSize(5_000_000);
        var snapshot = new ObjectMapper().readTree(snapshotJson);
        var request = new CreateMetadataVersionRequest("1.0.0", snapshot);

        MDC.put("correlationId", "logging-test-correlation");
        try {
            MetadataVersionDto result = proxy.processMetadata(request);

            assertThat(result.snapshot()).isSameAs(snapshot);
            assertThat(request.snapshot()).isSameAs(snapshot);
            assertThat(result.snapshot().path("payload").asText()).hasSize(5_000_000 - 14);
            assertThat(listAppender.list).hasSize(2);
            String entry = listAppender.list.get(0).getFormattedMessage();
            String exit = listAppender.list.get(1).getFormattedMessage();
            assertThat(entry).contains("SampleService.processMetadata", "schemaVersion=1.0.0",
                    "snapshot=<omitted>").hasSizeLessThan(512).doesNotContain("xxxx");
            assertThat(exit).contains("completed in", "MetadataVersionDto[id=", "version=1",
                    "snapshot=<omitted>").hasSizeLessThan(512).doesNotContain("xxxx");
            assertThat(listAppender.list.get(0).getMDCPropertyMap())
                    .containsEntry("correlationId", "logging-test-correlation");
        } finally {
            MDC.remove("correlationId");
        }
    }

    @Test
    void metadataSummary_masksSensitiveSmallFields() throws Exception {
        var request = new CreateMetadataVersionRequest("password=hidden-value",
                new ObjectMapper().readTree("{\"token\":\"snapshot-secret\"}"));

        proxy.processMetadata(request);

        assertThat(listAppender.list.get(0).getFormattedMessage())
                .contains("password=***MASKED***", "snapshot=<omitted>")
                .doesNotContain("hidden-value", "snapshot-secret");
        assertThat(listAppender.list.get(1).getFormattedMessage())
                .doesNotContain("hidden-value", "snapshot-secret");
    }
}
