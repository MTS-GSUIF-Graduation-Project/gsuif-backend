<#ftl strip_whitespace=true encoding="UTF-8">
package ${packageName}.bootstrap;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Map;
import org.springframework.boot.Banner;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

/** Explicit one-shot command. This package is excluded from normal application scanning. */
public final class FirstAdminBootstrap {
    private FirstAdminBootstrap() { }

    public static int run(String[] args) {
        return run(args, System.getenv(), () -> {
            java.io.Console console = System.console();
            return console == null ? null : console.readPassword("First admin password: ");
        });
    }

    @FunctionalInterface public interface PasswordReader { char[] read(); }

    /** Injectable only to test the command with disposable values and no real environment secrets. */
    public static int run(String[] args, Map<String, String> environment, PasswordReader reader) {
        if (args == null || args.length != 1 || !"bootstrap-admin".equals(args[0])) {
            System.err.println("Bootstrap command rejected");
            return 2;
        }
        String username = environment.get("GENERATED_APP_BOOTSTRAP_USERNAME");
        String dbUrl = environment.get("GENERATED_APP_DB_URL");
        String dbUser = environment.get("GENERATED_APP_DB_USER");
        String dbPassword = environment.get("GENERATED_APP_DB_PASSWORD");
        if (username == null || username.isBlank() || username.length() > 100
                || dbUrl == null || dbUrl.isBlank() || dbUser == null || dbUser.isBlank()
                || dbPassword == null || dbPassword.isBlank()) {
            System.err.println("Bootstrap input rejected");
            return 2;
        }
        String supplied = environment.get("GENERATED_APP_BOOTSTRAP_PASSWORD");
        char[] password;
        try {
            password = supplied == null ? reader.read() : supplied.toCharArray();
        } catch (RuntimeException failure) {
            System.err.println("Bootstrap input rejected");
            return 2;
        }
        if (password == null || password.length == 0 || new String(password).isBlank()
                || new String(password).getBytes(StandardCharsets.UTF_8).length > 72) {
            if (password != null) Arrays.fill(password, '\0');
            System.err.println("Bootstrap input rejected");
            return 2;
        }
        try {
            boolean created;
            try (var context = new SpringApplicationBuilder(BootstrapContext.class)
                    .web(WebApplicationType.NONE).bannerMode(Banner.Mode.OFF).logStartupInfo(false)
                    .properties("logging.level.root=OFF", "spring.main.web-application-type=none")
                    .initializers(application -> application.getEnvironment().getPropertySources().addFirst(
                            new org.springframework.core.env.MapPropertySource("generated-app-bootstrap-db",
                                    Map.ofEntries(
                                            Map.entry("spring.datasource.url", dbUrl),
                                            Map.entry("spring.datasource.username", dbUser),
                                            Map.entry("spring.datasource.password", dbPassword),
                                            Map.entry("spring.datasource.hikari.jdbc-url", dbUrl),
                                            Map.entry("spring.datasource.hikari.username", dbUser),
                                            Map.entry("spring.datasource.hikari.password", dbPassword),
                                            Map.entry("spring.flyway.url", dbUrl),
                                            Map.entry("spring.flyway.user", dbUser),
                                            Map.entry("spring.flyway.password", dbPassword),
                                            Map.entry("spring.jpa.hibernate.ddl-auto", "validate"),
                                            Map.entry("spring.jpa.open-in-view", "false"),
                                            Map.entry("spring.flyway.enabled", "true"),
                                            Map.entry("spring.flyway.clean-disabled", "true")))))
                    .run()) {
                created = context.getBean(FirstAdminBootstrapService.class)
                        .createIfNoAdmin(username, password);
            }
            if (!created) {
                System.err.println("Bootstrap refused: an admin already exists");
                return 3;
            }
            System.out.println("Bootstrap admin created");
            return 0;
        } catch (RuntimeException failure) {
            System.err.println("Bootstrap failed; no success reported");
            return 4;
        } finally {
            Arrays.fill(password, '\0');
        }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration(exclude = {
            org.springframework.boot.security.autoconfigure.SecurityAutoConfiguration.class,
            org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration.class})
    @EntityScan(basePackageClasses = ${packageName}.security.UserAccount.class)
    @EnableJpaRepositories(basePackageClasses = ${packageName}.security.UserAccountRepository.class)
    @Import({FirstAdminBootstrapService.class, ${packageName}.security.PasswordEncodingConfig.class})
    static class BootstrapContext { }
}
