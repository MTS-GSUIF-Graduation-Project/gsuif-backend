# SCRUM-50 generated validator contract

SCRUM-50 requires generated `validate()` implementations to be compiled and
invoked, but Jira does not prescribe a Java signature, input type, or finding
shape. No generated-validator API existed at baseline
`89138524a456fca9093ea7f90b0c36868f9df141`.

The generated application therefore owns this minimal deterministic contract:

```java
List<Finding> validate(String schemaName, byte[] jsonUtf8)
```

`schemaName` identifies a generated request schema, and `jsonUtf8` is the raw
JSON object so duplicate object members remain observable. A finding contains:

- `path`: stable JSON path;
- `code`: `REQUIRED_FIELD`, `WRONG_TYPE`, `INVALID_ENUM`,
  `DUPLICATE_VALUE`, `BOUNDARY_VIOLATION`, `MALFORMED_JSON`, or
  `UNKNOWN_SCHEMA`;
- `detail`: deterministic non-sensitive explanation.

Findings are sorted by path, code, then detail. Rules are frozen from the
selected packaged OpenAPI contract and generation specification. The generated
validator uses Jackson only and must not import GSUIF, Spring, FreeMarker,
JavaParser, Jmix, OpenAPI Generator, or provider/runtime generation classes.

This is intentionally separate from the framework's
`MetadataSchemaValidator`, `MetadataBusinessValidator`,
`BackendModelOutputValidator`, and build validators. Those types neither
implement nor stand in for the generated contract.
