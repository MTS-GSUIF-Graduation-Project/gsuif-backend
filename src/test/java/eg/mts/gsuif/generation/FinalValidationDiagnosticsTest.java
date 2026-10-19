package eg.mts.gsuif.generation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

class FinalValidationDiagnosticsTest {
    private final Logger logger = (Logger) LoggerFactory.getLogger(FinalValidationDiagnostics.class);
    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();

    @BeforeEach void attach() {
        appender.start();
        logger.addAppender(appender);
    }

    @AfterEach void detach() {
        logger.detachAppender(appender);
    }

    @Test void classifiesEveryPackageBuildFailureReason() {
        assertEquals(FinalValidationDiagnostics.Reason.CONFIGURATION, reasonOf(BackendFinalPackageValidator.Failure.CONFIG));
        assertEquals(FinalValidationDiagnostics.Reason.INTEGRITY, reasonOf(BackendFinalPackageValidator.Failure.INTEGRITY));
        assertEquals(FinalValidationDiagnostics.Reason.BUILD, reasonOf(BackendFinalPackageValidator.Failure.BUILD));
        assertEquals(FinalValidationDiagnostics.Reason.TIMEOUT, reasonOf(BackendFinalPackageValidator.Failure.TIMEOUT));
        assertEquals(FinalValidationDiagnostics.Reason.CLEANUP, reasonOf(BackendFinalPackageValidator.Failure.CLEANUP));
        assertEquals(FinalValidationDiagnostics.Reason.UNEXPECTED_VALIDATION,
                FinalValidationDiagnostics.ofBuild(new IllegalStateException("anything")));
    }

    @Test void logsRunIdAttemptIdReasonAndCauseTypesButNoMessages() {
        UUID runId = UUID.randomUUID();
        UUID attemptId = UUID.randomUUID();
        var failure = new IllegalStateException("C:\\secret\\path sk-or-v1-disposable class GeneratedSource {}",
                new UncheckedIOException(new java.io.IOException("raw maven output")));

        FinalValidationDiagnostics.report(runId, attemptId, FinalValidationDiagnostics.Reason.PERSISTENCE_STAGING, failure);

        List<ILoggingEvent> events = appender.list;
        assertEquals(1, events.size());
        ILoggingEvent event = events.get(0);
        assertEquals(Level.WARN, event.getLevel());
        String message = event.getFormattedMessage();
        assertTrue(message.contains("runId=" + runId), message);
        assertTrue(message.contains("attemptId=" + attemptId), message);
        assertTrue(message.contains("reason=PERSISTENCE_STAGING"), message);
        assertTrue(message.contains("causeTypes=IllegalStateException<-UncheckedIOException<-IOException"), message);
        for (String leaked : List.of("secret", "sk-or-", "GeneratedSource", "raw maven output"))
            assertFalse(message.contains(leaked), leaked);
        assertEquals(null, event.getThrowableProxy(), "no stack trace or exception message is attached");
    }

    @Test void marksAFailureThatCouldNotBeRecorded() {
        FinalValidationDiagnostics.report(null, UUID.randomUUID(), FinalValidationDiagnostics.Reason.BUILD,
                new BackendFinalPackageValidator.Failed(BackendFinalPackageValidator.Failure.BUILD));

        assertTrue(appender.list.get(0).getFormattedMessage().contains("runId=unrecorded"));
        assertTrue(appender.list.get(0).getFormattedMessage().contains("causeTypes=Failed"));
    }

    @Test void boundsTheCauseChain() {
        Throwable deep = new RuntimeException("1", new RuntimeException("2", new RuntimeException("3",
                new RuntimeException("4", new RuntimeException("5")))));

        assertEquals("RuntimeException<-RuntimeException<-RuntimeException<-RuntimeException",
                FinalValidationDiagnostics.causeTypes(deep));
    }

    private static FinalValidationDiagnostics.Reason reasonOf(BackendFinalPackageValidator.Failure failure) {
        return FinalValidationDiagnostics.ofBuild(new BackendFinalPackageValidator.Failed(failure));
    }
}
