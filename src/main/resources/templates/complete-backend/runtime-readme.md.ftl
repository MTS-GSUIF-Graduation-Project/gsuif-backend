<#ftl strip_whitespace=true encoding="UTF-8">
# Generated ${artifactId} backend

Selected API contract: `${contractId}@${contractVersion}` (`contracts/${contractId}-${contractVersion}.yaml`, SHA-256 `${contractHash}`). The contract file is copied byte-for-byte from the selected version.

## Prerequisites and configuration

The completed application requires JDK 21, Maven 3.9+, and an independently provisioned PostgreSQL database. Supply `GENERATED_APP_DB_URL`, `GENERATED_APP_DB_USER`, `GENERATED_APP_DB_PASSWORD`, and `GENERATED_APP_JWT_SECRET` through the generated application's runtime environment or secret manager. There are no default DB passwords, JWT secrets or bootstrap credentials. Do not put secrets in source, build artifacts or Docker images. A later explicit, non-HTTP first-admin command will take operator-supplied credentials; normal startup will not create an account. The generated application needs no GSUIF database, GSUIF source files, test fixtures or OpenRouter credentials.

Hibernate uses schema validation and Flyway owns versioned migrations. No destructive reset is configured. This **scaffold subset cannot yet start**: the entity migration, error handler, security/bootstrap implementation and service implementation are later review steps. `mvn validate` checks only the Maven scaffold model, not compilation or runtime behavior.

## Dependency choices

Spring Boot Web MVC serves the declared REST contract; Data JPA supports the app-owned entity/repository; Validation supports request constraints; Security supports the declared method roles. Flyway and its PostgreSQL adapter own future versioned migrations; the PostgreSQL driver connects only to the generated app's database. Springdoc API supplies the generated `@Operation` annotation without a Swagger UI dependency. <#if enversAudited>Hibernate Envers is included because the selected entity is audited.<#else>Hibernate Envers is omitted because the selected entity is not Envers-audited.</#if> Spring Boot `4.0.8` pins its managed dependency set; direct dependencies and build plugins have fixed versions. No version ranges, snapshots or dynamic latest selectors are used.
