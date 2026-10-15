<#ftl strip_whitespace=true encoding="UTF-8">
package ${packageName}.service;

public interface ${serviceName} {
    ${dtoPackage}.${dtoName} create(${dtoPackage}.${createName} request);
    ${dtoPackage}.${dtoName} getById(java.util.UUID id);
    ${dtoPackage}.${pageName} list(<#list listParameters as parameter>${parameter.type} ${parameter.name}<#if parameter_has_next>, </#if></#list>);
    ${dtoPackage}.${dtoName} update(java.util.UUID id, ${dtoPackage}.${updateName} request);
    void delete(java.util.UUID id);
}
