<#ftl strip_whitespace=true encoding="UTF-8">
package ${packageName}.audit;

@org.springframework.context.annotation.Configuration(proxyBeanMethods = false)
@org.springframework.data.jpa.repository.config.EnableJpaAuditing(auditorAwareRef = "generatedAppAuditor")
public class AuditActorConfig {
    @org.springframework.context.annotation.Bean("generatedAppAuditor")
    org.springframework.data.domain.AuditorAware<String> generatedAppAuditor(
            ${packageName}.security.SecurityMode mode, org.springframework.core.env.Environment environment) {
        return new GeneratedAppAuditorAware(mode, environment);
    }
}
