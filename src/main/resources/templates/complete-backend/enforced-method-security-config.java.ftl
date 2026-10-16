<#ftl strip_whitespace=true encoding="UTF-8">
package ${packageName}.security;

@org.springframework.context.annotation.Configuration(proxyBeanMethods = false)
@org.springframework.context.annotation.Profile({"test", "prod"})
@org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity
public class EnforcedMethodSecurityConfig { }
