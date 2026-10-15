<#ftl strip_whitespace=true encoding="UTF-8">
package ${basePackage}.api;

public interface ${interfaceName} {
<#list methods as method>
    @org.springframework.web.bind.annotation.RequestMapping(method = org.springframework.web.bind.annotation.RequestMethod.${method.verb}, path = "${method.path}", produces = "application/json"<#if method.verb == "POST" || method.verb == "PUT">, consumes = "application/json"</#if>)
    @io.swagger.v3.oas.annotations.Operation(operationId = "${method.operationId}")
    org.springframework.http.ResponseEntity<ApiResponse<${method.payloadType}>> ${method.operationId}(<#list method.parameters as parameter>${parameter.annotation} ${parameter.type} ${parameter.name}<#if parameter_has_next>, </#if></#list>);

</#list>
}
