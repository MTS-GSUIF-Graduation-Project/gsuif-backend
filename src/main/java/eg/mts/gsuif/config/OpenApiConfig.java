package eg.mts.gsuif.config;

import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.enums.SecuritySchemeType;
import io.swagger.v3.oas.annotations.info.Contact;
import io.swagger.v3.oas.annotations.info.Info;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.security.SecurityScheme;
import org.springdoc.core.models.GroupedOpenApi;
import org.springdoc.core.customizers.OpenApiCustomizer;
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
                .addOpenApiCustomizer(methodNotAllowedCustomizer())
                .build();
    }

    @Bean
    public GroupedOpenApi authApi() {
        return GroupedOpenApi.builder()
                .group("auth-api")
                .packagesToScan("eg.mts.gsuif.controller")
                .pathsToMatch("/api/auth/**")
                .addOpenApiCustomizer(methodNotAllowedCustomizer())
                .build();
    }

    @Bean
    public GroupedOpenApi referenceApi() {
        return GroupedOpenApi.builder()
                .group("reference-api")
                .packagesToScan("eg.mts.gsuif.controller")
                .pathsToMatch("/api/v1/work-orders/**")
                .addOpenApiCustomizer(methodNotAllowedCustomizer())
                .build();
    }

    @Bean
    public OpenApiCustomizer methodNotAllowedCustomizer() {
        return openApi -> {
            io.swagger.v3.oas.models.media.Schema<?> errorSchema = new io.swagger.v3.oas.models.media.ObjectSchema()
                    .$ref("#/components/schemas/ErrorApiResponse");
            io.swagger.v3.oas.models.media.Content content = new io.swagger.v3.oas.models.media.Content()
                    .addMediaType("application/json", new io.swagger.v3.oas.models.media.MediaType().schema(errorSchema))
                    .addMediaType("*/*", new io.swagger.v3.oas.models.media.MediaType().schema(errorSchema));

            io.swagger.v3.oas.models.responses.ApiResponse response = new io.swagger.v3.oas.models.responses.ApiResponse()
                    .description("Method Not Allowed")
                    .content(content);

            if (openApi.getComponents() == null) {
                openApi.setComponents(new io.swagger.v3.oas.models.Components());
            }
            openApi.getComponents().addResponses("Error405", response);

            if (openApi.getInfo() != null) {
                String existingDesc = openApi.getInfo().getDescription();
                String addendum = "Requests using an unsupported HTTP method return 405 with the standard error envelope.";
                openApi.getInfo().setDescription(existingDesc != null ? existingDesc + " " + addendum : addendum);
            }
        };
    }
}
