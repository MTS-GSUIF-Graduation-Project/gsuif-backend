package eg.mts.gsuif.config;

import eg.mts.gsuif.filter.CorrelationIdFilter;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Servlet filter configuration.
 *
 * <p>Registers {@link CorrelationIdFilter} with order {@code -101} so that
 * request tracing begins before Spring Security (-100) and any security
 * audit or exception events carry the correlation ID. STD-17.
 */
@Configuration
public class FilterConfig {

    @Bean
    public FilterRegistrationBean<CorrelationIdFilter> correlationIdFilterRegistration() {
        FilterRegistrationBean<CorrelationIdFilter> registration = new FilterRegistrationBean<>();
        registration.setFilter(new CorrelationIdFilter());
        registration.addUrlPatterns("/*");
        registration.setOrder(-101);
        return registration;
    }
}
