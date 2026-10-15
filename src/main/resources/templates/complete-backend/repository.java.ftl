<#ftl strip_whitespace=true encoding="UTF-8">
package ${packageName}.repository;

public interface ${entityName}Repository extends
        org.springframework.data.jpa.repository.JpaRepository<${packageName}.entity.${entityName}, java.util.UUID>,
        org.springframework.data.jpa.repository.JpaSpecificationExecutor<${packageName}.entity.${entityName}> { }
