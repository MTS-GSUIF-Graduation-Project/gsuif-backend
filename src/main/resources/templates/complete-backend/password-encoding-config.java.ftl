<#ftl strip_whitespace=true encoding="UTF-8">
package ${packageName}.security;

@org.springframework.context.annotation.Configuration(proxyBeanMethods = false)
public class PasswordEncodingConfig {
    @org.springframework.context.annotation.Bean
    public org.springframework.security.crypto.password.PasswordEncoder passwordEncoder() {
        return new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder();
    }
}
