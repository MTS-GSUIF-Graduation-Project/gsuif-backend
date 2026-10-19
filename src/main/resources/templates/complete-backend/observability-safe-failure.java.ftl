<#ftl strip_whitespace=true encoding="UTF-8">
package ${packageName}.logging;

public final class SafeFailureLogger {
    private SafeFailureLogger() { }

    public static void logHandled(Class<?> owner, Throwable failure) {
        log(owner, failure, "handled");
    }

    public static void logUnexpected(Class<?> owner, Throwable failure) {
        log(owner, failure, "unexpected");
    }

    private static void log(Class<?> owner, Throwable failure, String category) {
        org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(owner);
        log.error("{} failure type={}", category, safeType(failure.getClass().getSimpleName()));
        StackTraceElement[] frames = failure.getStackTrace();
        int limit = Math.min(frames.length, 12);
        for (int i = 0; i < limit; i++) {
            StackTraceElement frame = frames[i];
            log.error("stack[{}] class={} method={} line={}", i,
                    safeType(frame.getClassName()), safeType(frame.getMethodName()),
                    Math.max(-1, frame.getLineNumber()));
        }
    }

    private static String safeType(String value) {
        return value != null && value.length() <= 160 && value.matches("[A-Za-z0-9_.$-]+") ? value : "unknown";
    }
}
