<#ftl strip_whitespace=true encoding="UTF-8">
package ${basePackage}.api;

public interface ${interfaceName} {
<#list methods as method>
    @org.springframework.web.bind.annotation.RequestMapping(method = org.springframework.web.bind.annotation.RequestMethod.${method.verb}, path = "${method.path}", produces = "application/json"<#if method.verb == "POST" || method.verb == "PUT">, consumes = "application/json"</#if>)
    @io.swagger.v3.oas.annotations.Operation(operationId = "${method.operationId}")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(name = "bearerAuth")
    @io.swagger.v3.oas.annotations.responses.ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "${method.status}",
                description = "Contracted success response", useReturnTypeSchema = true),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400",
                description = "Invalid request (runtime behavior; selected contract may omit this declaration)",
                content = @io.swagger.v3.oas.annotations.media.Content(mediaType = "application/json",
                        schema = @io.swagger.v3.oas.annotations.media.Schema(implementation = ErrorApiResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Authentication required",
                content = @io.swagger.v3.oas.annotations.media.Content(mediaType = "application/json",
                        schema = @io.swagger.v3.oas.annotations.media.Schema(implementation = ErrorApiResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Access denied",
                content = @io.swagger.v3.oas.annotations.media.Content(mediaType = "application/json",
                        schema = @io.swagger.v3.oas.annotations.media.Schema(implementation = ErrorApiResponse.class))),
<#if method.item>
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Requested record not found",
                content = @io.swagger.v3.oas.annotations.media.Content(mediaType = "application/json",
                        schema = @io.swagger.v3.oas.annotations.media.Schema(implementation = ErrorApiResponse.class))),
</#if>
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "405", description = "Method not allowed",
                content = @io.swagger.v3.oas.annotations.media.Content(mediaType = "application/json",
                        schema = @io.swagger.v3.oas.annotations.media.Schema(implementation = ErrorApiResponse.class))),
<#if method.write>
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "415", description = "Unsupported media type",
                content = @io.swagger.v3.oas.annotations.media.Content(mediaType = "application/json",
                        schema = @io.swagger.v3.oas.annotations.media.Schema(implementation = ErrorApiResponse.class))),
</#if>
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "500", description = "Unexpected server error",
                content = @io.swagger.v3.oas.annotations.media.Content(mediaType = "application/json",
                        schema = @io.swagger.v3.oas.annotations.media.Schema(implementation = ErrorApiResponse.class)))
    })
    org.springframework.http.ResponseEntity<ApiResponse<${method.payloadType}>> ${method.operationId}(<#list method.parameters as parameter>${parameter.annotation} ${parameter.type} ${parameter.name}<#if parameter_has_next>, </#if></#list>);

</#list>
}
