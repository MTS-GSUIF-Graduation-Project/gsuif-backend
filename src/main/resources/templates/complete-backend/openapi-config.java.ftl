<#ftl strip_whitespace=true encoding="UTF-8">
package ${packageName}.config;

@org.springframework.context.annotation.Configuration(proxyBeanMethods = false)
@io.swagger.v3.oas.annotations.security.SecurityScheme(name = "bearerAuth",
        type = io.swagger.v3.oas.annotations.enums.SecuritySchemeType.HTTP,
        scheme = "bearer", bearerFormat = "JWT")
public class OpenApiConfig {
    @org.springframework.context.annotation.Bean
    io.swagger.v3.oas.models.OpenAPI generatedOpenApi() {
        return new io.swagger.v3.oas.models.OpenAPI().info(new io.swagger.v3.oas.models.info.Info()
                .title("${entityName} generated API").version("${contractVersion}")
                .description("Runtime documentation for ${contractId}@${contractVersion}; selected contract bytes are unchanged."));
    }

    @org.springframework.context.annotation.Bean
    org.springdoc.core.customizers.OpenApiCustomizer generatedSecurityDocumentation(
            ${packageName}.security.SecurityMode mode) {
        return document -> {
            if (document.getPaths() == null) return;
            if (mode.isDev()) document.getPaths().values().forEach(path ->
                    path.readOperations().forEach(operation -> operation.setSecurity(java.util.List.of())));
            var login = document.getPaths().get("/api/auth/login");
            if (login != null && login.getPost() != null) login.getPost().setSecurity(java.util.List.of());
        };
    }
}
