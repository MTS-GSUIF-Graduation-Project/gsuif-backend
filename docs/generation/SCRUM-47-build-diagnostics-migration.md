# SCRUM-47 build diagnostics schema rollout

`sql/V1__init_schema.sql` remains the original baseline. Apply
`sql/V2__add_build_diagnostics.sql` to existing databases before deploying the
SCRUM-47 application code. Its four `ADD COLUMN IF NOT EXISTS` statements are
safe to rerun and add nullable columns without rewriting existing run records.

The production profile uses Hibernate `ddl-auto: validate` and this repository
does not configure Flyway or Liquibase. Deployment must therefore execute V2
through the database change process before application startup. For a new
database, apply V1 followed by V2. The test profile loads both scripts in that
order and validates the resulting JPA mapping.

Example for a PostgreSQL deployment with `psql` configured for the target
database:

```sh
psql -v ON_ERROR_STOP=1 --single-transaction -f sql/V2__add_build_diagnostics.sql
```

Do not use `V1__init_schema.sql` again on an existing database.
