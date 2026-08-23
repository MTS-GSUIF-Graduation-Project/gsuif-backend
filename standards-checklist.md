# 📋 GSUIF Standards Checklist

**Ticket:** SCRUM-35 (T-06) · **Blocked by:** SCRUM-43 (T-03 — Governance Files)
**Source:** Technical Document Basic Architecture v0.1 (Marina Melad)
**Corrected against:** DECISIONS.md & QUESTIONS.md (V3.2, approved)
**Owner:** M1 — Sondos Hashem · **Reviewer:** M3 — Esraa Abdelrazek

> **Why this file exists:** every artifact the framework produces — hand-written or AI/template-generated — gets checked against this document. If it's not here, it's not a standard. If it's here, it must be pass/fail testable, not a matter of opinion.

---

## 🔑 How to Read Every Item

Each requirement follows the same structure, so you never have to guess what's being asked:

| Field | Meaning |
|---|---|
| **🎯 Mandatory** | Required for Phase 1 sign-off, or conditional/deferred? |
| **🛠️ What to Implement** | The concrete technical action — no ambiguity |
| **🔍 How It's Checked** | The exact pass/fail test a reviewer runs |
| **⚠️ Exceptions** | Where this rule doesn't apply, or is currently open |
| **📎 Context** | Why it matters / what it corrects from the original doc |

### Scope Tags (who this applies to)

| Tag | Meaning |
|---|---|
| 🟧 **GEN** | Applies to **AI/template-generated code only** |
| 🟦 **SYS** | Applies to **our own framework/system code only** |
| 🟪 **BOTH** | Applies to **generated code and framework code** |

### Mandatory Status

| Status | Meaning |
|---|---|
| 🟢 **MANDATORY** | Required for Phase 1 sign-off — no exceptions |
| 🟡 **DEFERRED** | Confirmed out of *current* scope — do only if time allows, not a blocker |
| 🔴 **BLOCKED / OPEN** | Cannot be finalized yet — tracked in QUESTIONS.md |
| ⚫ **EXCLUDED** | Confirmed permanently out of scope for this framework |

---

## 🚨 Corrections to the Original Technical Document

The Technical Document predates the team's confirmed architecture decisions. These corrections apply throughout — implement the corrected version, not the struck-through one.

| Original said... | ✅ Corrected to | Source |
|---|---|---|
| ~~Database: Oracle SQL not native Query~~ | Database-agnostic via JPA/Hibernate. **PostgreSQL** = dev/reference DB. **H2 (Postgres-compatible)** = CI. Oracle is **not mandatory**. | ADR-004, OQ-01 |
| ~~Backend: Java 8+ (upgrade to 21+ accepted, WebLogic)~~ | **Java 21** confirmed, no WebLogic dependency | ADR-001 |
| ~~SysConfigService, WLSJmxInterface, JSESSIONID cookie sessions~~ | Legacy **WOMS-specific** items, not GSUIF framework requirements. Framework is **stateless JWT**, not session-based. | ADR-010, OQ-07 (open) |

---

## 1️⃣ Unified API Response Format

### STD-01 — Five-field response envelope
🟪 BOTH · 🟢 MANDATORY

- **🛠️ Implement:** Every REST response body contains exactly five top-level fields: `status`, `clientMessage`, `statusCode`, `body`, `errors`.
- **🔍 Check:** Inspect any response JSON. Fail if a field is missing, renamed, or extra undocumented fields exist.
- **⚠️ Exceptions:** None.
- **📎 Context:** This is the contract every frontend service and generated client relies on — breaking it breaks everything downstream.

### STD-02 — Success shape
🟪 BOTH · 🟢 MANDATORY

- **🛠️ Implement:** `status: "OK"`, `body` populated, `errors: null`.
- **🔍 Check:** Fail if any 2xx response has non-null `errors`, or null `body` when data should exist.

### STD-03 — Error shape
🟪 BOTH · 🟢 MANDATORY

- **🛠️ Implement:** Appropriate `status` string (e.g. `INTERNAL_SERVER_ERROR`), `body: null`, `errors` populated as a field→message map.
- **🔍 Check:** Fail if an error response has non-null `body`, or `errors` is empty on a validation failure.

### STD-04 — Paginated shape
🟪 BOTH · 🟢 MANDATORY

- **🛠️ Implement:** Nest `data`, `totalPages`, `totalElements`, `size`, `number` inside `body`.
- **🔍 Check:** Fail if any paginated endpoint returns a bare array instead of this nested structure.

### STD-05 — Controller return type
🟪 BOTH · 🟢 MANDATORY

- **🛠️ Implement:** All controller methods return `ResponseEntity<ApiResponse<T>>`.
- **🔍 Check:** Fail if any endpoint returns a raw DTO, entity, or primitive.

---

## 2️⃣ Package Layout & Naming

### STD-06 — Backend package layout
🟪 BOTH · 🟢 MANDATORY

- **🛠️ Implement:** `config`, `controller`, `dto`, `entity`, `repository`, `service`, `exception`, `aspect`, `security`, `audit`, `util`.
- **🔍 Check:** Fail if a class lives outside its designated layer (e.g. business logic sitting in `controller`).

### STD-07 — Frontend structure
🟪 BOTH · 🟢 MANDATORY

- **🛠️ Implement:** `core/guards`, `core/interceptors`, `core/services`, `core/models`, `shared`, `features/<feature>`.
- **🔍 Check:** Fail if a reusable service/guard/interceptor is duplicated inside a feature module instead of living in `core`.

### STD-08 — Package naming convention
🟪 BOTH · 🟢 MANDATORY

- **🛠️ Implement:** Pattern `<org>.<project>.<layer>.<module>` (e.g. `mts.nameproject.portal.services`).
- **🔍 Check:** Fail on inconsistent casing or missing project/layer segments.

---

## 3️⃣ Exception Handling

### STD-09 — Global handler
🟪 BOTH · 🟢 MANDATORY

- **🛠️ Implement:** `@RestControllerAdvice` intercepts all exceptions, returns `ApiResponse` error objects.
- **🔍 Check:** Fail if any endpoint can leak a raw stack trace or Spring's default error page.

### STD-10 — Validation errors → 400
🟪 BOTH · 🟢 MANDATORY

- **🛠️ Implement:** `MethodArgumentNotValidException` → HTTP 400, per-field messages in `errors`.
- **🔍 Check:** Fail if a validation failure returns anything other than 400, or `errors` is empty.

### STD-11 — Not found → 404
🟪 BOTH · 🟢 MANDATORY

- **🛠️ Implement:** `ResourceNotFoundException` (or equivalent) → HTTP 404.
- **🔍 Check:** Fail if a missing-resource lookup returns 200 with a null body instead of 404.

### STD-12 — No leaking internals on 500
🟪 BOTH · 🟢 MANDATORY

- **🛠️ Implement:** Unclassified exceptions → HTTP 500, generic client-facing message, no stack trace / SQL / class names in the response.
- **🔍 Check:** Fail if any 500 body contains a stack trace, exception class name, or SQL fragment.

### STD-13 — Every exception is logged
🟪 BOTH · 🟢 MANDATORY

- **🛠️ Implement:** Every caught exception is logged with stack trace before the response returns.
- **🔍 Check:** Fail if an exception reaches the client with no matching log entry.

---

## 4️⃣ Logging

### STD-14 — Externalized logging config
🟦 SYS · 🟢 MANDATORY

- **🛠️ Implement:** Logback with environment-specific config in `logback-spring.xml`.
- **🔍 Check:** Fail if logging behavior is hardcoded in Java rather than externally configured per environment.

### STD-15 — AOP method logging
🟪 BOTH · 🟢 MANDATORY

- **🛠️ Implement:** `@Around("@annotation(Loggable)")` aspect logs entry/exit/execution time.
- **🔍 Check:** Fail if a `@Loggable`-annotated method produces no entry/exit/timing log line.

### STD-16 — Sensitive data masking
🟪 BOTH · 🟢 MANDATORY

- **🛠️ Implement:** Passwords/tokens masked via regex before any log write.
- **🔍 Check:** Fail if a captured log line contains a raw password or JWT/token value.
- **📎 Context:** Highest-priority security item on this list — a single leaked token in logs is a real incident, not a style nitpick.

### STD-17 — HTTP request/response logging
🟦 SYS · 🟢 MANDATORY

- **🛠️ Implement:** A request-logging filter traces incoming requests.
- **🔍 Check:** Fail if a request cannot be correlated to its downstream logs.

---

## 5️⃣ Security

### STD-18 — JWT filter
🟪 BOTH · 🟢 MANDATORY

- **🛠️ Implement:** Filter validates JWT and populates `SecurityContext`.
- **🔍 Check:** Fail if a protected endpoint is reachable without a valid token.

### STD-19 — Dev/test/prod security toggle
🟪 BOTH · 🟢 MANDATORY

- **🛠️ Implement:** Security enforcement is mode-driven — disabled in `dev`, enabled in `test`/`prod`.
- **🔍 Check:** Fail if the toggle doesn't actually change enforcement behavior, or is missing.

### STD-20 — RBAC enforcement
🟪 BOTH · 🟢 MANDATORY

- **🛠️ Implement:** `@PreAuthorize` + role-based `SimpleGrantedAuthority` mapping.
- **🔍 Check:** Fail if a role-restricted endpoint is reachable by a user without that role.

### STD-21 — Login endpoint contract
🟪 BOTH · 🟢 MANDATORY

- **🛠️ Implement:** `/api/auth/login` returns JWT wrapped in the standard `ApiResponse` (STD-01–STD-05).
- **🔍 Check:** Fail if login response breaks the standard envelope.

### STD-22 — WOMS-specific session items
🟦 SYS · ⚫ EXCLUDED

- **⚠️ Exceptions:** `SysConfigService`, `WLSJmxInterface`, cookie/`JSESSIONID` session management are **excluded** from the framework standard — legacy WOMS/WebLogic artifacts, not GSUIF requirements.
- **📎 Context:** Re-included only if OQ-07 resolves "yes" (WOMS becomes the mandatory Phase 2 demo).

---

## 6️⃣ Audit

### STD-23 — JPA Auditing enabled
🟪 BOTH · 🟢 MANDATORY

- **🛠️ Implement:** `@EnableJpaAuditing` + `AuditorAware<String>` resolving current username from `SecurityContextHolder`.
- **🔍 Check:** Fail if `createdBy`/`lastModifiedBy` is null after an authenticated write.

### STD-24 — Audit fields on entities
🟪 BOTH · 🟢 MANDATORY

- **🛠️ Implement:** `@CreatedDate`, `@LastModifiedDate`, `@CreatedBy`, `@LastModifiedBy` on all core entities.
- **🔍 Check:** Fail if any core entity is missing one of the four fields.

### STD-25 — Envers on critical entities
🟪 BOTH · 🟢 MANDATORY

- **🛠️ Implement:** Hibernate Envers enabled on critical/versioned entities.
- **🔍 Check:** Fail if a critical entity has no retrievable revision history after an update.

---

## 7️⃣ API Gateway

### STD-26 — Gateway responsibilities
🟦 SYS · 🟡 DEFERRED

- **🛠️ Implement:** Separate Spring Boot gateway; handles routing, JWT validation, request/response logging, CORS; **no business logic**. *(Only if time remains after core scope is delivered.)*
- **🔍 Check:** N/A — not evaluated for Phase 1 sign-off.
- **⚠️ Exceptions:** Not a blocker. OQ-09 is now **resolved**: the Gateway is deferred, not part of core scope. Revisit only post-MVP.

---

## 8️⃣ API Documentation & Status Codes

### STD-27 — Swagger/OpenAPI coverage
🟪 BOTH · 🟢 MANDATORY

- **🛠️ Implement:** Swagger/OpenAPI UI exposed for every controller.
- **🔍 Check:** Fail if any endpoint is missing from the generated spec.

### STD-28 — HTTP status code usage
🟪 BOTH · 🟢 MANDATORY

- **🛠️ Implement:** 200 (GET/PUT/PATCH/DELETE success), 201 (POST creates a resource), 400/401/403/404/500 per Section 3.
- **🔍 Check:** Fail if a creating POST returns 200 instead of 201, or any code deviates from this table.

---

## 9️⃣ Frontend Cross-Cutting Concerns

### STD-29 — Auth interceptor
🟪 BOTH · 🟢 MANDATORY

- **🛠️ Implement:** `AuthInterceptor` attaches JWT to `Authorization` header on every outgoing request.
- **🔍 Check:** Fail if any authenticated call is missing the header.

### STD-30 — Error interceptor
🟪 BOTH · 🟢 MANDATORY

- **🛠️ Implement:** `ErrorInterceptor` catches HTTP errors, triggers `NotificationService`, forces logout + redirect on 401.
- **🔍 Check:** Fail if a 401 doesn't force logout/redirect.

### STD-31 — Loading interceptor
🟪 BOTH · 🟢 MANDATORY

- **🛠️ Implement:** `LoadingInterceptor` toggles a global spinner based on pending request count.
- **🔍 Check:** Fail if the spinner disappears before all concurrent requests complete.

### STD-32 — Response unwrapping
🟪 BOTH · 🟢 MANDATORY

- **🛠️ Implement:** Services unwrap `ApiResponse<T>`, throw on `errors`, never expose the raw envelope to components.
- **🔍 Check:** Fail if any component consumes the raw envelope directly.

---

## 🔟 Database & Persistence

### STD-33 — Database-agnostic persistence
🟪 BOTH · 🟢 MANDATORY

- **🛠️ Implement:** Spring Data JPA/Hibernate only; **no native queries** in framework core.
- **🔍 Check:** Fail if any core repository uses `nativeQuery = true` or vendor-specific SQL.
- **📎 Context:** Corrects the original doc's Oracle-only framing — see Corrections table above.

### STD-34 — Reference DB / CI DB
🟦 SYS · 🟢 MANDATORY

- **🛠️ Implement:** PostgreSQL for dev/reference; H2 (Postgres-compatible mode) for CI/tests.
- **🔍 Check:** Fail if CI runs a different dialect than declared, or vendor-specific types/sequences appear in the schema.

### STD-35 — UUID identifier strategy
🟪 BOTH · 🟢 MANDATORY

- **🛠️ Implement:** UUID primary keys for project entities (not auto-increment).
- **🔍 Check:** Fail if a core entity uses a non-UUID key without documented justification.

---

## 1️⃣1️⃣ Explicitly Deferred (Not Blockers)

These are **confirmed not part of core Phase 1 scope**. They're listed here so nobody accidentally builds against them or treats their absence as a gap.

| Item | Status | Note |
|---|---|---|
| GraphQL Generator (schema/queries/mutations/subscriptions) | 🟡 DEFERRED | OQ-03 resolved: optional, not mandatory |
| API Gateway (STD-26) | 🟡 DEFERRED | OQ-09 resolved: do only if time remains |
| Advanced/Dynamic Authorization (entity-level, field-level, policy engine) | 🟡 DEFERRED | Basic RBAC (STD-20) remains mandatory |
| Password Reset + Email/SMS integrations (Twilio/SMTP) | 🟡 DEFERRED | OQ-10 resolved: not a core blocker |
| Redis / Distributed Metadata Cache | 🟡 DEFERRED | Consistent with existing DEC-011 |
| AI Generation Provider (OpenAI/Claude/etc. as generation path) | 🟡 DEFERRED | Consistent with ADR-002/ADR-003 — TemplateOnlyProvider is the Phase 1 path |
| Full Frontend/UI deliverables (Angular component library, dynamic form engine, theme engine, config framework, API model generator, UI starter kit) | 🟡 DEFERRED | Project is backend/metadata-first; frontend follows once the generation pipeline is proven |

## 1️⃣2️⃣ Not Part of Current Scope

| Item | Status | Note |
|---|---|---|
| Multi-tenancy implementation | ⚫ EXCLUDED | Consistent with existing DEC-012 |
| Oracle-specific implementation | ⚫ EXCLUDED | Superseded by ADR-004 — PostgreSQL is the reference DB |
| WebLogic / JSESSIONID / WOMS legacy infrastructure | ⚫ EXCLUDED | See STD-22 and Corrections table above |

---

## 🟡 Open Items Still Requiring Clarification

| ID | Question | Impact if resolved |
|---|---|---|
| OQ-04 | Can generated code depend on Jmix libraries? | Directly affects STD-33 |
| OQ-05 | What defines a passing Phase 1 submission? | May tighten pass/fail thresholds above |
| OQ-07 | Must WOMS be the formal Phase 2 demonstration app? | Would reopen STD-22 |

> ✅ **Resolved since last version:** OQ-02 (in-scope deliverables), OQ-03 (GraphQL), OQ-09 (API Gateway), OQ-10 (password reset) — see Section 11 above and updated QUESTIONS.md.

---

## ✅ Adoption Checklist

- [ ] Reviewed by M3 (Esraa Abdelrazek)
- [ ] Adopted by all four members at Week 1 sync
- [ ] Merged to `develop` via PR
