# Capability Matrix v1
> **Owner:** M2 (Alaa Elmasry) — Jmix spike · M3 (Esraa Abdelrazek) — OpenAPI spike
> **Source:** T-08 (SCRUM-37) + T-09 (SCRUM-38) consolidated in T-11 (SCRUM-40)
> **Decisions:** DEC-021 (Jmix REJECT) · DEC-022 (OpenAPI ACCEPT WITH LIMITATIONS)
> **Standards benchmark:** `standards-checklist.md` (SCRUM-35, owner M1 — Sondos Hashem)
> **Status:** COMPLETE — both spikes evaluated, one decision per component

A component uses a tool only if **all four** criteria pass:
Available ✓ AND Generated ✓ AND Editable (without the tool) ✓ AND Standard-compliant ✓.
Otherwise the decision is **FreeMarker template** (TemplateOnlyProvider — already the Phase 1 plan per ADR-003).

---

## How to Read This Matrix

| Column | Meaning |
|---|---|
| Component | ID + name from `components.yaml` |
| Tool evaluated | Tool name + version tested in spike |
| Generates? | Does the tool actually produce output for this component? |
| Editable? | Can the output be modified and compiled **without** the tool at runtime? |
| Standard-compliant? | Does the output match `standards-checklist.md`? |
| Extra dependencies | Runtime libs the generated code adds beyond our standard stack |
| **Decision** | Final choice: tool name or **FreeMarker** |
| Rationale | Why this decision was made |

**Verdict symbols:**

| Symbol | Meaning |
|---|---|
| ✅ | Fully passes criterion |
| ⚠️ | Partially passes — requires extra work |
| ❌ | Fails criterion |
| — | Tool not applicable to this component |

---

## Consolidated Component Decision Table

One row per Phase 1 component from `components.yaml`.
Deferred components (DEF-01–DEF-07) are listed at the bottom — no tool decision needed until scheduled.

| Component | Tool evaluated | Generates? | Editable? | Standard-compliant? | Extra dependencies | **Decision** | Rationale |
|---|---|---|---|---|---|---|---|
| **BE-01** Backend Platform Architecture | Jmix 3.0.1 | ❌ | — | — | — | **FreeMarker** | Jmix generates Vaadin/FlowUI full-stack, not our layered package skeleton. Architecture is hand-written once. |
| **BE-02** Metadata Model (Entity/JPA) | Jmix 3.0.1 | ✅ | ⚠️ (with Jmix only) | ❌ | `io.jmix.core`, EclipseLink | **FreeMarker** | Jmix entity uses `@JmixEntity` + EclipseLink — violates ADR-008 (lock-in) and ADR-004 (Hibernate). OpenAPI is not a persistence generator. |
| **BE-03** Unified Request Framework (Request DTOs) | OpenAPI 7.16.0 | ✅ | ✅ | ✅ | `jackson-databind-nullable:0.2.6` | **OpenAPI Generator** | Generates Jakarta-validated request POJOs from OpenAPI spec. Fully editable, no runtime lock-in, matches STD-10. One extra compile-scope dependency acceptable. |
| **BE-04** Unified Response Framework (`ApiResponse<T>`) | Jmix 3.0.1 / OpenAPI 7.16.0 | ❌ / ❌ | — | — | — | **FreeMarker** | `ApiResponse<T>` is our custom 5-field envelope (STD-01–05). Neither tool generates it. Hand-written once, referenced by all generated controllers. |
| **BE-05** Automatic CRUD Generator (REST endpoints) | OpenAPI 7.16.0 | ✅ | ✅ | ⚠️ | `jackson-databind-nullable:0.2.6` | **OpenAPI Generator + FreeMarker** | OpenAPI generates the interface with correct `ResponseEntity<ApiResponse<T>>` return type (STD-05) via 1-line `responseType.mustache` override. Developer implements `@RestController` in `src/main/java/` using FreeMarker template — STD-01 envelope populated there. |
| **BE-06** Authentication Framework (JWT) | Jmix 3.0.1 / OpenAPI 7.16.0 | ❌ / — | — | — | — | **FreeMarker** | Jmix has a different proprietary security model (FlowUI login, not JWT). OpenAPI does not generate security infrastructure. JWT filter, SecurityConfig, and login endpoint are hand-written. |
| **BE-07** Authorization Framework (Basic RBAC) | Jmix 3.0.1 / OpenAPI 7.16.0 | ❌ / — | — | — | — | **FreeMarker** | Jmix security model differs completely. OpenAPI not applicable. `@PreAuthorize` + role loading is hand-written per STD-20. |
| **BE-08** Audit Framework | Jmix 3.0.1 / OpenAPI 7.16.0 | ⚠️ / — | ⚠️ (with Jmix) | ❌ | `io.jmix.*` | **FreeMarker** | Jmix generates audit annotations but code remains inside Jmix runtime (violates ADR-008). OpenAPI not applicable. JPA Auditing + Hibernate Envers is hand-written per STD-23–25. |
| **BE-09** Global Exception Handling | Jmix 3.0.1 / OpenAPI 7.16.0 | ❌ / — | — | — | — | **FreeMarker** | Neither tool generates `@RestControllerAdvice`. `GlobalExceptionHandler` is hand-written per STD-09–13. |
| **BE-10** Logging (AOP + Correlation ID + Masking) | Jmix 3.0.1 / OpenAPI 7.16.0 | ❌ / — | — | — | — | **FreeMarker** | Neither tool generates `@Loggable` aspect, MDC filter, or sensitive-data masking. Hand-written per STD-14–17. |
| **BE-11** API Documentation (Swagger/OpenAPI) | OpenAPI 7.16.0 | ✅ | ✅ | ✅ | none beyond springdoc | **OpenAPI Generator** | Generates `@Operation`, `@ApiResponse`, `@Parameter`, `@Schema` annotations on every method and DTO. Fully conformant with STD-27. No extra runtime dependency beyond springdoc already in the stack. |
| **BE-12** Testing & Build Validation | Jmix 3.0.1 / OpenAPI 7.16.0 | — / — | — | — | — | **FreeMarker / Hand-written** | Testing infrastructure, JaCoCo gate, and golden tests are framework code — not generated artifacts. Neither tool applies. |
| **BE-13** Code Generation Engine (TemplateOnlyProvider) | Jmix 3.0.1 / OpenAPI 7.16.0 | — / — | — | — | — | **FreeMarker (IS the engine)** | This component IS the generation engine itself. Tool decisions here are circular. Phase 1 engine = TemplateOnlyProvider + Apache FreeMarker (ADR-003). |
| **BE-14** Generation Export & Request Logging | Jmix 3.0.1 / OpenAPI 7.16.0 | — / — | — | — | — | **FreeMarker** | REST export endpoint and generation-run logging are framework system code. Neither tool generates framework infrastructure. |
| **BE-15** Metadata CRUD REST APIs | OpenAPI 7.16.0 | ✅ | ✅ | ⚠️ | `jackson-databind-nullable:0.2.6` | **OpenAPI Generator + FreeMarker** | Same pattern as BE-05: OpenAPI generates the interface (STD-05 + STD-27 + STD-28), FreeMarker template generates the `@RestController` implementation that populates `ApiResponse<T>` (STD-01–04). |
| **BE-16** Metadata Validation Framework | Jmix 3.0.1 / OpenAPI 7.16.0 | — / — | — | — | — | **FreeMarker** | Neither tool generates schema validators or pluggable business-rule validators. Hand-written per STD-10. |
| **BE-17** Metadata Versioning Service | Jmix 3.0.1 / OpenAPI 7.16.0 | — / — | — | — | — | **FreeMarker** | Immutable versioning logic (ADR-006) is domain-specific. Neither tool generates it. Hand-written. |
| **BE-18** Database-Agnostic Persistence (JPA Entities) | Jmix 3.0.1 | ✅ | ⚠️ (with Jmix) | ❌ | `io.jmix.core`, EclipseLink | **FreeMarker** | Jmix generates entities with EclipseLink (violates ADR-004 — Hibernate required) and `@JmixEntity` (violates ADR-008 — lock-in). GSUIF entities are hand-written with plain JPA + Hibernate. |
| **BE-19** Generated Artifact Registry | Jmix 3.0.1 / OpenAPI 7.16.0 | — / — | — | — | — | **FreeMarker** | Traceability registry (GenerationRun + GeneratedArtifact) is framework system code. Neither tool applies. |

### Deferred Components — No Tool Decision Yet

| Component | Status | Note |
|---|---|---|
| DEF-01 GraphQL Generator | DEFERRED (DEC-015) | Tool decision deferred until scheduled in Phase 2 |
| DEF-02 Metadata Cache (Redis) | DEFERRED (DEC-011) | Tool decision deferred |
| DEF-03 Password Management | DEFERRED (DEC-018) | Tool decision deferred |
| DEF-04 API Gateway | DEFERRED (DEC-016) | Tool decision deferred |
| DEF-05 Full UI Component Metadata Catalog | DEFERRED (DEC-019) | Tool decision deferred |
| DEF-06 Angular Frontend Application | DEFERRED (DEC-019) | Tool decision deferred |
| DEF-07 External AI Generation Provider | DEFERRED (ADR-002/003) | Tool decision deferred to Phase 2 |

---

## Overall Summary

| Tool | Overall Verdict | Components accepted | Notes |
|---|---|---|---|
| **Jmix 3.0.1** | **REJECT** (DEC-021) | None | Runtime lock-in to `io.jmix.*`, EclipseLink not Hibernate, Vaadin not Angular, no `ApiResponse`. Keep as optional study tool only (ADR-009). |
| **OpenAPI Generator 7.16.0** | **ACCEPT WITH LIMITATIONS** (DEC-022) | BE-03, BE-05, BE-11, BE-15 | Interface + DTO scaffolding only. STD-05 requires 1-line `responseType.mustache` override. STD-01 envelope populated by FreeMarker `@RestController` template. |
| **FreeMarker / TemplateOnlyProvider** | **CONFIRMED — Phase 1 engine** (ADR-003) | All others | Default for every component not covered by OpenAPI Generator. |

---

## Spike Details — Jmix Studio (T-08 / SCRUM-37)

**Version:** Jmix framework / Gradle plugin **3.0.1** · Jmix Studio IntelliJ plugin **3.0.2-261** · Java **21.0.12.1** (Temurin)
**Spike owner:** M2 (Alaa Elmasry) · **Spike date:** 2026-08-21
**Spike project (local only, not in this repo):** `D:\jmix-spike-test`

### Q1 — What exactly does Jmix generate?

For creating a `WorkOrder` JPA entity (with audit traits + data repository), Jmix Studio produced:

| Artifact | Notes |
|---|---|
| JPA entity class | `WorkOrder.java` — `@JmixEntity`, `@Entity`, `@Table`, UUID id, fields, audit fields, `@Version`, getters/setters |
| Data repository | `WorkOrderRepository.java` — extends `JmixDataRepository<WorkOrder, UUID>` |
| Repository config stub | `JmixDataRepositoryConfiguration.java` (auto-created with first repository) |

Starter project (before our entity) already included: `User` entity, security roles/config, Vaadin/FlowUI views, Liquibase changelogs, Gradle build with many `io.jmix.*` starters.

**Not generated:** Spring MVC `@RestController`, `ApiResponse<T>` DTOs, Hibernate Envers history tables, Angular code.

### Q2 — How does Jmix store its own metadata?

Jmix does **not** use a separate JSON metadata file like GSUIF. Observed storage mechanisms:
1. **Java annotations as metamodel** — `@JmixEntity`, `@JmixGeneratedValue` plus JPA annotations on entity classes.
2. **Liquibase XML** under `src/main/resources/.../liquibase/` for DB schema.
3. **Runtime metamodel** loaded by Jmix core from annotated classes — not an editable standalone JSON schema.

### Q3 — Is the generated output editable by hand?

**Yes, as source files.** `WorkOrder.java` is normal editable Java.

**But:** editability ≠ tool-independence. Code is written against Jmix APIs (`@JmixEntity`, `JmixDataRepository`). You can edit it inside a Jmix project; you cannot treat it as plain Spring Boot that runs after removing Jmix.

### Q4 — What dependencies does Jmix force into generated code?

From `build.gradle` (Jmix **3.0.1** BOM):

- `io.jmix.core:jmix-core-starter`
- `io.jmix.data:jmix-eclipselink-starter` (**EclipseLink**, not Hibernate)
- `io.jmix.security:jmix-security-starter`
- `io.jmix.flowui:jmix-flowui-*` (Vaadin-based UI)
- `com.vaadin:vaadin-dev`

Entity/repository code **compile-depends** on: `io.jmix.core.*`, `io.jmix.core.repository.JmixDataRepository`.

### Q5 — Does Jmix output match our Standards Checklist?

Benchmark: `standards-checklist.md` on `develop` (SCRUM-35).

| Checklist ref | Standard | Match? | Evidence |
|---|---|---|---|
| STD-01–05 | `ApiResponse<T>` envelope + controller return type | ❌ | No company response wrapper generated |
| STD-06, STD-08 | Package layout `com.company.gsuif/...` + naming | ❌ | Uses `com.company.jmixspiketest` + Jmix layout |
| ADR-004 | Spring Data JPA + **Hibernate** | ❌ | Uses **EclipseLink** via `jmix-eclipselink-starter` |
| STD-09–13 | `@RestControllerAdvice` + mapped errors | ❌ | Not produced for WorkOrder |
| STD-14–17 | Logback + `@Loggable` AOP | ❌ | Not in generated WorkOrder path |
| STD-18–21 | JWT filter + dev/test/prod toggle | ❌ | Jmix security model / FlowUI login instead |
| STD-23–24 | JPA auditing `@CreatedBy` etc. | ✅ partial | Present on `WorkOrder` |
| ADR-005 | UUID primary keys | ✅ | Matches ADR-005 |
| STD-27 | Swagger/springdoc | ❌ | Not the company Swagger setup |

**Overall: not standards-compliant** for GSUIF generated output.

### Q6 — If Jmix libraries were removed, would generated code still compile?

**No.**

- `WorkOrder` uses `@JmixEntity`, `@JmixGeneratedValue` from `io.jmix.core`
- `WorkOrderRepository` extends `io.jmix.core.repository.JmixDataRepository`
- Removing `io.jmix.*` breaks compilation immediately

This violates **ADR-008** (generated code must remain editable without tool lock-in).

### Q7 — Tool versions recorded

| Tool | Version |
|---|---|
| Jmix Gradle plugin / BOM | **3.0.1** |
| Jmix Studio (IntelliJ plugin) | **3.0.2-261** |
| JDK used for spike | **Temurin 21.0.12.1** |
| IntelliJ IDEA | **2026.1.2** |

---

## Spike Details — OpenAPI Generator (T-09 / SCRUM-38)

**Version:** `openapi-generator-maven-plugin` **7.16.0** · Generator: `spring`
**Spike owner:** M3 (Esraa Abdelrazek) · **Spike date:** 2026-08
**Config:** `useSpringBoot4=true`, `interfaceOnly=true`, `useJakartaEe=true`
**Template override:** `src/main/resources/templates/openapi/spring/responseType.mustache` (1 file, 62 bytes)

### Q1 — What exactly does OpenAPI Generator generate?

From a manual OpenAPI spec for the WorkOrder API:

| Artifact | Location | Notes |
|---|---|---|
| `WorkOrderApi.java` | `target/generated-sources/openapi/.../api/` | Spring interface, `@Validated`, `@RequestMapping`, `@Operation` |
| `WorkOrderDto.java` | `target/generated-sources/openapi/.../dto/generated/` | Jakarta-validated POJO |
| `CreateWorkOrderRequest.java` | Same | Jakarta-validated POJO |
| `UpdateWorkOrderRequest.java` | Same | Jakarta-validated POJO |
| `WorkOrderStatus.java` | Same | Java `enum` |

**Not generated:** `@RestController` implementation, `ApiResponse<T>` envelope, repository, service, security, audit.

### Q2 — How does OpenAPI Generator store metadata?

OpenAPI Generator reads a developer-authored `.yaml` spec (`work-order-api.yaml`) at build time via the Maven plugin. The spec is the metadata — no separate runtime metamodel. Generated code lives in `target/generated-sources/` only (not committed).

### Q3 — Is the generated output editable by hand?

**Yes, fully.** Generated interfaces in `target/generated-sources/` are standard Java. The developer implements them in hand-written `@RestController` classes in `src/main/java/`. No tool required at runtime.

### Q4 — What dependencies does OpenAPI Generator force?

| Dependency | Scope | Why |
|---|---|---|
| `org.openapitools:jackson-databind-nullable:0.2.6` | compile | Generator nullable fields support |

No proprietary runtime framework. Consistent with **ADR-008** (no lock-in).

### Q5 — Does OpenAPI output match our Standards Checklist?

| Checklist ref | Standard | Match? | Evidence |
|---|---|---|---|
| STD-05 | `ResponseEntity<ApiResponse<T>>` return type | ✅ (with override) | `responseType.mustache` override (1 line) achieves full conformance — verified on 2 consecutive `mvn clean compile` runs |
| STD-01–04 | Five-field `ApiResponse<T>` envelope | ⚠️ Gap | Interface only — developer fills envelope in `@RestController` impl |
| STD-10 | Validation errors → 400 | ✅ | Jakarta `@Valid` annotations generated |
| STD-27 | Swagger/OpenAPI coverage | ✅ | `@Operation`, `@ApiResponse`, `@Parameter` generated |
| STD-28 | HTTP status codes (201 POST / 204 DELETE) | ✅ | Declared in OpenAPI YAML `responses:` blocks |
| ADR-001 | Java 21 + Jakarta EE | ✅ | `useJakartaEe=true`, confirmed `javac release 21` |
| ADR-001 | Spring Boot 4 compatible | ✅ | `useSpringBoot4=true`, generator 7.16.0 |
| ADR-008 | No runtime lock-in | ✅ | Zero proprietary runtime dependency |
| ADR-007 | Compiles deterministically | ✅ | `BUILD SUCCESS` on 2 consecutive `mvn clean compile` runs |

### Q6 — If generator plugin is removed, would generated code still compile?

**Yes**, provided generated sources are committed or regenerated before build. The plugin is a build tool, not a runtime dependency. Implementing `@RestController` classes depend only on Spring Boot and `jackson-databind-nullable`.

### Q7 — Tool versions recorded

| Tool | Version |
|---|---|
| `openapi-generator-maven-plugin` | **7.16.0** |
| Generator name | `spring` |
| Spring Boot | **4.0.8** |
| Java | **21** (Jakarta EE) |
| Template override | `responseType.mustache` (1 line, 62 bytes) |
