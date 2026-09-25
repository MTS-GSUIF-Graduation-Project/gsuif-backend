package eg.mts.gsuif.config;

import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.enums.SecuritySchemeType;
import io.swagger.v3.oas.annotations.info.Contact;
import io.swagger.v3.oas.annotations.info.Info;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.security.SecurityScheme;
import org.springdoc.core.models.GroupedOpenApi;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@OpenAPIDefinition(
        info = @Info(
                title = "GSUIF API",
                version = "1.0",
                description = "Global State UI Framework API documentation.",
                contact = @Contact(name = "GSUIF Team")
        ),
        security = @SecurityRequirement(name = "bearerAuth")
)
@SecurityScheme(
        name = "bearerAuth",
        type = SecuritySchemeType.HTTP,
        scheme = "bearer",
        bearerFormat = "JWT"
)
public class OpenApiConfig {

    @Bean
    public GroupedOpenApi metadataApi() {
        return GroupedOpenApi.builder()
                .group("metadata-api")
                .packagesToScan("eg.mts.gsuif.controller")
                .pathsToMatch(
                        "/api/v1/projects/**",
                        "/api/v1/pages/**"
                )
                .build();
    }

    @Bean
    public GroupedOpenApi authApi() {
        return GroupedOpenApi.builder()
                .group("auth-api")
                .packagesToScan("eg.mts.gsuif.controller")
                .pathsToMatch("/api/auth/**")
                .build();
    }

    @Bean
    public GroupedOpenApi referenceApi() {
        return GroupedOpenApi.builder()
                .group("reference-api")
                .packagesToScan("eg.mts.gsuif.controller")
                .pathsToMatch("/api/v1/work-orders/**")
                .build();
    }
}
