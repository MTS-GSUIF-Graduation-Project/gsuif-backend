# Standards Checklist
> Source: Technical Document Basic Architecture (MTS company standard)
> Owner: M1 (Metadata)
> Status: Draft — to be reviewed by full team at Week 1 sync

This checklist defines every company standard that generated code must comply with.
Used as the conformance benchmark in the Capability Matrix and Golden Tests.

---

## 1. API Response Format

Every REST endpoint MUST return `ApiResponse<T>` with exactly these fields:

| Field | Type | Notes |
|---|---|---|
| `status` | String | HTTP status name e.g. `"OK"`, `"BAD_REQUEST"` |
| `clientMessage` | String | Human-readable summary |
| `statusCode` | int | HTTP status code number |
| `body` | T / Object | Payload; `null` on error |
| `errors` | Map<String,String> | Field-level errors; `null` on success |

**Paginated response** — `body` must contain:
`{ data: [...], totalPages, totalElements, size, number }`

---

## 2. Package Layout

Root package: `com.company.gsuif`

```
com.company.gsuif
├── config/          SecurityConfig, JpaConfig, SwaggerConfig
├── controller/      REST controllers
├── dto/             ApiResponse<T>, request/response DTOs
├── entity/          JPA entities
├── repository/      Spring Data JPA repositories
├── service/         Business logic
├── exception/       Custom exceptions + GlobalExceptionHandler
├── aspect/          LoggingAspect, @Loggable
├── security/        JwtUtil, JwtAuthenticationFilter, UserDetailsServiceImpl
├── audit/           AuditorAwareImpl
├── metadata/        schema/, validator/, versioning/
└── generation/      context/, provider/, output/, registry/
```

Naming convention: `mts.nameproject.portal.services`

---

## 3. Exception Handling

| Exception | HTTP Status | Response body |
|---|---|---|
| `MethodArgumentNotValidException` | 400 | `errors` map populated with field messages |
| `ResourceNotFoundException` | 404 | `clientMessage` set; `errors` null |
| General `Exception` | 500 | Generic "Internal Server Error" message only (no stack trace leak) |

Implementation: `@RestControllerAdvice` on `GlobalExceptionHandler`.

---

## 4. Logging

- Framework: **Logback** with environment-specific `logback-spring.xml`
- AOP aspect: `@Around("@annotation(Loggable)")` logs method entry/exit and execution time
- HTTP logging: `CommonsRequestLoggingFilter` or custom `WebFilter`
- **Sensitive data** (passwords, tokens) must be masked via Regex before logging
- MDC Correlation ID filter: every request tagged with `X-Correlation-ID`

---

## 5. Security

- **Spring Security + JWT** (stateless)
- `JwtAuthenticationFilter` validates token and sets `SecurityContext`
- **Dynamic mode**: security disabled in `dev` profile, enabled in `test` and `prod`
- RBAC via `@PreAuthorize` + `SimpleGrantedAuthority` from DB roles
- Login endpoint: `POST /api/auth/login` → returns `ApiResponse<{ token }>`

---

## 6. Audit

- `@EnableJpaAuditing` on main application class
- `AuditorAwareImpl` returns current username from `SecurityContextHolder`
- Every auditable entity has: `@CreatedDate`, `@LastModifiedDate`, `@CreatedBy`, `@LastModifiedBy`
- Full history on critical entities: **Hibernate Envers** (`@Audited`)

---

## 7. API Documentation

- **Swagger UI** via `springdoc-openapi 2.x`
- JWT Bearer auth scheme configured
- All endpoints annotated
- Swagger disabled in `prod` profile

---

## 8. HTTP Status Codes

| Code | When |
|---|---|
| 200 OK | Successful GET, PUT, PATCH, DELETE |
| 201 Created | Successful POST that creates a resource |
| 400 Bad Request | Validation errors, malformed request |
| 401 Unauthorized | Missing or invalid authentication |
| 403 Forbidden | Authenticated but not authorized |
| 404 Not Found | Resource does not exist |
| 500 Internal Server Error | Unexpected server error |

---

## Checklist — Quick Reference for Conformance Testing

When evaluating any generated artifact, verify each item:

- [ ] Returns `ApiResponse<T>` with all 5 required fields
- [ ] Class is in the correct sub-package under `com.company.gsuif`
- [ ] No native SQL queries
- [ ] `@RestControllerAdvice` present and maps all 3 exception types
- [ ] `@Loggable` annotation used on service methods
- [ ] Audit fields present on all entities (`@CreatedDate` etc.)
- [ ] No hardcoded credentials or tokens
- [ ] Swagger annotation present on all controller methods
