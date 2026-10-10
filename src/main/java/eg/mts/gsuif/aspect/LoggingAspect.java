package eg.mts.gsuif.aspect;

import eg.mts.gsuif.dto.CreateMetadataVersionRequest;
import eg.mts.gsuif.dto.MetadataVersionDto;
import eg.mts.gsuif.dto.PagedBody;
import eg.mts.gsuif.logging.SensitiveDataMasker;
import java.lang.reflect.Array;
import java.util.Collection;
import java.util.Map;
import java.util.UUID;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Aspect that intercepts methods annotated with {@link Loggable}.
 *
 * <p>Logs method entry with masked arguments (INFO), method exit with elapsed time
 * in milliseconds (INFO), and any thrown exception with elapsed time and masked message (ERROR).
 *
 * <p>Adheres to STD-14, STD-15, and STD-16.
 */
@Aspect
@Component
public class LoggingAspect {

    private static final Logger log = LoggerFactory.getLogger(LoggingAspect.class);
    private static final int MAX_TEXT_LENGTH = 256;
    private static final int MAX_ARGUMENTS = 8;

    private String summarizeArguments(Object[] args) {
        if (args == null || args.length == 0) {
            return "";
        }
        StringBuilder summary = new StringBuilder();
        for (int i = 0; i < Math.min(args.length, MAX_ARGUMENTS); i++) {
            if (i > 0) {
                summary.append(", ");
            }
            summary.append(safeSummary(args[i]));
        }
        if (args.length > MAX_ARGUMENTS) {
            summary.append(", ... ").append(args.length - MAX_ARGUMENTS).append(" more arguments");
        }
        return SensitiveDataMasker.mask(summary.toString());
    }

    private String summarize(Object value) {
        if (value == null) {
            return "null";
        }
        if (value instanceof CreateMetadataVersionRequest request) {
            return "CreateMetadataVersionRequest[schemaVersion=" + summarizeText(request.schemaVersion())
                    + ", snapshot=<omitted>]";
        }
        if (value instanceof MetadataVersionDto version) {
            return "MetadataVersionDto[id=" + version.id() + ", pageId=" + version.pageId()
                    + ", version=" + version.version() + ", snapshot=<omitted>]";
        }
        if (value instanceof PagedBody<?> page) {
            return "PagedBody[number=" + page.number() + ", size=" + page.size()
                    + ", totalElements=" + page.totalElements() + ", data=<omitted>]";
        }
        if (value instanceof CharSequence text) {
            return summarizeText(text);
        }
        if (value instanceof UUID || value instanceof Boolean || value instanceof Byte
                || value instanceof Short || value instanceof Integer || value instanceof Long
                || value instanceof Float || value instanceof Double) {
            return String.valueOf(value);
        }
        if (value instanceof Enum<?> enumValue) {
            return enumValue.name();
        }
        if (value instanceof Collection<?> collection) {
            return value.getClass().getSimpleName() + "[size=" + collection.size() + ", contents=<omitted>]";
        }
        if (value instanceof Map<?, ?> map) {
            return value.getClass().getSimpleName() + "[size=" + map.size() + ", contents=<omitted>]";
        }
        if (value.getClass().isArray()) {
            return value.getClass().getComponentType().getSimpleName() + "[length="
                    + Array.getLength(value) + ", contents=<omitted>]";
        }
        // Never call an arbitrary toString(): records and JSON nodes can contain a 5 MB snapshot.
        return value.getClass().getSimpleName() + "[contents=<omitted>]";
    }

    private String safeSummary(Object value) {
        try {
            return summarize(value);
        } catch (Throwable ignored) {
            return value == null ? "null" : value.getClass().getSimpleName() + "[unprintable]";
        }
    }

    private String summarizeText(CharSequence text) {
        if (text == null) {
            return "null";
        }
        if (text.length() > MAX_TEXT_LENGTH) {
            return "String[length=" + text.length() + ", contents=<omitted>]";
        }
        return text.toString();
    }

    @Around("@annotation(eg.mts.gsuif.aspect.Loggable)")
    public Object logExecution(ProceedingJoinPoint joinPoint) throws Throwable {
        String className = joinPoint.getSignature().getDeclaringType().getSimpleName();
        String methodName = joinPoint.getSignature().getName();
        Object[] args = joinPoint.getArgs();

        String maskedArgs = summarizeArguments(args);

        log.info("→ {}.{}({})", className, methodName, maskedArgs);

        long start = System.nanoTime();
        try {
            Object result = joinPoint.proceed();
            long durationMs = (System.nanoTime() - start) / 1_000_000;
            log.info("← {}.{} completed in {} ms; result={}",
                    className, methodName, durationMs, SensitiveDataMasker.mask(safeSummary(result)));
            return result;
        } catch (Throwable ex) {
            long durationMs = (System.nanoTime() - start) / 1_000_000;
            log.error(
                    "✗ {}.{} threw {} after {} ms: {}",
                    className,
                    methodName,
                    ex.getClass().getSimpleName(),
                    durationMs,
                    SensitiveDataMasker.mask(ex.getMessage()),
                    ex);
            throw ex;
        }
    }
}
