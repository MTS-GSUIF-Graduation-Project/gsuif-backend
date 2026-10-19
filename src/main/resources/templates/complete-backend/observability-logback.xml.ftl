<#ftl strip_whitespace=true encoding="UTF-8">
<?xml version="1.0" encoding="UTF-8"?>
<configuration>
    <appender name="CONSOLE" class="ch.qos.logback.core.ConsoleAppender">
        <encoder class="ch.qos.logback.core.encoder.LayoutWrappingEncoder">
            <charset>UTF-8</charset>
            <layout class="${packageName}.logging.MaskingPatternLayout">
                <pattern>%d{yyyy-MM-dd HH:mm:ss.SSS} %-5level [%X{correlationId:-NO_CORR_ID}] %logger{36} - %msg%n%nopex</pattern>
            </layout>
        </encoder>
    </appender>
    <!-- Spring's default resolver can include rejected request values in WARN messages. -->
    <logger name="org.springframework.web.servlet.mvc.support.DefaultHandlerExceptionResolver" level="ERROR"/>
    <root level="INFO"><appender-ref ref="CONSOLE"/></root>
</configuration>
