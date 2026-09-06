package eg.mts.gsuif.filter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Filter that establishes distributed request tracing via a correlation ID.
 *
 * <p>Reads {@code X-Correlation-Id} from the incoming request, or generates a new
 * random UUID if missing or blank. Binds the ID to SLF4J MDC under {@code correlationId}
 * for the duration of the request, adds it to the HTTP response header, and clears
 * MDC in a {@code finally} block.
 *
 * <p>Registered via {@code FilterConfig} at order {@code -101} so it executes
 * before Spring Security's filter chain. STD-17.
 */
public class CorrelationIdFilter extends OncePerRequestFilter {

    public static final String CORRELATION_ID_HEADER = "X-Correlation-Id";
    public static final String MDC_KEY = "correlationId";

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {

        String correlationId = request.getHeader(CORRELATION_ID_HEADER);
        if (correlationId == null || correlationId.isBlank()) {
            correlationId = UUID.randomUUID().toString();
        }

        MDC.put(MDC_KEY, correlationId);
        response.setHeader(CORRELATION_ID_HEADER, correlationId);

        try {
            filterChain.doFilter(request, response);
        } finally {
            MDC.remove(MDC_KEY);
        }
    }
}
