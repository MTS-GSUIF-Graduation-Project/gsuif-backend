# Initial complete-output persistence mapping gate

For BACKEND and FULL_STACK complete-output descriptors, `BigDecimal` is unsupported until the generation specification can provide explicit precision and scale. Preflight rejects it before a descriptor or rendered files exist. This limit does not change the legacy fragment path or the shared entity template's legacy `BigDecimal` rendering.

Complete-output enum entity fields retain `@Enumerated(STRING)` and explicitly use `@JdbcTypeCode(SqlTypes.VARCHAR)`. Their selected contract values must be Java enum identifiers and fit the field's explicit `columnLength`, or 255 when no length is set. This is checked before definition rendering. The shared `entity.ftl` enables the Hibernate annotation only when the complete-output renderer supplies `explicitVarcharEnumMapping=true`; absent/false preserves the legacy template default. Non-enum scalar fields do not receive the annotation. The public legacy context builder continues to reject explicit enum column lengths; only complete-output BACKEND/FULL_STACK can use them.

The focused test compiles an emitted entity and inspects Hibernate ORM 7.2.24.Final metadata using PostgreSQL dialect with JDBC metadata access disabled. It confirms the enum property's JDBC type is `VARCHAR` without a database connection. No migration or PostgreSQL `CHECK` constraint is emitted by this gate. A future migration renderer must derive and test the database constraint from the same selected contract values.

This partial generated output remains non-runnable. V1 migration, account mapping, Envers revision schema, persistence behavior and repeated startup remain later review steps. Lowercase ASCII username normalization is a proposal pending the account/security review, not an implemented policy.
