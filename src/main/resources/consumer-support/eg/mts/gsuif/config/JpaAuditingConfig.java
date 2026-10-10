package eg.mts.gsuif.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;

/**
 * Enables Spring Data JPA Auditing.
 *
 * <p>Wires the {@code auditorAware} bean for populating {@code @CreatedBy}
 * and {@code @LastModifiedBy}. Adheres to STD-23.
 */
@Configuration
@EnableJpaAuditing(auditorAwareRef = "auditorAware")
public class JpaAuditingConfig {
}
