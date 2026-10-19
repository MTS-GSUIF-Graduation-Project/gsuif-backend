<#ftl strip_whitespace=true encoding="UTF-8">
package ${packageName}.aspect;

@org.aspectj.lang.annotation.Aspect
@org.springframework.stereotype.Component
public final class LoggingAspect {
    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(LoggingAspect.class);

    @org.aspectj.lang.annotation.Around("@annotation(${packageName}.aspect.Loggable)")
    public Object around(org.aspectj.lang.ProceedingJoinPoint point) throws Throwable {
        String identity = safeIdentity(point.getSignature().getDeclaringType().getSimpleName(),
                point.getSignature().getName());
        long start = System.nanoTime();
        LOG.info("method={} event=entry", identity);
        try {
            Object result = point.proceed();
            LOG.info("method={} event=exit outcome=success durationMs={}", identity, elapsed(start));
            return result;
        } catch (Throwable failure) {
            LOG.info("method={} event=exit outcome=failure durationMs={}", identity, elapsed(start));
            throw failure;
        }
    }

    private static long elapsed(long start) {
        return Math.max(0L, java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start));
    }

    private static String safeIdentity(String type, String method) {
        String value = type + "." + method;
        return value.length() <= 120 && value.matches("[A-Za-z_$][A-Za-z0-9_$.]*") ? value : "method";
    }
}
