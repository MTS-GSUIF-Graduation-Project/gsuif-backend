<#ftl strip_whitespace=true encoding="UTF-8">
CREATE TABLE ${table} (
<#list columns as column>    ${column.name} ${column.sqlType}<#if !column.nullable> NOT NULL</#if>,
</#list>    CONSTRAINT ${primary} PRIMARY KEY (id)<#list checks as check>,
    CONSTRAINT ${check.name} CHECK (${check.column} IN (<#list check.enumValues as value>'${value}'<#sep>, </#list>))</#list>
);
