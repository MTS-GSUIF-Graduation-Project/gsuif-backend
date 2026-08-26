# 📊 Capability Matrix — Code Generation & Scaffolding Tools

**Owner:** SCRUM-38 (T-09) — Esraa Abdelrazek
**Reviewer:** M2 — Alaa Elmasry
**Source of truth for standards:** `standards-checklist.md` (T-06 / SCRUM-35)
**Related decisions:** `DECISIONS.md` — ADR-003, ADR-008, ADR-009, DEC-021

> Every claim in this document that references a standard (STD-xx) or a decision (ADR-xxx / DEC-xxx)
> is traceable to the exact item in `standards-checklist.md` or `DECISIONS.md`.
> No new standard numbers are introduced here.

---

## How to Read This Matrix

Each spike is evaluated against the same set of criteria derived directly from the
GSUIF architecture standards and the T-09 / T-08 acceptance criteria.

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

## Detailed Spike Findings: OpenAPI Generator 7.16.0 (T-09 / SCRUM-38)

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
| ACCEPT / REJECT recommendation with rationale | ✅ **ACCEPTED WITH LIMITATIONS** — see `DECISIONS.md` DEC-021 |

---

## Jmix Spike Summary (T-08)

**Verdict: REJECTED** — consistent with ADR-009.

Jmix was evaluated and rejected because:
- Generated code editable only within Jmix Studio — violates **ADR-008**.
- Introduces a proprietary runtime dependency — violates **ADR-008**.
- Uses legacy `javax.*` instead of Jakarta EE — violates **ADR-001**.
- Does not produce the GSUIF unified response envelope — violates **STD-01**, **STD-05**.
- Opinionated full-stack output conflicts with the backend-first metadata pipeline.

Jmix remains eligible for optional evaluation only, as stated in **ADR-009**.
