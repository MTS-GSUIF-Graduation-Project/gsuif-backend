# Capability Matrix
> Owner: M2 (Alaa — Jmix spike) + M3 (Nancy — OpenAPI spike)
> Source: T-08 (SCRUM-37) and T-09 (SCRUM-38)
> Status: Jmix spike COMPLETE — awaiting M4 review

This matrix records the evaluation of candidate tools against each backend component.
A component uses a tool only if: Available ✓ AND Generated ✓ AND Editable ✓ AND Standard-compliant ✓.
Otherwise the decision is REJECT and the fallback is a FreeMarker template (already the Phase 1 plan).

---

## How to Read This Matrix

| Column | Meaning |
|---|---|
| Tool | Tool name + version evaluated |
| Available? | Does the tool support this component? |
| Generated? | Does it actually produce output for it? |
| Editable? | Can the output be modified and built without the tool? |
| Standard-compliant? | Does output match `standards-checklist.md`? |
| Dependencies | Runtime libs the generated code requires |
| Decision | ACCEPT (use tool) or REJECT (use FreeMarker template) |

---

## Tool: Jmix Studio
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
1. **Java annotations as metamodel** — `@JmixEntity`, `@JmixGeneratedValue`, plus JPA annotations on entity classes (this is the primary “metadata”).
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

## Tool: OpenAPI Generator
*(T-09 — to be filled by M3 Nancy)*

---

## Overall Summary

| Tool | Overall Verdict | Components Accepted | Notes |
|---|---|---|---|
| Jmix | **REJECT as generation foundation** | **None** for GSUIF codegen | Keep as optional study/reference tool only. Phase 1 generation remains **FreeMarker / TemplateOnlyProvider** (ADR-003, ADR-009). |
| OpenAPI Generator | *(pending T-09)* | — | — |

### Recommendation (T-08)

**REJECT Jmix for producing GSUIF output.**  
Reasons: runtime lock-in to `io.jmix.*`, EclipseLink instead of Hibernate, Vaadin UI instead of Angular, no `ApiResponse` / company package layout.  
Fallback already planned: FreeMarker templates derived from the hand-written reference implementation.
