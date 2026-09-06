package eg.mts.gsuif.aspect;

import eg.mts.gsuif.logging.SensitiveDataMasker;
import java.util.Arrays;
import java.util.stream.Collectors;
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

    @Around("@annotation(eg.mts.gsuif.aspect.Loggable)")
    public Object logExecution(ProceedingJoinPoint joinPoint) throws Throwable {
        String className = joinPoint.getSignature().getDeclaringType().getSimpleName();
        String methodName = joinPoint.getSignature().getName();
        Object[] args = joinPoint.getArgs();

        String maskedArgs = (args == null || args.length == 0)
                ? ""
                : Arrays.stream(args)
                        .map(arg -> SensitiveDataMasker.mask(String.valueOf(arg)))
                        .collect(Collectors.joining(", "));

        log.info("→ {}.{}({})", className, methodName, maskedArgs);

        long start = System.nanoTime();
        try {
            Object result = joinPoint.proceed();
            long durationMs = (System.nanoTime() - start) / 1_000_000;
            log.info("← {}.{} completed in {} ms", className, methodName, durationMs);
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
