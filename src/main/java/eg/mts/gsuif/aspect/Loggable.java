package eg.mts.gsuif.aspect;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a Spring bean method for AOP-driven logging.
 *
 * <p>When present, {@link LoggingAspect} logs:
 * <ul>
 *   <li>Method entry — name and masked arguments (INFO)
 *   <li>Method exit — name and execution time in ms (INFO)
 *   <li>Exception — name, masked exception message, and stack trace (ERROR)
 * </ul>
 *
 * <p>Sensitive values (passwords, tokens, Authorization/Bearer headers) are
 * masked by {@link eg.mts.gsuif.logging.SensitiveDataMasker} before any
 * log write. STD-15, STD-16.
 *
 * <p>Usage:
 * <pre>{@code
 * @Loggable
 * public WorkOrder createWorkOrder(CreateWorkOrderRequest request) { ... }
 * }</pre>
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface Loggable {
}
