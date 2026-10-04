<#ftl strip_whitespace=true encoding="UTF-8">
<#-- SCRUM-44 fixed AssetTicket integration fixture. OpenAPI owns mappings and signatures. -->
<#if !(project??) || !project?is_hash><#stop "controller-crud.ftl: project: expected map"></#if>
<#if !(entity??) || !entity?is_hash><#stop "controller-crud.ftl: entity: expected map"></#if>
<#if !(api??) || !api?is_hash><#stop "controller-crud.ftl: api: expected map"></#if>
<#if !(project.basePackage??) || !project.basePackage?is_string || !project.basePackage?matches("[a-z][a-z0-9_]*(\\.[a-z][a-z0-9_]*)+")><#stop "controller-crud.ftl: project.basePackage: expected Java package"></#if>
<#if !(entity.className??) || !entity.className?is_string || !entity.className?matches("[A-Z][A-Za-z0-9]*")><#stop "controller-crud.ftl: entity.className: expected PascalCase Java identifier"></#if>
<#assign javaReserved=["abstract", "assert", "boolean", "break", "byte", "case", "catch", "char", "class", "const", "continue", "default", "do", "double", "else", "enum", "extends", "final", "finally", "float", "for", "goto", "if", "implements", "import", "instanceof", "int", "interface", "long", "native", "new", "package", "private", "protected", "public", "return", "short", "static", "strictfp", "super", "switch", "synchronized", "this", "throw", "throws", "transient", "try", "void", "volatile", "while", "true", "false", "null", "var", "yield", "record", "sealed", "permits"]>
<#list project.basePackage?split(".") as segment><#if javaReserved?seq_contains(segment)><#stop "controller-crud.ftl: project.basePackage: contains Java reserved segment"></#if></#list>
<#if javaReserved?seq_contains(entity.className)><#stop "controller-crud.ftl: entity.className: Java reserved identifier"></#if>
<#list ["dtoPackage", "responsePackage", "statusEnumPackage", "servicePackage", "interfacePackage"] as key>
  <#if !(api[key]??) || !api[key]?is_string || !api[key]?matches("[a-z][a-z0-9_]*(\\.[a-z][a-z0-9_]*)+")><#stop "controller-crud.ftl: api.${key}: expected Java package"></#if>
  <#list api[key]?split(".") as segment><#if javaReserved?seq_contains(segment)><#stop "controller-crud.ftl: api.${key}: contains Java reserved segment"></#if></#list>
</#list>
<#assign seenClassNames=[entity.className + "Controller", "ApiResponse", "ResponseEntity", "HttpStatus", "RestController", "UUID"]>
<#list ["dtoClass", "pageDtoClass", "createRequestClass", "updateRequestClass", "statusEnumClass", "serviceInterface", "interfaceClass"] as key>
  <#if !(api[key]??) || !api[key]?is_string || !api[key]?matches("[A-Z][A-Za-z0-9]*")><#stop "controller-crud.ftl: api.${key}: expected Java class identifier"></#if>
  <#if javaReserved?seq_contains(api[key])><#stop "controller-crud.ftl: api.${key}: Java reserved identifier"></#if>
  <#if seenClassNames?seq_contains(api[key])><#stop "controller-crud.ftl: api.${key}: collides with an imported or generated Java type"></#if>
  <#assign seenClassNames=seenClassNames + [api[key]]>
</#list>
<#assign seenMethodNames=[]>
<#list ["createMethod", "getMethod", "listMethod", "updateMethod", "deleteMethod"] as key>
  <#if !(api[key]??) || !api[key]?is_string || !api[key]?matches("[a-z][A-Za-z0-9]*")><#stop "controller-crud.ftl: api.${key}: expected Java method identifier"></#if>
  <#if javaReserved?seq_contains(api[key])><#stop "controller-crud.ftl: api.${key}: Java reserved identifier"></#if>
  <#if seenMethodNames?seq_contains(api[key])><#stop "controller-crud.ftl: api.${key}: duplicate Java method name"></#if>
  <#assign seenMethodNames=seenMethodNames + [api[key]]>
</#list>
package ${project.basePackage}.controller;

import ${api.interfacePackage}.${api.interfaceClass};
import ${api.responsePackage}.ApiResponse;
import ${api.dtoPackage}.${api.dtoClass};
import ${api.dtoPackage}.${api.pageDtoClass};
import ${api.dtoPackage}.${api.createRequestClass};
import ${api.dtoPackage}.${api.updateRequestClass};
import ${api.statusEnumPackage}.${api.statusEnumClass};
import ${api.servicePackage}.${api.serviceInterface};
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
<#if api.operationRoles??>import org.springframework.security.access.prepost.PreAuthorize;
</#if>
import java.util.UUID;

@RestController
public class ${entity.className}Controller implements ${api.interfaceClass} {
    private final ${api.serviceInterface} service;

    public ${entity.className}Controller(${api.serviceInterface} service) {
        this.service = service;
    }

    @Override
<#if api.operationRoles??>    @PreAuthorize("${api.operationRoles.createMethod}")
</#if>
    public ResponseEntity<ApiResponse<${api.dtoClass}>> ${api.createMethod}(${api.createRequestClass} request) {
        ${api.dtoClass} created = service.create(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success(created, "Created successfully", 201));
    }

    @Override
<#if api.operationRoles??>    @PreAuthorize("${api.operationRoles.getMethod}")
</#if>
    public ResponseEntity<ApiResponse<${api.dtoClass}>> ${api.getMethod}(UUID id) {
        return ResponseEntity.ok(ApiResponse.success(service.getById(id), "Retrieved successfully"));
    }

    @Override
<#if api.operationRoles??>    @PreAuthorize("${api.operationRoles.listMethod}")
</#if>
    public ResponseEntity<ApiResponse<${api.pageDtoClass}>> ${api.listMethod}(${api.statusEnumClass} status) {
        return ResponseEntity.ok(ApiResponse.success(service.list(status), "Retrieved successfully"));
    }

    @Override
<#if api.operationRoles??>    @PreAuthorize("${api.operationRoles.updateMethod}")
</#if>
    public ResponseEntity<ApiResponse<${api.dtoClass}>> ${api.updateMethod}(UUID id, ${api.updateRequestClass} request) {
        return ResponseEntity.ok(ApiResponse.success(service.update(id, request), "Updated successfully"));
    }

    @Override
<#if api.operationRoles??>    @PreAuthorize("${api.operationRoles.deleteMethod}")
</#if>
    public ResponseEntity<ApiResponse<Void>> ${api.deleteMethod}(UUID id) {
        service.delete(id);
        return ResponseEntity.ok(ApiResponse.success(null, "Deleted successfully"));
    }
}
