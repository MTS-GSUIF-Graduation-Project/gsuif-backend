# SCRUM-51 test-first reproduction

These are hand-minimized regression inputs, not claimed jqwik discoveries. The host
owns compilation, test execution, failure reports and replay evidence. No test was
run by the author of this change.

`MetadataBusinessValidatorPropertiesTest` pins seeds 51001 through 51005 and try
counts on each property. All random inputs come from jqwik; identifiers derive
from generated longs, and duplication happens after generation so shrinking
preserves the targeted fault. The explicit examples guarantee PATCH, case,
whitespace, empty methods and all three byte boundaries even if sampling changes.
The negative properties apply the same named-rule/path oracle to each real rule
and an always-accepting proxy, requiring an AssertionError for each mutant.
Host reports must retain property name, seed, tries and shrunk sample when a
property fails. `MetadataBusinessMutationEngineTest` asks the actual jqwik engine
to execute each negative property normally, with only its target rule replaced,
again with the same pinned seed, and with the real rule restored. It requires
engine-reported assertion failures for both mutant runs, identical generated and
shrunk contexts on replay, and successful normal/restored runs. Scoped
thread-local replacement is cleared in `finally`; existing property assertions
remain active. No production rule or build configuration is mutated. The fixed
host test command discovers this harness; execution evidence remains host work.

`MetadataBusinessFixtureTest` replays the two JSON files. The size fixture is the
compact recipe `BusinessRuleTestSupport.sized(bytes, duplicate)`: five valid
components with unique sequential UUIDs, labels beginning with multibyte and
escaped text, then ASCII padding distributed across five strings. It asserts
independent UTF-8 counts at 4,999,999, 5,000,000 and 5,000,001 bytes and schema
validity. No individual label approaches the parser's default string limit.

Internal reflective seam selected for test-first implementation (not a new public
API or owner-contract decision):

- `eg.mts.gsuif.validator.MetadataBusinessRule`: `name()`,
  `supports(MetadataValidationContext)`, `validate(MetadataValidationContext)`.
- `MetadataValidationContext.snapshot(com.fasterxml.jackson.databind.JsonNode,
  String exactPersistedJson)` and `.page(UUID projectId, UUID excludedPageId,
  String route)` are static factories.
- `MetadataBusinessValidator.validate(MetadataValidationContext)` and each rule
  return `List<MetadataSchemaValidator.ValidationError>`.
- Discovered Spring beans expose stable names `RouteUniqueness`,
  `ComponentIdUniqueness`, `SupportedHttpMethod`, `SnapshotSize` in messages.
- Paths are `route`, `$.snapshot.components[index].id`,
  `$.snapshot.apiBindings[index].httpMethod`, and `$.snapshot` respectively.
- Core findings sort by path then message; it skips unsupported contexts and
  retains every finding, including multiple messages on one path.

Missing types cause assertion failures, not compile errors, skips or fake success.
Test-only discovered rules add actual failures and never substitute the real
production validator or its built-in rules. Repository mocks isolate persistence;
HTTP tests exercise the real application and database.

AC2 retirement: duplicate-version-name rejection is explicitly retired. No name
field/rule/generator is added. Numeric/history assertions only preserve existing
behavior; they do not fulfill the retired obligation. PATCH remains unsupported.
