# Internal backend model compiler classpath

The internal backend model adapter resolves compile dependencies **before** its first provider request.
`BackendModelInput` freezes the exact backend POM bytes emitted by `CompleteBackendRuntimeRenderer` for
the selected descriptor. The POM selects the generated application's pinned dependencies and includes
Hibernate Envers only for an audited descriptor. GSUIF's runtime or test classpath is never used.

Trusted Maven provisioning uses the fixed `maven-dependency-plugin:3.9.0:build-classpath` goal with
compile scope and a 90-second overall provisioning deadline. Configure an absolute Maven executable
with `GSUIF_MAVEN_COMMAND`, and a local repository with `GSUIF_MAVEN_REPOSITORY` or the
`maven.repo.local` Java property. These settings work in a checkout or a packaged GSUIF deployment;
the renderer reads packaged resources rather than source-tree paths. Maven may use the operator's
normal private repository settings during this trusted step. The OpenRouter API key is removed from
the Maven child environment. Its output is discarded and never sent
to the model or compiler sandbox.

The immutable manifest binds the POM SHA-256 to every resolved JAR's Maven coordinates and SHA-256.
Only JARs under the configured Maven repository are accepted. The compiler copies each JAR into a
disposable directory, verifies the copied bytes against the manifest, then mounts only those JARs
and the byte-bound source definitions into the pinned JDK container. The container remains non-root,
offline, read-only at its input mount, and uses `javac -proc:none`. Missing, changed, or invalid
artifacts fail with fixed diagnostic codes; exception text and Maven output are not returned.

This compilation check does not execute model source or establish CRUD runtime correctness. Public
dispatch, final application assembly, and clean exported-package validation remain separate work.
