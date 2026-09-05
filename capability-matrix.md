# 📊 Capability Matrix — Code Generation & Scaffolding Tools

**Owner:** M2 (Alaa — Jmix spike / T-08) + M3 (Esraa — OpenAPI spike / T-09)
**Reviewer:** Team
**Source of truth for standards:** `standards-checklist.md` (T-06 / SCRUM-35)
**Related decisions:** `DECISIONS.md` — ADR-003, ADR-008, ADR-009, DEC-021, DEC-022

> Every claim in this document that references a standard (STD-xx) or a decision (ADR-xxx / DEC-xxx)
> is traceable to the exact item in `standards-checklist.md` or `DECISIONS.md`.
> No new standard numbers are introduced here.

---

## How to Read This Matrix

Each spike is evaluated against the same set of criteria derived directly from the
GSUIF architecture standards and the T-08 / T-09 acceptance criteria.

**Column meanings:**

| Column | Meaning |
|---|---|
| Tool | Tool name + version evaluated |
| Available? | Does the tool support this component? |
| Generated? | Does it actually produce output for it? |
| Editable? | Can the output be modified and built without the tool? |
| Standard-compliant? | Does output match `standards-checklist.md`? |
| Dependencies | Runtime libs the generated code requires |
| Decision | ACCEPT (use tool) or REJECT (use FreeMarker template) |

**Verdict legend:**

| Symbol | Meaning |
|---|---|
| ✅ Pass | Criterion fully met |
| ⚠️ Partial | Criterion partially met or requires additional work |
| ❌ Fail | Criterion not met — blocking concern |
| — | Not applicable to this tool |

---

## Comparative Capability Matrix

| Evaluation Criterion | Standard Ref | Jmix Spike (T-08) | OpenAPI Generator 7.16.0 Spike (T-09) | FreeMarker `TemplateOnlyProvider` (Phase 1 Engine) |
|---|---|---|---|---|
| **Compiles out of the box?** | ADR-007 | ✅ Yes | ✅ Yes (`jackson-databind-nullable:0.2.6` required) | ✅ Yes |
| **Generated code is editable** | ADR-008 | ❌ Tightly bound to Jmix Studio runtime | ✅ Yes — implements generated interface in own `@RestController` | ✅ Yes — unconstrained source templates |
| **No proprietary framework lock-in** | ADR-008 | ❌ Proprietary runtime & Studio dependency | ✅ Yes — zero runtime lock-in | ✅ Yes |
| **Controller return type `ResponseEntity<ApiResponse<T>>`** | STD-05 | ❌ Not conformant | ⚠️ Gap by default; conformance achieved via 1-file custom template override (`responseType.mustache`) | ✅ Exact — template-controlled |
| **Five-field response envelope** | STD-01 | ❌ Not conformant | ⚠️ Not generated — must be implemented by the developer in the `@RestController` | ✅ Template-controlled |
| **UUID identifier strategy** | STD-35 | — | ✅ `format: uuid` in YAML generates `UUID` Java type | ✅ Template-controlled |
| **Swagger/OpenAPI coverage** | STD-27 | — | ✅ Generates full `@Operation` / `@ApiResponse` annotations | ✅ Template-controlled |
| **HTTP status code usage (201 POST / 204 DELETE)** | STD-28 | — | ✅ Configured via OpenAPI YAML `responses:` blocks | ✅ Template-controlled |
| **Database-agnostic persistence** | STD-33 | ❌ Oracle-native Jmix Studio | — (not a persistence-layer generator) | ✅ |
| **Jakarta EE (not javax)** | ADR-001 | ❌ Legacy javax | ✅ `useJakartaEe=true` | ✅ |
| **Java 21 compatible** | ADR-001 | ⚠️ Uncertain | ✅ Confirmed — `javac release 21` | ✅ |
| **Spring Boot 4 compatible** | ADR-001 | ❌ Not confirmed | ✅ `useSpringBoot4=true`, generator version 7.16.0 | ✅ |
| **No additional runtime dependencies** | ADR-008 | ❌ Large Jmix runtime | ⚠️ One extra: `org.openapitools:jackson-databind-nullable:0.2.6` | ✅ None |
| **Deterministic clean generation** | ADR-007 | — | ✅ Two consecutive `mvn clean compile` runs — identical output | ✅ |
| **`interfaceOnly=true` — no impl generated** | ADR-008 | ❌ Opinionated full-stack | ✅ Configured via `<interfaceOnly>true</interfaceOnly>` | — |
| **Spike recommendation** | ADR-009 | **REJECTED** | **ACCEPTED WITH LIMITATIONS** | **CONFIRMED — Phase 1 engine** |

---

## Tool: Jmix Studio (T-08 / SCRUM-37)

**Version evaluated:** Jmix framework / Gradle plugin **3.0.1** · Jmix Studio IntelliJ plugin **3.0.2-261** · Java **21.0.12.1**
**Spike owner:** M2 (Alaa Elmasry)
**Spike date:** 2026-08-21
**Branch:** `feature/SCRUM-37-jmix-capability-spike`
**Spike project (local only, not in this repo):** `D:\jmix-spike-test`

### Q1 — What exactly does Jmix generate?

For creating a `WorkOrder` JPA entity (with audit traits + data repository), Jmix Studio produced:

| Artifact | Path / notes |
|---|---|
| JPA entity class | `.../entity/WorkOrder.java` — `@JmixEntity`, `@Entity`, `@Table`, UUID id, fields, audit fields, `@Version`, getters/setters |
| Data repository | `.../repository/WorkOrderRepository.java` — extends `JmixDataRepository<WorkOrder, UUID>` |
| Repository config stub | `JmixDataRepositoryConfiguration.java` (created with first repository) |

The starter project (before our entity) already included: `User` entity, security roles/config, Vaadin/FlowUI views (login, main, user list/detail), Liquibase changelogs, Gradle build with many `io.jmix.*` starters.

**Not generated for WorkOrder in this spike:** Spring MVC `@RestController`, company `ApiResponse` DTOs, Hibernate Envers history tables, Angular code.

### Q2 — How does Jmix store its own metadata?

Jmix does **not** use a separate JSON metadata file like GSUIF.

Observed storage mechanisms:
1. **Java annotations as metamodel** — `@JmixEntity`, `@JmixGeneratedValue`, plus JPA annotations on entity classes (this is the primary "metadata").
2. **Liquibase XML** under `src/main/resources/.../liquibase/` for DB schema (e.g. `changelog.xml`, `010-init-user.xml`).
3. **Runtime metamodel** loaded by Jmix core from the annotated classes (framework-owned), not an editable standalone JSON schema.

### Q3 — Is the generated output editable by hand?

**Yes, as source files.** `WorkOrder.java` is normal editable Java; the Designer and Text views stay in sync.

**But:** editability does **not** mean tool-independence. The code is written against Jmix APIs (`@JmixEntity`, `JmixDataRepository`). You can edit it inside a Jmix project; you cannot treat it as plain Spring Boot that runs after removing Jmix.

### Q4 — What dependencies does it force into generated code?

From `build.gradle` (Jmix **3.0.1** BOM):

- `io.jmix.core:jmix-core-starter`
- `io.jmix.data:jmix-eclipselink-starter` (**EclipseLink**, not Hibernate)
- `io.jmix.security:jmix-security-starter` (+ flowui / data variants)
- `io.jmix.flowui:jmix-flowui-*` (Vaadin-based UI)
- `io.jmix.localfs`, `io.jmix.datatools-*`
- `org.springframework.boot:spring-boot-starter-web`
- `com.vaadin:vaadin-dev`
- Runtime DB for spike: `org.hsqldb:hsqldb`

Entity/repository code also **compile-depends** on: `io.jmix.core.*`, `io.jmix.core.repository.JmixDataRepository`.

### Q5 — Does the output match our Standards Checklist?

| Standard | Match? | Evidence |
|---|---|---|
| `ApiResponse<T>` unified JSON | ❌ | No company response wrapper generated |
| Package layout `com.company.gsuif/...` | ❌ | Uses `com.company.jmixspiketest` + Jmix layout |
| Spring Data JPA + Hibernate | ❌ | Uses **EclipseLink** via `jmix-eclipselink-starter` |
| `@RestControllerAdvice` + mapped errors | ❌ | Not produced for WorkOrder |
| Logback + `@Loggable` AOP | ❌ | Not in generated WorkOrder path |
| JWT + company security mode toggle | ❌ | Jmix security model / FlowUI login instead |
| JPA auditing `@CreatedBy` etc. | ✅ partial | Present on `WorkOrder` |
| UUID primary keys | ✅ | Matches ADR-005 |
| Swagger/springdoc | ❌ | Not the company Swagger setup |
| Angular frontend | ❌ | Vaadin / Jmix FlowUI |

**Overall: not standards-compliant** for GSUIF generated output.

### Q6 — If Jmix libraries were removed, would the generated code still compile?

**No.**

- `WorkOrder` uses `@JmixEntity`, `@JmixGeneratedValue` from `io.jmix.core`.
- `WorkOrderRepository` extends `io.jmix.core.repository.JmixDataRepository`.
- Removing `io.jmix.*` dependencies breaks compilation immediately.

This violates ADR-008 / company editable + no lock-in requirement for **generated GSUIF artifacts**.

### Q7 — Tool version recorded?

| Tool | Version |
|---|---|
| Jmix Gradle plugin / BOM | **3.0.1** |
| Jmix Studio (IntelliJ plugin) | **3.0.2-261** |
| JDK used for spike | **Temurin 21.0.12.1** |
| IntelliJ IDEA | **2026.1.2** |

---

### Component Matrix — Jmix

| Component | Available? | Generated? | Editable? | Standard-compliant? | Dependencies | Decision | Rationale |
|---|---|---|---|---|---|---|---|
| Entity (JPA) | ✓ | ✓ | ✓ (with Jmix) | ❌ | `io.jmix.core`, EclipseLink | **REJECT** | Useful as learning reference only; not company package/ApiResponse/Hibernate |
| DTO | ✗ / weak | ✗ for WorkOrder | — | ❌ | — | **REJECT** | No company DTO/`ApiResponse` generation observed |
| Repository | ✓ | ✓ | ✓ (with Jmix) | ❌ | `JmixDataRepository` | **REJECT** | Not Spring Data JPA repository style from Tech Doc |
| Service | ✗ for this flow | ✗ | — | ❌ | — | **REJECT** | No service layer generated for WorkOrder |
| Controller (REST) | ✗ for this flow | ✗ | — | ❌ | — | **REJECT** | FlowUI views, not Spring MVC + ApiResponse |
| Exception Handler | framework-owned | ✗ as our artifact | — | ❌ | Jmix/Spring | **REJECT** | Not GlobalExceptionHandler pattern |
| JWT Security | different model | starter security | — | ❌ | jmix-security-* | **REJECT** | Not our JWT filter / ApiResponse login contract |
| Audit | ✓ | ✓ | ✓ (with Jmix) | ⚠ partial | Spring Data auditing annotations | **REJECT for generator** | Annotations are familiar, but still inside Jmix runtime |

---

### Verdict (T-08)

**REJECT Jmix for producing GSUIF output.**
Reasons: runtime lock-in to `io.jmix.*`, EclipseLink instead of Hibernate, Vaadin UI instead of Angular, no `ApiResponse` / company package layout.
Fallback already planned: FreeMarker templates derived from the hand-written reference implementation.

> See **DEC-021** in `DECISIONS.md` for the confirmed team decision.

---

## Tool: OpenAPI Generator (T-09 / SCRUM-38)

**Version evaluated:** `openapi-generator-maven-plugin:7.16.0`
**Spike owner:** M3 (Esraa Abdelrazek)
**Branch:** `feature/SCRUM-38-openapi-generator`
**Standards referenced:** STD-01, STD-02, STD-03, STD-05, STD-27, STD-28, STD-33, STD-35, ADR-001, ADR-003, ADR-007, ADR-008, ADR-009

### Environment

| Item | Value |
|---|---|
| Plugin | `openapi-generator-maven-plugin:7.16.0` |
| Generator | `spring` |
| Spring Boot | `4.0.8` |
| Java | `21` |
| Jakarta EE | Yes (`useJakartaEe=true`) |
| Config flags | `useSpringBoot4=true`, `interfaceOnly=true` |
| Template override | `src/main/resources/templates/openapi/spring/responseType.mustache` (1 file, 62 bytes) |
| OpenAPI spec | `src/main/resources/openapi/work-order-api.yaml` |

---

### What Is Generated (Empirically Verified)

| Artifact | Location | Notes |
|---|---|---|
| `WorkOrderApi.java` | `target/generated-sources/openapi/.../api/` | Spring interface, `@Validated`, `@RequestMapping`, `@Operation` |
| `WorkOrderDto.java` | `target/generated-sources/openapi/.../dto/generated/` | Jakarta-validated POJO |
| `CreateWorkOrderRequest.java` | Same | Jakarta-validated POJO |
| `UpdateWorkOrderRequest.java` | Same | Jakarta-validated POJO |
| `WorkOrderStatus.java` | Same | Java `enum` |

---

### STD-05 Compliance: Generated Return Types (Empirically Verified)

All five `WorkOrderApi` methods produce `ResponseEntity<eg.mts.gsuif.dto.ApiResponse<T>>`
after the `responseType.mustache` override. Confirmed by two consecutive `.\mvnw.cmd clean compile` runs.

| Method | HTTP | Generated Return Type | STD-05 |
|---|---|---|---|
| `createWorkOrder` | `POST /work-orders` | `ResponseEntity<eg.mts.gsuif.dto.ApiResponse<WorkOrderDto>>` | ✅ |
| `deleteWorkOrder` | `DELETE /work-orders/{id}` | `ResponseEntity<eg.mts.gsuif.dto.ApiResponse<Void>>` | ✅ |
| `getWorkOrderById` | `GET /work-orders/{id}` | `ResponseEntity<eg.mts.gsuif.dto.ApiResponse<WorkOrderDto>>` | ✅ |
| `getWorkOrders` | `GET /work-orders` | `ResponseEntity<eg.mts.gsuif.dto.ApiResponse<List<WorkOrderDto>>>` | ✅ |
| `updateWorkOrder` | `PUT /work-orders/{id}` | `ResponseEntity<eg.mts.gsuif.dto.ApiResponse<WorkOrderDto>>` | ✅ |

**Note on Swagger naming collision:** `io.swagger.v3.oas.annotations.responses.ApiResponse` (annotation)
is imported by the default `api.mustache` template. Using the fully-qualified
`eg.mts.gsuif.dto.ApiResponse` in `responseType.mustache` eliminates Java compiler ambiguity
without any additional import or workaround.

---

### STD-01 Gap (Not Closed by the Generator)

The five-field response envelope (`status`, `clientMessage`, `statusCode`, `body`, `errors` — **STD-01**)
is not produced by OpenAPI Generator. The generator scaffolds the interface signature only.
The developer implementing the `@RestController` is responsible for constructing a properly
populated `ApiResponse<T>`, consistent with **STD-02** (success shape) and **STD-03** (error shape).

This is expected and acceptable for a `interfaceOnly=true` contract-first scaffolding tool.

---

### Dependency Introduced

| Dependency | Scope | Required by |
|---|---|---|
| `org.openapitools:jackson-databind-nullable:0.2.6` | `compile` | Generator nullable fields support |

No proprietary runtime framework or vendor-locked library. Consistent with **ADR-008**.

---

### Template Customization: Approaches Compared (Empirically Verified)

| Approach | File(s) in Repo | Verified? | Notes |
|---|---|---|---|
| Full `api.mustache` override | 1 file (~290 lines) | ✅ Verified (initial POC) | High maintenance — strong version coupling |
| `responseType.mustache`-only override | **1 file (1 line, 62 bytes)** | ✅ **Verified (adopted approach)** | `api.mustache` inherited from JAR; minimal coupling |
| Built-in `<responseWrapper>` config option | 0 custom files | ❌ Not viable | Produces `ApiResponse<ResponseEntity<T>>` — inverted hierarchy |

**Adopted approach:** `responseType.mustache`-only override. `api.mustache` is absent from the
repository and is resolved from the generator JAR at build time.

---

### Verdict (T-09 Acceptance Criteria)

| Acceptance Criterion | Result |
|---|---|
| Generated Spring interfaces compile | ✅ `BUILD SUCCESS` — Java 21, Spring Boot 4.0.8 |
| Gap between generated output and `ApiResponse` standard documented | ✅ See STD-01 Gap and STD-05 table above |
| Plugin version recorded | ✅ `openapi-generator-maven-plugin:7.16.0` |
| ACCEPT / REJECT recommendation with rationale | ✅ **ACCEPTED WITH LIMITATIONS** — see `DECISIONS.md` DEC-022 |

> See **DEC-022** in `DECISIONS.md` for the confirmed team decision.

---

## Overall Summary

| Tool | Overall Verdict | Components Accepted | Notes |
|---|---|---|---|
| Jmix (T-08) | **REJECT as generation foundation** | **None** for GSUIF codegen | Keep as optional study/reference tool only. Phase 1 generation remains **FreeMarker / TemplateOnlyProvider** (ADR-003, ADR-009). Decision → DEC-021. |
| OpenAPI Generator 7.16.0 (T-09) | **ACCEPTED WITH LIMITATIONS** | Contract-first REST scaffolding + Phase 2 client SDK | Not the Phase 1 engine. FreeMarker remains Phase 1. Decision → DEC-022. |
| FreeMarker `TemplateOnlyProvider` | **CONFIRMED — Phase 1 engine** | All Phase 1 generation | ADR-003. |
