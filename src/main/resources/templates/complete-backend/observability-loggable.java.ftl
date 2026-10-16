<#ftl strip_whitespace=true encoding="UTF-8">
package ${packageName}.aspect;

@java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.RUNTIME)
@java.lang.annotation.Target(java.lang.annotation.ElementType.METHOD)
public @interface Loggable { }
