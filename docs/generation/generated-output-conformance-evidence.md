# SCRUM-50 regression evidence

Baseline: `89138524a456fca9093ea7f90b0c36868f9df141`

## Output under test

SCRUM-50 assertions run against three kinds of output:

1. **Historical evidence: the real final output of a previous generation run.**
   This is a complete `BACKEND` package for `StockUnit`, contract
   `stock-unit-api` 3.0.0, produced by the OpenRouter provider
   (`qwen/qwen3-coder`, `mocked: false`). It is frozen byte-for-byte as
   `src/test/resources/generation/final-output/stock-unit-backend-6b1f398c.zip`
   (SHA-256 `6aebabb622cd9ed4afd9fb79e758a117e4dc8ed328a558b3bbd27a42ab192d18`).
   It is never regenerated or edited; every test re-checks that SHA and its
   `generation-manifest.json` hashes.
2. **Newly generated output from today's generator**, for the same contract
   (regression) and for an unrelated contract, `equipment-loan-api` 1.0.0
   (generality). New packages are tested against the **intended** behavior, not
   against the historical score.
3. **Template-only output** (`GenerationEngine.templateOnly`, `AssetTicket`
   fixture). This is still the only Angular output. `GenerationFilePlan`
   declares `frontend/...` paths, but no complete-frontend renderer exists yet.

`FinalOutputGoldenTest.currentGeneratorDiffersFromTheRealOutputOnlyInTheRequestValidationFiles`
re-assembles the historical package with today's generator, reusing the real
model-owned `StockUnitServiceImpl`. Every deterministic file must still match
the historical manifest byte-for-byte, except exactly these: `README.md`, the
two request DTOs and `GlobalExceptionHandler` (changed), and
`config/RequestJsonStrictnessConfig.java` (new).

## Acceptance-criteria coverage

| Jira acceptance criterion | Evidence |
| --- | --- |
| Golden test passes for the generated Angular component and Spring controller | `FinalOutputGoldenTest` (real shipped controller), `GeneratedSpringControllerGoldenTest` (template controller), `GeneratedAngularComponentGoldenTest` (Angular, template-only = shipped) |
| A deliberate structural standards violation is detected and named | 12 mutations of the real controller and 12 of the template controller each produce exactly one violation with its rule and Standards Checklist ID; 10 Angular mutations |
| Rules use the Reference Implementation as the expected shape | `referenceImplementationSatisfiesTheSameStandardsRules` runs the same rules on `WorkOrderController` |
| Generated `validate()` accepts the valid corpus and rejects every mutation family | Newly generated complete packages: `GeneratedPackageRequestValidationTest`, all applicable families detected with the field as the error key (StockUnit 5/5, EquipmentLoan 6/6 including pattern). Template-only validator: `GeneratedValidatorBehaviorTest` 5/5 |
| The always-accepting validator makes the harness fail | Detected in 0/5 families by both harnesses; the tests require every family to fail |
| A hand-edited generated file still compiles | `FinalOutputEditableBuildTest` (real output) and `CompleteBackendStandaloneBuildTest#handEdited...` (current generator) edit one method **after** generation and manifest verification, build with Maven, and find the edit in the compiled bytecode |
| Generated code has no mandatory dependency on a generation tool | Any `eg.mts.gsuif`, FreeMarker, Jmix, JavaParser, openapi-generator or OpenRouter reference in the real backend fails the test, whether it is an import or a fully qualified name. The built JAR contains no GSUIF classes or generator libraries |

## Request validation: fixed at the source in the generator

The historical package accepted invalid requests in three of five mutation
families (measured below). The cause was in the generator, so it is fixed there
and applies to every contract:

- **Contract constraints become Bean Validation.** `PackagedContractLoader`
  now reads `minLength`, `maxLength`, `pattern` (plain strings) and `minimum`,
  `maximum` (integers). `RequestValidationRules` turns them into `@Size`,
  `@Pattern`, `@Min` and `@Max` on the generated request DTOs, per operation:
  create and update may differ.
- **Persistence reconciliation.** A plain string is stored in
  `varchar(columnLength)`, 255 by default. When the contract declares no
  `maxLength`, the generator enforces the column length and reports a
  **contract gap**: in the generated README and as a test-visible finding.
  A contract `maxLength` larger than the column, a `minLength` that cannot be
  met, or an integer bound outside `int32`/`int64` is a **conflict** that stops
  generation with a named diagnostic.
- **Unsupported features are named, never ignored.** Any other request-field
  keyword (`multipleOf`, `exclusiveMinimum`, `default`, ...), an unsupported
  format (`email`, ...), a length or pattern keyword on a non-string, a bound on
  a non-integer, an invalid regular expression, or keywords next to a `$ref`
  each produce a specific diagnostic. Before this change they produced one
  generic "unsupported request-field schema" message.
- **Strict JSON types.** The new generated `config/RequestJsonStrictnessConfig`
  (a Boot `JsonMapperBuilderCustomizer`) rejects:
  - numbers or booleans for strings
  - strings, fractions or booleans for integers
  - numbers or strings for booleans
  - enum ordinals
  - epoch numbers for dates
  - duplicate object members
- **Field-level error keys.** `GlobalExceptionHandler` reports body failures
  (type, enum, duplicate, missing member) under the offending declared field,
  never echoing the rejected value, and falls back to `_global` only for
  malformed JSON.

### Historical evidence (unchanged)

`FinalOutputRequestValidationBehaviorTest` still scores the frozen ZIP with its
hand-written corpus and pins the measured result. This is evidence, not the
target:

| Family | Cases | Rejected | Correct path | Detected |
| --- | --- | --- | --- | --- |
| REQUIRED_FIELD | 3 | 3 | 1 | yes |
| WRONG_TYPE | 3 | 1 | 0 | no |
| INVALID_ENUM | 2 | 2 | 0 | yes |
| DUPLICATE_VALUE | 1 | 0 | 0 | no |
| BOUNDARY_VIOLATION | 1 | 0 | 0 | no |

Scored with the stricter contract-driven corpus (below), which also requires
the offending field as the only error key, the same ZIP detects 0 of 5
families.

### Newly generated output (enforced)

`ContractDrivenRequestHarness` derives its corpus from the contract itself.
For every request schema and field it builds:

- a valid body (contract `example` values are used for `pattern` fields)
- null and at-limit variants that must be accepted
- omitted members and nulls for non-nullable fields
- wrong JSON types
- undeclared and wrong-case enum values
- duplicate members
- length and integer bounds: contract limit or column length ±1, and `int32` overflow
- pattern mismatches

It compiles the generated request layer and sends each case through Spring MVC.
The JSON mapper is built the way the generated application builds it: Boot's
Jackson auto-configuration plus the generated config class. A family counts
as detected only when every case is rejected with HTTP 400 and the offending
field is the sole error key. `GeneratedPackageRequestValidationTest` requires
every applicable family to be detected, and every valid body to be accepted:

| Package | Families detected | Valid bodies wrongly rejected |
| --- | --- | --- |
| Regenerated StockUnit (`stock-unit-api` 3.0.0) | 5/5 | 0 |
| Generated EquipmentLoan (`equipment-loan-api` 1.0.0) | 6/6 (including PATTERN_MISMATCH) | 0 |

The EquipmentLoan contract shares no field, enum, limit or package with the
StockUnit example. It declares:

- `minLength`/`maxLength`/`pattern` on `assetTag`
- different integer bounds for create (1–25) and update (0–40)
- a boolean and a nullable date
- `notes` with a contract `maxLength` on update only. On create, the column
  length 500 is enforced and reported as a gap.

Its conflict variants (contract `maxLength` above the column) and
unsupported-feature variants (`multipleOf`, `format: email`) stop generation
with the exact diagnostics asserted by the test.

### Contract ambiguities and limitations (reported, not guessed)

- **Missing length limits.** `stock-unit-api` 3.0.0 declares no `maxLength`
  for `label`. The column is 100, so 100 is enforced and the gap is reported
  (`CreateStockUnitRequest.label`, `UpdateStockUnitRequest.label`). The
  contract should declare it in a future version.
- **POST 400 not declared.** The selected contracts declare no 400 response for
  POST, although validation failures now return 400 there. This was already
  noted in the generated README; the copied contract is not silently amended.
- **`pattern` semantics.** JSON Schema patterns are unanchored ECMA-262
  regular expressions. The generator wraps them as `(?s).*(?:pattern).*` so
  `@Pattern` keeps search semantics, and compiles them with the Java dialect.
  Expressions that differ between ECMA-262 and Java are therefore interpreted
  with Java rules; patterns that do not compile in Java are rejected by name.
- **Length unit.** `maxLength` counts Unicode code points; `@Size` counts UTF-16
  units. They differ only for characters outside the Basic Multilingual Plane,
  where `@Size` is stricter.
- **Unknown members.** OpenAPI 3.0 allows additional properties unless
  `additionalProperties: false` is declared. The contracts do not declare it,
  so unknown members are still ignored, not rejected.
- **Decimal bounds.** `minimum`/`maximum` on `number` fields are reported as
  unsupported. `BigDecimal` fields are already rejected by the complete-output
  profile.

## Structural rules and Standards Checklist mapping

`JavaStructuralGoldenRules` parses at the Java 21 language level (generated
code uses records and pattern matching). It compares types and annotations by
simple name, so it accepts both the imported template style and the fully
qualified complete-package style. CRUD method names, the create operation and
`{id}` operations come from the shipped OpenAPI contract, not from hard-coded
strings.

| Rule | Checklist ID | Notes |
| --- | --- | --- |
| `JAVA_PACKAGE`: controller in `<base>.controller` | STD-06 | |
| `JAVA_PACKAGE_NAMING`: lowercase segments, at least 3 | STD-08 | |
| `JAVA_REST_CONTROLLER_ANNOTATION` | STD-06 | |
| `JAVA_SERVICE_FIELD`, `JAVA_SERVICE_CONSTRUCTOR_RELATIONSHIP`: logic delegated to the service layer | STD-06 | |
| `JAVA_API_RELATIONSHIP`: implements the contract-generated `*Api` | STD-27 | Not applied to the reference implementation, which declares its own mappings |
| `CONTROLLER_RESPONSE_ENVELOPE`: every public endpoint returns `ResponseEntity<ApiResponse<T>>` | STD-05 | |
| `CREATE_STATUS_201`: `status(201)`, `HttpStatus.CREATED` or `created(...)` | STD-28 | |
| `ENDPOINT_RBAC`: `@PreAuthorize` on every CRUD endpoint | STD-20 | Not applied to the reference implementation (no method-level RBAC) |
| `UUID_IDENTIFIER`: `{id}` parameters are `UUID` | STD-35 | The real entity's `@Id` is also checked |
| `JAVA_CLASS_NAME`, `JAVA_CRUD_METHODS` | `SCRUM-50/UNMAPPED` | Generation-contract assertions, no checklist item |
| CRUD artifacts in their layers (controller, entity, repository, service, dto) | STD-06 | Real output |
| `ApiResponse` has exactly `status, clientMessage, statusCode, body, errors` | STD-01 | Real output |
| Angular imports, decorator, selector, fields, template | `SCRUM-50/UNMAPPED` | STD-07 is DEFERRED for Phase 1 |

## STD-06 against its wording

STD-06 says:

> Implement: `config`, `controller`, `dto`, `entity`, `repository`, `service`,
> `exception`, `aspect`, `security`, `audit`, `util`.
> Check: Fail if a class lives outside its designated layer (e.g. business logic
> sitting in `controller`).

Existing generated output does not establish an exception to this.
Accordingly:

- The new strict-JSON class is in `config/`, the constraints are on the
  request DTOs in `dto/`, and the error mapping is in `exception/`.
- The template-only generated validator moved from a `validation` package,
  which is not a designated layer, to `util`.
- `GeneratedPackageRequestValidationTest.newlyGeneratedPackageLayersAgainstStd06`
  lists the classes of a newly generated complete package that are outside the
  designated layers. These are **open STD-06 non-conformances that predate
  SCRUM-50**, not accepted exceptions:

  - `api/`: `ApiResponse`, `ErrorApiResponse`, `<Entity>Api`
  - `bootstrap/`: `FirstAdminBootstrap`, `FirstAdminBootstrapService`
  - `filter/`: `CorrelationIdFilter`
  - `logging/`: `MaskingPatternLayout`, `SafeFailureLogger`, `SensitiveDataMasker`
  - the base package: `Application`. STD-06 is silent on it; Spring Boot's
    component scan expects it there.

  The test fails if the set grows. Relocating these packages changes many
  templates and tests and needs a team decision, so it is left open here.

## Editable Code Test result

`FinalOutputEditableBuildTest` extracts the real ZIP and makes a one-line hand
edit in one method. In `StockUnitServiceImpl.list` it simplifies the LIKE
pattern `"%%" + label + "%%"` to `"%" + label + "%"`. In SQL `LIKE`, `%%`
matches exactly what `%` matches, so this is a behavior-preserving maintenance
edit, not a bug fix; no functional defect is claimed. The test then:

- proves exactly one line changed and every other file is byte-identical
- rejects any generator reference
- runs `mvn -DskipTests package`
- checks the compiled string-concatenation recipe: the new pattern is present
  and the old one is gone

The JAR contains no `eg/mts/gsuif` classes and no generator libraries.

## Local verification

On 2026-10-20 (Windows 11, Java 21.0.12, Node 24.16.0):

- SCRUM-50 suites: 70 tests passed. The run covered `FinalOutputGoldenTest`,
  `GeneratedSpringControllerGoldenTest`, `GeneratedAngularComponentGoldenTest`,
  `GeneratedValidatorBehaviorTest`, `FinalOutputRequestValidationBehaviorTest`,
  `GeneratedPackageRequestValidationTest`, `CompleteBackendErrorTest` and
  `CompleteOutputDescriptorContractTest`.
- Opt-in real Maven builds: 4 tests passed with packaging. They cover the
  regenerated unaudited StockUnit and audited Gadget packages, which now
  include `RequestJsonStrictnessConfig`, the hand-edited regenerated package,
  and the hand-edited historical ZIP.
- Full backend suite (`./mvnw -B test`): 769 tests, 0 failures, 0 errors,
  30 skipped (opt-in and environment-gated tests), BUILD SUCCESS.

Runtime startup of a generated application against PostgreSQL
(`CompleteBackendStandaloneStartupTest`) is environment-gated and was not run.
The strict-JSON customizer was exercised through Spring Boot's
`JacksonAutoConfiguration` in the harness, not inside a started application.
