<#ftl strip_whitespace=true encoding="UTF-8">
<#-- SCRUM-44 explicit fixture renderer. Maps are not GenerationContext APIs. -->
<#if !(project??) || !project?is_hash><#stop "entity.ftl: project: expected map"></#if>
<#if !(entity??) || !entity?is_hash><#stop "entity.ftl: entity: expected map"></#if>
<#if !(project.basePackage??) || !project.basePackage?is_string || !project.basePackage?matches("[a-z][a-z0-9_]*(\\.[a-z][a-z0-9_]*)+")><#stop "entity.ftl: project.basePackage: expected Java package"></#if>
<#if !(entity.auditBasePackage??) || !entity.auditBasePackage?is_string || !entity.auditBasePackage?matches("[a-z][a-z0-9_]*(\\.[a-z][a-z0-9_]*)+")><#stop "entity.ftl: entity.auditBasePackage: expected Java package providing AuditableEntity"></#if>
<#if !(entity.className??) || !entity.className?is_string || !entity.className?matches("[A-Z][A-Za-z0-9]*")><#stop "entity.ftl: entity.className: expected PascalCase Java identifier"></#if>
<#if !(entity.tableName??) || !entity.tableName?is_string || !entity.tableName?matches("[a-z][a-z0-9_]*")><#stop "entity.ftl: entity.tableName: expected snake_case name"></#if>
<#if !(entity.fields??) || !entity.fields?is_sequence><#stop "entity.ftl: entity.fields: expected list"></#if>
<#if entity.enversAudited?? && !entity.enversAudited?is_boolean><#stop "entity.ftl: entity.enversAudited: expected Boolean"></#if>
<#assign javaReserved=["abstract", "assert", "boolean", "break", "byte", "case", "catch", "char", "class", "const", "continue", "default", "do", "double", "else", "enum", "extends", "final", "finally", "float", "for", "goto", "if", "implements", "import", "instanceof", "int", "interface", "long", "native", "new", "package", "private", "protected", "public", "return", "short", "static", "strictfp", "super", "switch", "synchronized", "this", "throw", "throws", "transient", "try", "void", "volatile", "while", "true", "false", "null", "var", "yield", "record", "sealed", "permits"]>
<#list project.basePackage?split(".") as segment><#if javaReserved?seq_contains(segment)><#stop "entity.ftl: project.basePackage: contains Java reserved segment"></#if></#list>
<#list entity.auditBasePackage?split(".") as segment><#if javaReserved?seq_contains(segment)><#stop "entity.ftl: entity.auditBasePackage: contains Java reserved segment"></#if></#list>
<#assign supportedTypes=["String", "UUID", "LocalDate", "LocalDateTime", "Integer", "Long", "BigDecimal", "Boolean"]>
<#assign importedNames=["Column", "Entity", "GeneratedValue", "GenerationType", "Id", "Table", "AuditableEntity", "UUID", "EnumType", "Enumerated", "Audited", "LocalDate", "LocalDateTime", "BigDecimal"]>
<#if supportedTypes?seq_contains(entity.className) || importedNames?seq_contains(entity.className)><#stop "entity.ftl: entity.className: collides with a supported or imported Java type"></#if>
<#assign seenNames=["id", "createdAt", "updatedAt", "createdBy", "lastModifiedBy"]>
<#assign seenColumns=["id", "created_at", "updated_at", "created_by", "last_modified_by"]>
<#list entity.fields as f>
  <#if !f?is_hash><#stop "entity.ftl: entity.fields[${f_index?c}]: expected map"></#if>
  <#if !(f.name??) || !f.name?is_string || !f.name?matches("[a-z][A-Za-z0-9]*") || seenNames?seq_contains(f.name) || javaReserved?seq_contains(f.name)><#stop "entity.ftl: entity.fields[${f_index?c}].name: expected unique camelCase identifier"></#if>
  <#if !(f.columnName??) || !f.columnName?is_string || !f.columnName?matches("[a-z][a-z0-9_]*") || seenColumns?seq_contains(f.columnName)><#stop "entity.ftl: entity.fields[${f_index?c}].columnName: expected unique snake_case name"></#if>
  <#if !(f.javaType??) || !f.javaType?is_string || !(supportedTypes?seq_contains(f.javaType) || (f.enumType?? && f.javaType == f.enumType && f.javaType?matches("[A-Z][A-Za-z0-9]*")))><#stop "entity.ftl: entity.fields[${f_index?c}].javaType: unsupported Java type"></#if>
  <#if !(f.nullable??) || !f.nullable?is_boolean><#stop "entity.ftl: entity.fields[${f_index?c}].nullable: expected Boolean"></#if>
  <#if f.enumType?? && (!f.enumType?is_string || !f.enumType?matches("[A-Z][A-Za-z0-9]*") || f.enumType != f.javaType || supportedTypes?seq_contains(f.enumType) || importedNames?seq_contains(f.enumType) || f.enumType == entity.className)><#stop "entity.ftl: entity.fields[${f_index?c}].enumType: expected matching non-scalar enum type"></#if>
  <#if f.columnLength?? && (!f.columnLength?is_number || f.columnLength?floor != f.columnLength || f.columnLength < 1 || f.columnLength > 2147483647 || !(f.javaType == "String" || f.enumType??))><#stop "entity.ftl: entity.fields[${f_index?c}].columnLength: expected positive textual column length"></#if>
  <#assign seenNames=seenNames + [f.name]>
  <#assign seenColumns=seenColumns + [f.columnName]>
</#list>
package ${project.basePackage}.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import ${entity.auditBasePackage}.AuditableEntity;
<#if entity.fields?filter(f -> f.enumType??)?size gt 0>import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
<#list entity.fields?filter(f -> f.enumType??) as enumField>import ${entity.enumPackage}.${enumField.enumType};
</#list>
</#if>
<#if entity.enversAudited!false>import org.hibernate.envers.Audited;
</#if>
import java.util.UUID;
<#if entity.fields?filter(f -> f.javaType == "LocalDate")?size gt 0>import java.time.LocalDate;
</#if>
<#if entity.fields?filter(f -> f.javaType == "LocalDateTime")?size gt 0>import java.time.LocalDateTime;
</#if>
<#if entity.fields?filter(f -> f.javaType == "BigDecimal")?size gt 0>import java.math.BigDecimal;
</#if>
@Entity
@Table(name = "${entity.tableName}")
<#if entity.enversAudited!false>@Audited
</#if>public class ${entity.className} extends AuditableEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

<#list entity.fields as f>
    <#if f.enumType??>@Enumerated(EnumType.STRING)
    </#if>@Column(name = "${f.columnName}", nullable = ${f.nullable?c}<#if f.columnLength??>, length = ${f.columnLength?c}</#if>)
    private ${f.javaType} ${f.name};

</#list>    public ${entity.className}() {
    }

    public UUID getId() {
        return id;
    }

    protected void setId(UUID id) {
        this.id = id;
    }

<#list entity.fields as f>
    public ${f.javaType} get${f.name?cap_first}() {
        return ${f.name};
    }

    public void set${f.name?cap_first}(${f.javaType} ${f.name}) {
        this.${f.name} = ${f.name};
    }

</#list>    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof ${entity.className} that)) return false;
        return id != null && id.equals(that.getId());
    }

    @Override
    public int hashCode() {
        return ${entity.className}.class.hashCode();
    }
}
