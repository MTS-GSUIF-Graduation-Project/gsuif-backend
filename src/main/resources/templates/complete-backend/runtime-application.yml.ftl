<#ftl strip_whitespace=true encoding="UTF-8">
spring:
  datasource:
    url: <#noparse>${GENERATED_APP_DB_URL}</#noparse>
    username: <#noparse>${GENERATED_APP_DB_USER}</#noparse>
    password: <#noparse>${GENERATED_APP_DB_PASSWORD}</#noparse>
    driver-class-name: org.postgresql.Driver
  jpa:
    open-in-view: false
    hibernate:
      ddl-auto: validate
  flyway:
    enabled: true
    clean-disabled: true
    baseline-on-migrate: false
  sql:
    init:
      mode: never
generated-app:
  security:
    jwt-secret: <#noparse>${GENERATED_APP_JWT_SECRET}</#noparse>
