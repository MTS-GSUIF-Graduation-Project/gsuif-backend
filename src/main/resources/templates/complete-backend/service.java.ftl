<#ftl strip_whitespace=true encoding="UTF-8">
package ${packageName}.service;

/** getById, update and delete throw ${packageName}.exception.RequestedRecordNotFoundException
 * only when the requested id is absent; other persistence failures propagate. */
public interface ${serviceName} {
    ${dtoPackage}.${dtoName} create(${dtoPackage}.${createName} request);
    ${dtoPackage}.${dtoName} getById(java.util.UUID id);
<#if paginationGuidance?has_content>
    /** ${paginationGuidance} */
</#if>
    ${dtoPackage}.${pageName} list(<#list listParameters as parameter>${parameter.type} ${parameter.name}<#if parameter_has_next>, </#if></#list>);
    ${dtoPackage}.${dtoName} update(java.util.UUID id, ${dtoPackage}.${updateName} request);
    void delete(java.util.UUID id);
}
