package eg.mts.gsuif.generation;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Operator diagnostics for FINAL_VALIDATION / RESULT_VALIDATION_FAILED. The persisted stage and
 * code stay unchanged for clients; the log adds a fixed reason and exception class names only,
 * never exception messages, generated source, build output, paths or credentials.
 */
final class FinalValidationDiagnostics {
    enum Reason {
        CONFIGURATION, BUILD, TIMEOUT, INTEGRITY, CLEANUP, UNEXPECTED_VALIDATION,
        PERSISTENCE_STAGING, PERSISTENCE_COMPLETION
    }

    private static final Logger log = LoggerFactory.getLogger(FinalValidationDiagnostics.class);
    private static final int MAX_CAUSES = 4;

    private FinalValidationDiagnostics() { }

    /** Classifies a failure of the offline package build. */
    static Reason ofBuild(Throwable failure) {
        if (failure instanceof BackendFinalPackageValidator.Failed failed) {
            return switch (failed.reason()) {
                case CONFIG -> Reason.CONFIGURATION;
                case INTEGRITY -> Reason.INTEGRITY;
                case BUILD -> Reason.BUILD;
                case TIMEOUT -> Reason.TIMEOUT;
                case CLEANUP -> Reason.CLEANUP;
            };
        }
        return Reason.UNEXPECTED_VALIDATION;
    }

    /** Simple class names of the failure and its causes, outermost first. */
    static String causeTypes(Throwable failure) {
        List<String> names = new ArrayList<>();
        for (Throwable current = failure; current != null && names.size() < MAX_CAUSES; current = current.getCause()) {
            names.add(current.getClass().getSimpleName());
            if (current.getCause() == current) break;
        }
        return names.isEmpty() ? "none" : String.join("<-", names);
    }

    static void report(UUID runId, UUID attemptId, Reason reason, Throwable failure) {
        log.warn("Backend generation failed at FINAL_VALIDATION: runId={} attemptId={} reason={} causeTypes={}",
                runId == null ? "unrecorded" : runId, attemptId, reason, causeTypes(failure));
    }
}
