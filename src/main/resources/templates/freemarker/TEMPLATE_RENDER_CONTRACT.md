# GSUIF FreeMarker Template Render Contract v0.1
<!-- SCRUM-44 / T-40 · Part 1: Template Foundation & Catalog Support -->
<!-- Owner: M3 — Esraa Abdelrazek | Reviewer: M1 — Sondos Hashem, M4 — Nancy -->
<!-- Status: PROVISIONAL — fixture verified; production integration remains planned -->

> **This document is provisional and SCRUM-44 remains in progress.** The `.ftl` files
> render narrow, explicit fixtures. They do not implement GenerationContext or the
> SCRUM-28 provider. The AssetTicket controller is verified against one generated
> OpenAPI interface; general API-binding translation is outside this fixture.

---

## 1. Purpose and Scope

This document establishes, for Phase 1 only:

1. Which FreeMarker templates exist, which catalog component IDs they serve, and exactly where they
   live in the repository.
2. The proposed data model that a template render call would receive — every candidate variable,
   its type, its required/optional status, its meaning, and a reference example.
3. Which generation inputs are currently unresolved and must be settled in SCRUM-28.
4. How the engine validates metadata before rendering and what happens when required inputs are absent.
5. The proposed output file path layout.

**What this document does NOT do:**
- Describe the Java implementation of `GenerationContext`, `TemplateOnlyProvider`, or
  `AICodeGenerationProvider`. Those are designed in SCRUM-28.
- Assert that any variable name below is an existing Java field or method.
- Add unconditional annotation rules. Annotations appear in Section 4 with their governing
  standard and an explicit condition.
- Describe catalog parsing, generation orchestration, or the REST export API (BE-14).

**Source of truth:** All tool and template decisions are read from `components.yaml`
(`tool_template_resolution`, `generation` blocks) and `capability-matrix.md`. Anything not
confirmed in those files is flagged as an open alignment item.

---

## 2. Template Inventory — Phase 1

| Template ID | Catalog component | Resource path (classpath) | Catalog strategy |
|---|---|---|---|
| `T-ENTITY` | **BE-02** Metadata Model | `templates/freemarker/entity.ftl` | `FREEMARKER` |
| `T-CONTROLLER` | **BE-05** Automatic CRUD Generator — controller half | `templates/freemarker/controller-crud.ftl` | `OPENAPI_PLUS_FREEMARKER` |

**BE-05 has two catalog template artifacts** (`generation.template.artifacts`), not one:

| Catalog role | Location type | Path | Status |
|---|---|---|---|
| `OPENAPI_OVERRIDE` | `CLASSPATH` | `templates/openapi/spring/responseType.mustache` | DEC-022 Mustache override; extended for the envelope fixture while retaining its default behavior. |
| `CONTROLLER_IMPLEMENTATION` | `CLASSPATH` | `templates/freemarker/controller-crud.ftl` | Executable fixed-shape AssetTicket fixture with one generated-interface integration check; production integration remains `PLANNED`. |

BE-02 has a single `generation.template.path: templates/freemarker/entity.ftl`,
`implementation_status: PLANNED` — the executable fixture exists; metadata integration and
consumer dependency handling are not implemented.

Paths are relative to the classpath root (`src/main/resources` here). A loader rooted there
must retain the `templates/` prefix exactly once. Select BE-05 artifacts by role, not list
position. Loader and catalog parsing implementation belongs to SCRUM-28.

> **Both templates carry `template.version: "0.0.0-planned"` in the catalog.** This is a
> placeholder recorded at catalog-authoring time, not an implemented version. The actual version
> string remains a placeholder until the catalog decision and integration checks are complete.

> **Phase 1 Angular output.** `components.yaml §phase_1_includes` records "Minimal Angular
> component and Spring REST controller template outputs required by SCRUM-28/T-28." A third
> template for Angular is in scope but its variable contract is entirely unresolved. SCRUM-28
> owns the minimal TS/HTML output contract and template; this SCRUM-44 fixture does not verify
> Angular generation (S28-11). The full Angular application remains Phase 2 work.

---

## 3. Render Variable Contract

### 3.1 Conventions

- Variable paths use dot notation for the template data-model tree.
- **Type** is what the template engine sees in `${...}` and `<#list ...>` directives. Types
  listed here are not claims about existing Java classes.
- **Req?** — `R` = Required (engine must throw before rendering if absent or null, see Section 5);
  `O` = Optional (template must guard with `??` or `!`).
- Where a variable is listed as Required, the applicable failure check depends on its type:
  - `String` — null or blank (zero-length after trim) fails.
  - `List` — null fails; an empty list is valid.
  - `boolean` / `Boolean` — null fails if boxed; a primitive `false` is a valid value and must not
    be treated as absent.
- Examples reference the WorkOrder reference implementation and the metadata schemas in
  `metadata/schema/`.
- Sections 3.3–3.5 describe the proposed full render model. The current fixtures consume
  only the explicit subset in Section 3.6. Proposed variables are not GenerationContext APIs.

---

### 3.2 Top-Level Variables

| Variable | Type | Req? | Description | Example |
|---|---|---|---|---|
| `project` | Object | R | Normalised project context (Section 3.3). | — |
| `entity` | Object | R | Normalised entity descriptor (Section 3.4). | — |
| `api` | Object | R for controller only | Normalised API binding descriptor (Section 3.5). Required for `T-CONTROLLER`; unused by `T-ENTITY`. | — |

> **S28-01 — Root binding decision.** The foundation uses bare `project`, `entity`, and `api`
> maps. SCRUM-28 will adapt GenerationContext to this template-facing model; templates must
> not navigate assumed context getters. Context/builder implementation remains SCRUM-28 work.

---

### 3.3 `project` Variables

Source: `metadata/schema/project.schema.json` plus generation configuration not present in the
schema (see S28-02).

| Variable | Type | Req? | Description | Example |
|---|---|---|---|---|
| `project.projectId` | String (UUID) | R | UUID of the owning GsuifProject. Javadoc use only. | `"3fa85f64-5717-4562-b3fc-2c963f66afa6"` |
| `project.name` | String | R | Project name. `minLength=1, maxLength=200` per schema. | `"Work Order Management"` |
| `project.basePackage` | String | R | Root Java package for generated consumer code. Drives `package` declaration and output path. Must match STD-08 pattern. | `"com.example.woms"` |
| `project.description` | String | O | Project description. Javadoc only. | `"WOMS reference demo."` |

> **S28-02 — `basePackage` source.** `project.schema.json` does not carry a `basePackage` field.
> The source of this value — a project-configuration table column, a generation-request field, or
> a `GenerationContext` field — is unresolved. The template cannot emit a valid `package`
> declaration without it, so it is marked Required pending SCRUM-28 confirmation.

---

### 3.4 `entity` Variables

> **S28-03 — Entity definition is a generation input, not derivable from the snapshot.**
> `component.schema.json` records UI component shape (`type`, `label`, `position`, `size`,
> `visibility`, `disabled`, `fieldKey`). None of these fields individually or collectively define a
> business entity, its Java class name, its table name, or its field list.
>
> - `component.type` is an open, non-enum string identifying a UI component kind
>   (e.g. `"text-field"`, `"select"`). It does not name a business entity.
> - `component.fieldKey` is an optional string mapping a UI component to a field; it is not a
>   field descriptor.
>
> The entity name, Java fields and table name are therefore supplied by an **explicit
> generation specification**, not inferred from UI metadata. SCRUM-28 owns its schema,
> source and adapter. The variables below describe the template-facing values.

The identifier strategy is already fixed: UUID per STD-35 / ADR-005.

| Variable | Type | Req? | Description | Example |
|---|---|---|---|---|
| `entity.className` | String | R | PascalCase Java class name from explicit generation specification. | `"WorkOrder"` |
| `entity.tableName` | String | R | `snake_case` JPA table name from explicit generation specification. | `"work_orders"` |
| `entity.fields` | List | R | List of field descriptors (Section 3.4.1) from explicit generation specification. Null fails; empty list is valid for a stub. | — |

#### 3.4.1 Proposed `FieldDescriptor` items (`entity.fields[n]`)

These sub-variables are template-facing descriptors. SCRUM-28 defines and validates
their explicit generation-specification source.

| Sub-variable | Type | Req? | Description | Example |
|---|---|---|---|---|
| `.name` | String | R | `camelCase` Java field name. | `"orderNumber"` |
| `.columnName` | String | R | `snake_case` DB column name. | `"order_number"` |
| `.javaType` | String | R | Simple or fully-qualified Java type. | `"String"`, `"java.util.UUID"`, `"java.time.LocalDate"` |
| `.nullable` | boolean | R | Whether the column is nullable. `false` is a valid value — must not be treated as absent. Drives `@Column(nullable = ...)`. | `false` |
| `.columnLength` | Integer | O | Column length. Emitted as `@Column(length = ...)` only when present. | `64` |
| `.enumType` | String | O | When present, drives `@Enumerated(EnumType.STRING)`. Simple enum class name. | `"WorkOrderStatus"` |

---

### 3.5 `api` Variables (controller template only)

Source: `metadata/schema/api-binding.schema.json` fields. Only populated for `T-CONTROLLER`.
`T-ENTITY` must not reference `api`.

> **S28-04 — General operation signatures remain SCRUM-28 work.**
> `api-binding.schema.json` records individual bindings (httpMethod, endpointUrl,
> requestMapping, responseMapping). The selected OpenAPI interface owns HTTP routes and
> operation signatures. SCRUM-28 must decide how bindings produce that contract and the
> validated implementation inputs; the fixed fixture makes no route derivation claim.

| Variable | Type | Req? | Description | Example |
|---|---|---|---|---|
| `api.bindings` | List | Proposed for SCRUM-28 | One entry per `APIBinding` (Section 3.5.1). The fixed fixture does not read or validate it and always emits five methods, even when bindings are absent or empty. | — |

#### 3.5.1 Proposed `BindingDescriptor` items (`api.bindings[n]`)

| Sub-variable | Type | Req? | Description | Example |
|---|---|---|---|---|
| `.bindingId` | String (UUID) | R | APIBinding UUID from schema. | `"9b1deb4d-3b7d-4bad-9bdd-2b0d7b3dcb6d"` |
| `.name` | String | O | Optional binding name from schema. | `"listWorkOrders"` |
| `.httpMethod` | String | R | One of `GET`, `POST`, `PUT`, `DELETE` (schema enum). | `"GET"` |
| `.endpointUrl` | String | R | Relative path from schema. | `"/api/v1/work-orders/{id}"` |
| `.requestMapping` | Object | R | `path`, `query`, `body` sub-maps from schema. | — |
| `.responseMapping` | Object | R | `item`, `list`, `pagination` from schema. | — |

### 3.6 Explicit executable fixture inputs

The fixture names below are map keys supplied directly by tests. They are not Java fields,
metadata schema properties, or an approved production request shape. The two templates check
these values before source output. `project.name` and `project.projectId` from the proposed
model are not consumed by these fixtures.

| Template | Consumed input | Fixture constraint |
|---|---|---|
| Entity | `project.basePackage` | Required Java package for generated source |
| Entity | `entity.auditBasePackage` | Required Java package supplying `AuditableEntity` on the consumer compile classpath; emitted as an explicit import |
| Entity | `entity.className`, `entity.tableName`, `entity.fields` | Required class, table and list of field maps; class name cannot shadow supported or imported Java types |
| Entity | `entity.fields[n].name`, `.columnName`, `.javaType`, `.nullable` | Required identifiers, supported type and Boolean. Supported scalar names: `String`, `UUID`, `LocalDate`, `LocalDateTime`, `Integer`, `Long`, `BigDecimal`, `Boolean`; an enum may use a matching simple `enumType` in the generated `.entity` package. |
| Entity | `entity.fields[n].columnLength`, `.enumType` | Optional positive integer/string enum; enum class must be in the `.entity` package and cannot shadow a scalar, imported type, or the generated entity |
| Entity | `entity.enversAudited` | Optional Boolean; explicit `true` adds class-level `@Audited`; absent means false in this fixture |
| Controller | `project.basePackage`, `entity.className` | Required package and class; routes come only from the selected OpenAPI interface |
| Controller | `api.dtoPackage`, `.dtoClass`, `.pageDtoClass`, `.createRequestClass`, `.updateRequestClass` | Required package and distinct names for OpenAPI-generated business DTOs and the fixture page DTO; names cannot collide with controller imports |
| Controller | `api.interfacePackage`, `.interfaceClass` | Required package/name of the OpenAPI-generated Spring interface |
| Controller | `api.createMethod`, `.getMethod`, `.listMethod`, `.updateMethod`, `.deleteMethod` | Required distinct Java method names matching the selected OpenAPI operation IDs; all five methods are emitted |
| Controller | `api.responsePackage` | Required package supplying the shared `ApiResponse` support type on the consumer compile classpath |
| Controller | `api.statusEnumPackage`, `.statusEnumClass` | Required enum package and type for the fixed status filter |
| Controller | `api.servicePackage`, `.serviceInterface` | Required service package/type |

The controller fixture assumes service operations `create(request)`, `getById(UUID)`,
`list(status)`, `update(UUID, request)` and `delete(UUID)`. It generates those
five operations regardless of `api.bindings`, which it does not consume. This verifies a
reference-shaped slice only; arbitrary binding translation remains S28-04. The test uses
matching `AssetTicket` entity, OpenAPI-generated status/business/request/page DTOs,
service and controller types. `AuditableEntity` and `ApiResponse` are existing framework
support types, distinct from generated business DTOs. `PagedBody` is not used by this fixture.

> **STD-05 / shared dependency note.** Every generated controller method must return
> `ResponseEntity<ApiResponse<T>>` (STD-05, BOTH scope). `ApiResponse<T>` lives in
> `eg.mts.gsuif.dto` in the GSUIF framework module. Generated consumer code can only reference
> this class only if its source or dependency is present in the consumer project. The
> approved handoff decision is to export versioned support source, including `ApiResponse`,
> `PagedBody`, and audit support, in SCRUM-28. The current Mustache override hardcodes
> `eg.mts.gsuif.dto.ApiResponse`; SCRUM-28 must preserve that package or revise the
> override and fixture together. Alignment item S28-05.

The fixture makes that dependency explicit: `entity.auditBasePackage` selects the import for
`AuditableEntity`, and `api.responsePackage` selects the import for `ApiResponse`.
The Maven test compiles against those classes already built in this repository. SCRUM-28
owns versioned source export and the consumer's Spring/JPA dependencies. Explicit imports
alone do not deliver support classes.

> **STD-20 / authorization note.** STD-20 (MANDATORY, BOTH scope) requires `@PreAuthorize` +
> role-based `SimpleGrantedAuthority` on restricted endpoints. No `@PreAuthorize` annotation
> exists anywhere in the current codebase. The role expression and its source are **entirely
> unresolved**. No authority or role variable is proposed. Alignment item S28-06.

`components.yaml` assigns basic RBAC infrastructure to BE-07 and AOP logging to BE-10;
their generated-artifact standards still apply when BE-05 output is integrated. SCRUM-28
must establish which generated endpoints are restricted, their approved role expressions,
and when `@Loggable` applies before emitting either annotation. The fixed fixture has no
such policy inputs and makes no authorization or logging conformance claim.

---

## 4. Annotation Rules by Template

Annotations are governed by `standards-checklist.md` and apply conditionally. No annotation is
unconditional unless the standard applies to every artifact of that component type.

### 4.1 Audit — Two Distinct Mechanisms

**Mechanism A — JPA Auditing (STD-23, STD-24)**

`AuditableEntity` carries `@MappedSuperclass` and `@EntityListeners(AuditingEntityListener.class)`.
Any concrete class that `extends AuditableEntity` **automatically inherits** the four JPA audit
fields (`createdAt`, `updatedAt`, `createdBy`, `lastModifiedBy`) and the entity-listener
behaviour. No annotation is required on the subclass.

Evidence: `WorkOrder extends AuditableEntity` — no audit annotations on `WorkOrder` itself; its
audit columns are populated by JPA Auditing.

**Mechanism B — Hibernate Envers revision history (STD-25)**

`AuditableEntity` also carries `@Audited`. On a `@MappedSuperclass`, `@Audited` does **not**
automatically cause subclasses to be Envers-tracked. It means only that the superclass fields are
**included in the audit revision table** of any concrete entity that *itself* declares `@Audited`.

A concrete entity is Envers-audited if and only if it declares its own `@Audited`.

Evidence:
- `GsuifProject` — explicit `@Audited` → Envers-audited.
- `GsuifPage` — explicit `@Audited` → Envers-audited.
- `MetadataVersion` — explicit `@Audited` → Envers-audited.
- `WorkOrder` — no `@Audited` → **not** Envers-audited; JPA audit fields only.

**Entity identity convention.** The generated entity follows the WorkOrder reference:
transient instances are equal only to themselves; persisted instances with the same
non-null UUID compare equal; `hashCode()` uses the entity class constant so it remains
stable when JPA assigns an ID. The AssetTicket fixture compiles and tests this behavior.

### 4.2 Annotation Rule Table

| Annotation | Standard | Scope | Template | Condition |
|---|---|---|---|---|
| `@Entity`, `@Table` | STD-33 | BOTH | T-ENTITY | Always — any generated JPA entity. |
| `@GeneratedValue(strategy = GenerationType.UUID)` | STD-35, ADR-005 | BOTH | T-ENTITY | Always — UUID is the confirmed identifier strategy. |
| `extends AuditableEntity` | STD-23, STD-24 | BOTH | T-ENTITY | Always — inherits JPA audit fields (Mechanism A). |
| `@Audited` on generated entity class | STD-25 | BOTH | T-ENTITY | **Conditional** — only if the entity is designated as requiring Envers revision history (Mechanism B). Not inherited. Which generated entities qualify and how that is signalled are unresolved (S28-07). |
| `@RestController`, `@RequestMapping` | STD-06 | BOTH | T-CONTROLLER | The fixture implementation emits `@RestController`; the generated OpenAPI interface supplies method `@RequestMapping` annotations. |
| `ResponseEntity<ApiResponse<T>>` return type | STD-05 | BOTH | T-CONTROLLER | Always — every controller method. Requires shared-dependency resolution (S28-05). |
| `@Loggable` | STD-15 | BOTH | T-CONTROLLER | **Conditional** — applicability to generated controller methods and the triggering condition are unresolved (S28-08). |
| `@PreAuthorize(...)` | STD-20 | BOTH | T-CONTROLLER | **Unresolved.** STD-20 is MANDATORY. Mechanism, role expression, and source are entirely open (S28-06). |

---

## 5. Validation and Required-Variable Checks

SCRUM-28 must perform these steps before passing the data model to FreeMarker. This is
a validation contract, not a claim that a render pipeline already exists.

### 5.1 Structural Validation (already implemented — BE-16)

`MetadataSchemaValidator` (in `eg.mts.gsuif.validator`) validates the snapshot against the JSON
Schema for `schemaVersion`. SCRUM-28 must invoke validation so invalid snapshots never reach rendering.

### 5.2 Required-Variable Check (engine responsibility — SCRUM-28)

These are adapter requirements, not implemented validation claims. Objects are string-keyed
maps and lists are ordered sequences of the descriptor maps documented above. Validate
recursively: reject null list elements, wrong scalar/container types, and invalid nested values.
Do not coerce strings to booleans/numbers or scalars to lists. Optional null/absent values may
be omitted; present values must satisfy their type and constraints. Required values must
never be silently defaulted with FreeMarker `!`.

In addition to presence checks:

- Validate Java 21 identifiers and applicable keywords/restricted type names. Enforce PascalCase
  class names, camelCase field names and lowercase package segments. Reject empty segments,
  whitespace and path separators. `123Order` and `com..example` fail.
- Resolve `javaType` through an explicit supported scalar/enum type set and the consumer
  compile classpath; reject arbitrary source snippets and unsupported mappings. Resolve imports
  consistently. `enumType`, when supplied, must identify the same enum as the field type.
- Require snake_case table/column names (`[a-z][a-z0-9_]*`) and reject reserved names for
  supported databases, duplicates, and collisions with generated ID/inherited audit fields.
- Require `columnLength` to be an integer in 1..2147483647 and applicable to the field's
  textual column mapping. Boolean `false` remains valid; the string `"false"` fails.
- Validate UUIDs and schema-derived constraints. Request maps contain optional `path`, `query`,
  `body` entries of Map<String,String>; response maps contain optional String `item`, `list`,
  `pagination` entries. Empty maps are valid. Guard optional nested entries in templates.
- Escape Java literals and Javadoc for their respective contexts; descriptions must not end
  comments or inject source. Validate normalized output paths stay beneath the output root.
- Emit Java numeric literals with FreeMarker `?c`, never locale-sensitive interpolation
  (e.g. column length 1000 must remain `1000`, not `1,000`).
- Identify the template, exact indexed variable path and failed constraint in errors, without
  dumping sensitive model values, e.g. `entity.ftl: entity.fields[2].nullable: expected Boolean`.
  Propagate FreeMarker errors and publish buffered output only after successful rendering.

Snapshot schema validation cannot validate separately supplied entity/configuration inputs.
The existing validator must be followed by these render-model checks in SCRUM-28.

For production rendering, the SCRUM-28 adapter must verify every Required variable before
FreeMarker is invoked. The fixture templates themselves guard their consumed subset and use
FreeMarker `#stop` with indexed paths on invalid descriptors. The exception type for the
production adapter is unresolved (S28-10).

Type-specific rules:

| Type | Failure condition | Rationale |
|---|---|---|
| `String` | null or blank (zero-length after trim) | A blank string cannot produce a valid Java identifier or package declaration. |
| `List` | null | An empty list is a valid value (e.g. a stub entity with no fields). |
| `boolean` / `Boolean` | null (boxed only) | `false` is a meaningful value; it must not be treated as absent. |
| `Object` (sub-object) | null | A null sub-object means the whole sub-tree is inaccessible. |

Illustrative failure messages:

| Variable | Failure condition | Message |
|---|---|---|
| `project.basePackage` | null or blank | `"Required render variable 'project.basePackage' is null or blank"` |
| `entity.className` | null or blank | `"Required render variable 'entity.className' is null or blank"` |
| `entity.fields` | null | `"Required render variable 'entity.fields' is null"` |
| `entity.fields[n].nullable` | null (when boxed) | `"Required render variable 'entity.fields[n].nullable' is null"` |

---

## 6. Proposed Output File Path (Provisional — S28-09)

The proposed layout places generated files under a `src/main/java` subtree rooted at a
configurable consumer project directory, mirroring the standard Maven source layout. This is
**provisional**; the exact mechanism must be confirmed in SCRUM-28.

### T-ENTITY proposed path

```
{consumerProjectRoot}/src/main/java/{basePackagePath}/entity/{ClassName}.java
```

| Token | Derivation | Example |
|---|---|---|
| `{consumerProjectRoot}` | Configurable per generation request. Source unresolved (S28-09). | `generated-output/woms` |
| `{basePackagePath}` | `project.basePackage` with `.` replaced by `/`. | `com/example/woms` |
| `entity` | Fixed layer name per STD-06. | `entity` |
| `{ClassName}.java` | `entity.className`. | `WorkOrder.java` |

Full example: `generated-output/woms/src/main/java/com/example/woms/entity/WorkOrder.java`

### T-CONTROLLER proposed path

```
{consumerProjectRoot}/src/main/java/{basePackagePath}/controller/{ClassName}Controller.java
```

Full example: `generated-output/woms/src/main/java/com/example/woms/controller/WorkOrderController.java`

> **BE-19 registry.** After each file is written, `GenerationRun` fields (`toolName`,
> `toolVersion`, `templateVersion`, `generatorVersion`) must be populated from the catalog entry.
> The field-to-catalog mapping is an alignment item with SCRUM-28 / SCRUM-49 (T-31).

---

## 7. Catalog Field Mapping

`components.yaml` `generation` blocks are the source of truth. The table below maps each catalog
field to its render-contract meaning. BE-02 and BE-05 have different `template` structures.

### BE-02 (T-ENTITY) — single `template.path`

| Catalog field | Value | Render contract meaning |
|---|---|---|
| `generation.tool.name` | `"Apache FreeMarker"` | Engine that processes the template. |
| `generation.tool.version` | `"2.3.34"` | Pinned FreeMarker version. Written to `GenerationRun.toolVersion`. |
| `generation.template.path` | `"templates/freemarker/entity.ftl"` | Classpath path of the `.ftl` file. |
| `generation.template.version` | `"0.0.0-planned"` | **Catalog placeholder only**; the fixture is not a released generator template. |
| `generation.implementation_status` | `PLANNED` | Executable fixture exists; integration remains planned. |
| `generation.metadata_fields` | schema paths | Schema fields the template consumes. |

### BE-05 (T-CONTROLLER) — `template.artifacts` list

BE-05 uses `generation.template.artifacts`, not a single `generation.template.path`.

| Catalog artifact role | Path | Render contract meaning |
|---|---|---|
| `OPENAPI_OVERRIDE` | `templates/openapi/spring/responseType.mustache` | Mustache override now uses optional operation-level `x-gsuif-payload-java-type`; operations without it retain the DEC-022 return-type behavior. |
| `CONTROLLER_IMPLEMENTATION` | `templates/freemarker/controller-crud.ftl` | Executable fixed-shape fixture compiled against the AssetTicket OpenAPI interface. |

| Catalog field | Value | Render contract meaning |
|---|---|---|
| `generation.tool.name` | `"openapi-generator-maven-plugin"` | Primary tool (OpenAPI interface scaffold). |
| `generation.tool.version` | `"7.16.0"` | Pinned OpenAPI Generator version. |
| `generation.tool.supplement.name` | `"Apache FreeMarker"` | Secondary tool (controller implementation). |
| `generation.tool.supplement.version` | `"2.3.34"` | Pinned FreeMarker version. |
| `generation.template.version` | `"0.0.0-planned"` | **Catalog placeholder only** — not an implemented version. |
| `generation.implementation_status` | `PLANNED` | One AssetTicket interface/implementation pair is verified; production adaptation and export remain planned. |

---

## 8. Open Alignment Items with SCRUM-28

The executable fixtures and conventions can be reviewed within SCRUM-44 without waiting
for SCRUM-28 implementation. Resolve these integration items before a production handoff;
do not implement a provider, builder, catalog loader or export pipeline in this ticket.
Update this section as decisions are agreed. Human review and CI remain ticket completion gates.

| ID | Item | Section | Impact |
|---|---|---|---|
| S28-01 | Review bare-map proposal and adapt GenerationContext to it in SCRUM-28. | 3.2 | No assumed Java context getters. |
| S28-02 | `project.basePackage` source: project-config table, generation-request field, or GenerationContext field? | 3.3 | Affects what the engine reads before populating the model. |
| S28-03 | Define the explicit generation-specification schema and adapt its entity name, fields and table name. | 3.4, 3.4.1 | Prevent inference from UI component metadata. |
| S28-04 | Convert APIBinding fields into a selected OpenAPI contract and validated implementation inputs. | 3.5 | General routes and signatures are not inferred by this fixture. |
| S28-05 | Export and version `ApiResponse`, `PagedBody`, and audit support source in consumer packages; reconcile the Mustache override's hardcoded response package. | 4.2 | Consumer code needs actual support source and its dependencies. |
| S28-06 | `@PreAuthorize` mechanism: role expression source, render variable, fixed convention, or developer responsibility? STD-20 is MANDATORY. | 4.2 | T-CONTROLLER cannot be declared STD-20-compliant without this. |
| S28-07 | Which generated entity types require explicit `@Audited` (Mechanism B, Section 4.1)? Signalled how? | 4.2 | Determines whether T-ENTITY emits `@Audited` conditionally or never. |
| S28-08 | `@Loggable` applicability to generated controller methods and triggering condition. | 4.2 | Affects T-CONTROLLER output. |
| S28-09 | `consumerProjectRoot` / `exportRoot` configuration mechanism and proposed `src/main/java` layout confirmation. | 6 | Affects output path determination. |
| S28-10 | Exception type for Required-variable failures: `IllegalArgumentException` or a named domain exception? | 5.2 | Affects engine error propagation. |
| S28-11 | Angular component template variable contract (third Phase 1 template from `components.yaml §phase_1_includes`). | 2 | Requires a new Section 3 sub-section once SCRUM-28 confirms output shape. |

---

## 9. SCRUM-44 fixture files and evidence

The repository contains `openapi/asset-ticket-api.yaml`, an opt-in Maven profile
`-Popenapi-fixture`, the two catalog-selected FreeMarker templates, and
`TemplateFoundationTest`. The profile uses OpenAPI Generator 7.16.0 with the same
Spring Boot 4, Jakarta, interface-only, tag, and Mustache override settings as the
existing spike. It places generated sources under `target/generated-sources/openapi-fixture`.
Normal builds do not add the example interface to production sources.

Run `./mvnw -B -Popenapi-fixture -DopenapiFixtureRequired=true
-Dtest=TemplateFoundationTest test` (use `mvnw.cmd` on Windows). CI runs this
command after the normal Maven verify step. The integration test uses catalog
paths, renders an AssetTicket entity and controller, compiles the generated
Spring interface and DTOs with Maven, then compiles the rendered controller and
fixture service against those actual classes with Java 21. It checks the five
interface operation names, Java parameter types, generic response types, HTTP
verbs and URL mappings, including Spring's inherited mapping lookup on the
compiled controller. The interface owns `@RequestMapping`; the implementation
owns `@RestController` and overrides those signatures. The test also checks the
published response schemas and required envelope keys, page DTO properties,
a four-digit entity column length, conditional Envers, invalid nested fields,
scalar-as-enum rejection, and Java reserved-word rejection. It relies on
this repository's `ApiResponse` and `AuditableEntity` classes for compilation.

Local result on 2026-09-30: 8 tests, 0 failures, 0 errors, 0 skipped;
BUILD SUCCESS with the profile and explicit test flag. This is a fixture-level
compatibility result, not a generated consumer project build or deployment.
The separate `OpenApiSpikeFallbackTest`, run with `-Popenapi-spike` and
`-DopenapiSpikeRequired=true`, checks the shared Mustache override's fallback for
all five generated WorkOrder methods, including the list payload. CI runs that
test from a clean target; local result on 2026-09-30: 1 test, 0 failures,
0 errors, 0 skipped. The WorkOrder spike remains an evaluation contract,
not the canonical consumer CRUD contract.

## 10. Handoff limit

The fixed AssetTicket OpenAPI example publishes five-field success-envelope schemas.
Its operation-level `x-gsuif-payload-java-type` supplies the payload type to the
Mustache override, preventing a second `ApiResponse` wrapper in the Java signature.
Operations without that extension retain the prior DEC-022 behavior. The example produces `AssetTicketApi` and DTOs in
`com.example.fixture.api` and `com.example.fixture.dto`. The rendered
`AssetTicketController` implements it, including its `ApiResponse<AssetTicketPage>`
list return. Its five methods are fixed and do not consume `api.bindings`.
The accepted direction is that OpenAPI defines operation and DTO signatures;
SCRUM-28 must adapt `GenerationContext` and explicit entity-generation inputs
into validated `project`, `entity`, and `api` maps. UI component metadata must
not be guessed into business fields. SCRUM-28 also owns catalog selection,
rendering orchestration, versioned export of `ApiResponse`, `PagedBody`, and
audit support source, output-path handling, and artifact persistence.

The current Mustache override hardcodes
`eg.mts.gsuif.dto.ApiResponse`. A consumer export must either retain that package
or change the override, fixture, and import contract together. `PagedBody` is
not exercised by this fixture; its export remains a consumer-support requirement.
No role expression or logging policy has been approved, so the controller emits
neither `@PreAuthorize` nor `@Loggable`. This sample establishes no general CRUD,
arbitrary OpenAPI-contract, role-policy, or consumer-export compatibility.
The catalog remains `PLANNED` until the broader handoff is implemented and reviewed.
