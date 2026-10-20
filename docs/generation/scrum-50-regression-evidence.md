# SCRUM-50 regression evidence

Baseline: `89138524a456fca9093ea7f90b0c36868f9df141`

## Acceptance-criteria coverage

- `GeneratedSpringControllerGoldenTest` parses the generated
  `AssetTicketController` with JavaParser. Its deliberate mutations name the
  failing rule and applicable checklist/contract ID.
- `GeneratedAngularComponentGoldenTest` checks the generated
  `AssetTicketComponent` and HTML with the pinned TypeScript AST/compiler
  harness. Its deliberate mutations name the failing generation-contract rule.
- `GeneratedValidatorBehaviorTest` compiles the emitted
  `AssetTicketGeneratedValidator`, loads it, invokes `validate()`, and applies
  the shared valid/invalid corpus. The corpus covers required-field,
  wrong-type, invalid-enum, duplicate-value, and boundary mutations and kills
  the deliberately faulty `AlwaysAcceptingValidator`.
- `CompleteBackendStandaloneBuildTest` hand-edits an emitted service, builds
  the extracted backend, verifies the edit in compiled bytecode, and rejects
  GSUIF/generator dependencies and imports. Spring/JPA/Jackson remain ordinary
  generated-application dependencies.

## Applicable structural mapping

| Assertion | Checklist/contract ID | Artifact | Deliberate mutation |
| --- | --- | --- | --- |
| Package placement | `STD-06` | Spring controller | Move package |
| `@RestController` | `STD-06` | Spring controller | Remove annotation |
| Class name | `SCRUM-50/UNMAPPED` | Spring controller | Rename class |
| Service field and constructor/API relationships | `SCRUM-50/UNMAPPED` | Spring controller | Change field/constructor type; remove implemented API |
| CRUD methods | `SCRUM-50/UNMAPPED` | Spring controller | Rename create method |
| `ResponseEntity<ApiResponse<...>>` | `STD-05` | Spring controller | Replace response envelope |
| Create returns HTTP 201 | `STD-28` | Spring controller | Replace `CREATED` with `OK` |
| Imports, DTO, decorator, class, selector, standalone flag, fields, methods, and template relationship | `SCRUM-50/UNMAPPED` | Angular component | Remove or rename each structure |
| Generated grid/form/table HTML | `SCRUM-50/UNMAPPED` | Angular template | Replace required grid class |

No catalog implementation status is changed. Standards not applicable to these
artifacts are not asserted.

## Local verification

On 2026-10-20:

- Generated validator and focused generation contracts: 5 tests passed.
- Spring structural golden suite: 10 tests passed.
- Hand-edited standalone backend: 1 test passed.
- Angular structural suite could not run locally because Node/npm is not
  installed on the workstation. CI provisions pinned Node and Angular compiler
  dependencies before Maven verification and is the required execution
  evidence for that suite.

The full legacy `GenerationEngineTest` suite additionally requires its OpenAPI
fixture profiles and Node/npm. Focused profile-independent generation tests
passed; CI runs the repository's complete configured verification.
