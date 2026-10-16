# Internal BACKEND package assembly

`CompleteBackendPackageAssembler` accepts only one frozen BACKEND `BackendModelInput`,
the `Accepted` source validated for that exact invocation, and trusted provenance.
The assembler renders the current deterministic backend subsets, rejects any
path/owner mismatch or cross-renderer collision, checks the selected contract,
POM, and byte-bound handoff, then adds the single model-owned service file.
It returns defensive copies of an in-memory package. It does not dispatch a
provider request, persist a run, register artifacts, or export files.

`generation-manifest.json` is assembler-owned UTF-8 JSON. Its `files` array is
sorted by path and contains the path, owner, byte length, and SHA-256 of every
other packaged file. The manifest has no entry for itself, avoiding a recursive
hash. A later artifact registry can hash the final manifest bytes. Contract
identity and SHA-256 are taken from the exact selected version; provider ID,
version, model ID, and an explicit `mocked` flag come from the trusted invocation.
Tests use `mocked: true` and `mock-provider`; this is not evidence of an
OpenRouter generation. Neither credentials, prompts, nor raw provider responses
are package inputs or manifest fields.

The model validator checks response structure, exact ownership, Java structure,
and isolated compilation before returning `Accepted`. Assembly checks that the
accepted object belongs to the same frozen invocation. This slice does not
prove CRUD behavior or runnable output. Later work must clean-build the package
outside GSUIF, run disposable-database acceptance, then connect successful final
validation to attempt lifecycle, artifact registration, and export. Actual
provider/model identity must come from that future invocation rather than a
mock value.
