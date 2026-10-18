<#ftl strip_whitespace=true encoding="UTF-8">
package ${basePackage}.controller;

@org.springframework.web.bind.annotation.RestController
public class ${controllerName} implements ${basePackage}.api.${interfaceName} {
    private final ${basePackage}.service.${serviceName} service;

    public ${controllerName}(${basePackage}.service.${serviceName} service) {
        this.service = service;
    }

<#list methods as method>
    @Override
    @org.springframework.security.access.prepost.PreAuthorize("${method.roles}")
    @${basePackage}.aspect.Loggable
    public org.springframework.http.ResponseEntity<${basePackage}.api.ApiResponse<${method.payloadType}>> ${method.operationId}(<#list method.parameters as parameter>${parameter.type} ${parameter.name}<#if parameter_has_next>, </#if></#list>) {
<#list method.bounds as bound>
        if (${bound.condition}) return org.springframework.http.ResponseEntity.badRequest().body(
                ${basePackage}.api.ApiResponse.<${method.payloadType}>error(400, "Invalid request",
                        java.util.Map.of("${bound.name}", "Invalid value")));
</#list>
<#if method.delete>
        service.${method.serviceMethod}(${method.arguments});
        return org.springframework.http.ResponseEntity.status(${method.status}).body(
                ${basePackage}.api.ApiResponse.<Void>success(null, "Success", ${method.status}));
<#else>
        var body = service.${method.serviceMethod}(${method.arguments});
        return org.springframework.http.ResponseEntity.status(${method.status}).body(
                ${basePackage}.api.ApiResponse.success(body, "Success", ${method.status}));
</#if>
    }

</#list>
}
