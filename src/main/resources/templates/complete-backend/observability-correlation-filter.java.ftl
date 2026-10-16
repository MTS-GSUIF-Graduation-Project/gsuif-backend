<#ftl strip_whitespace=true encoding="UTF-8">
package ${packageName}.filter;

@org.springframework.stereotype.Component
@org.springframework.core.annotation.Order(-101)
public final class CorrelationIdFilter extends org.springframework.web.filter.OncePerRequestFilter {
    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(CorrelationIdFilter.class);
    private static final java.util.regex.Pattern ID = java.util.regex.Pattern.compile("[A-Za-z0-9_-]{1,64}");
    private static final java.util.regex.Pattern ROUTE = java.util.regex.Pattern.compile("/[A-Za-z0-9_{}./-]{0,159}");

    @Override
    protected void doFilterInternal(jakarta.servlet.http.HttpServletRequest request,
            jakarta.servlet.http.HttpServletResponse response, jakarta.servlet.FilterChain chain)
            throws jakarta.servlet.ServletException, java.io.IOException {
        String previous = org.slf4j.MDC.get("correlationId");
        String supplied = request.getHeader("X-Correlation-Id");
        String correlationId = supplied != null && ID.matcher(supplied).matches()
                ? supplied : java.util.UUID.randomUUID().toString();
        long start = System.nanoTime();
        boolean completed = false;
        org.slf4j.MDC.put("correlationId", correlationId);
        try {
            response.setHeader("X-Correlation-Id", correlationId);
            chain.doFilter(request, response);
            completed = true;
        } finally {
            try {
                Object matched = request.getAttribute(org.springframework.web.servlet.HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
                String route = matched instanceof String value && ROUTE.matcher(value).matches() ? value : "unmatched";
                String verb = request.getMethod();
                if (!java.util.Set.of("GET", "POST", "PUT", "PATCH", "DELETE", "HEAD", "OPTIONS").contains(verb))
                    verb = "OTHER";
                long duration = Math.max(0L, java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start));
                LOG.info("http method={} route={} status={} outcome={} durationMs={}",
                        verb, route, response.getStatus(), completed ? "completed" : "failure", duration);
            } finally {
                if (previous == null) org.slf4j.MDC.remove("correlationId");
                else org.slf4j.MDC.put("correlationId", previous);
            }
        }
    }
}
