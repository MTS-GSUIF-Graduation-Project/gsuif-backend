package eg.mts.gsuif.logging;

import ch.qos.logback.classic.PatternLayout;
import ch.qos.logback.classic.spi.ILoggingEvent;

/**
 * PatternLayout that applies {@link SensitiveDataMasker} to every fully
 * formatted log line before it reaches any appender.
 *
 * <p>{@code doLayout(ILoggingEvent)} returns the complete formatted string —
 * timestamp, level, correlation ID (from MDC), logger name, message, and
 * any exception stack trace. Masking therefore covers all of these in one
 * pass, including stack traces that may contain sensitive values.
 *
 * <p>This is Layer 2 of STD-16 masking. Layer 1 is the per-argument masking
 * in {@code LoggingAspect}. This layer catches any log statement that
 * originates outside {@code @Loggable}-annotated methods.
 *
 * <p>Registered in {@code logback-spring.xml} via {@code LayoutWrappingEncoder}:
 * <pre>{@code
 * <encoder class="ch.qos.logback.core.encoder.LayoutWrappingEncoder">
 *     <layout class="eg.mts.gsuif.logging.MaskingPatternLayout">
 *         <pattern>%d %-5level [%X{correlationId:-NO_CORR_ID}] %logger{36} - %msg%n</pattern>
 *     </layout>
 *     <charset>UTF-8</charset>
 * </encoder>
 * }</pre>
 */
public class MaskingPatternLayout extends PatternLayout {

    /**
     * Returns the fully formatted log line with all sensitive values masked.
     *
     * <p>Delegates formatting to {@code super.doLayout(event)} — which handles
     * param substitution, MDC lookup, and exception rendering — then passes the
     * result through {@link SensitiveDataMasker#mask(String)}.
     */
    @Override
    public String doLayout(ILoggingEvent event) {
        return SensitiveDataMasker.mask(super.doLayout(event));
    }
}
