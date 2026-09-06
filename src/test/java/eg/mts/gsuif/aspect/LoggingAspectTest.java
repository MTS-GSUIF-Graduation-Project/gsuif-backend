package eg.mts.gsuif.aspect;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.aop.aspectj.annotation.AspectJProxyFactory;

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
                .matches("← SampleService\\.execute completed in \\d+ ms");
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
}
