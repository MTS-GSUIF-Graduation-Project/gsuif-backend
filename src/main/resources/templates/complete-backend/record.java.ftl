<#ftl strip_whitespace=true encoding="UTF-8">
package ${packageName};

public record ${name}(
<#list members as member>
<#list member.annotations as annotation>    ${annotation}
</#list>    ${member.type} ${member.name}<#if member_has_next>,</#if>
</#list>) { }
