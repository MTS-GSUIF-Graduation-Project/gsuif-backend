<#ftl strip_whitespace=true encoding="UTF-8">
package ${packageName}.security;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

@Component
public final class SecurityMode {
    private final boolean dev;

    public SecurityMode(Environment environment) {
        Set<String> profiles = Arrays.stream(environment.getActiveProfiles().length == 0
                ? environment.getDefaultProfiles() : environment.getActiveProfiles())
                .collect(Collectors.toSet());
        Set<String> securityProfiles = profiles.stream()
                .filter(Set.of("dev", "test", "prod")::contains).collect(Collectors.toSet());
        if (securityProfiles.size() != 1)
            throw new IllegalStateException("Select exactly one generated-app security profile");
        if (environment.containsProperty("GENERATED_APP_SECURITY_MODE"))
            throw new IllegalStateException("Security mode is selected only by Spring profile");
        dev = securityProfiles.contains("dev");
        if (dev && !"127.0.0.1".equals(environment.getProperty("server.address")))
            throw new IllegalStateException("Dev security requires loopback server.address");
    }

    public boolean isDev() { return dev; }
}
