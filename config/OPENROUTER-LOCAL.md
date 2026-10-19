# Local OpenRouter runtime configuration

For backend generation, `scripts/Initialize-GsuifLocal.ps1` and `scripts/Start-GsuifLocal.ps1` reuse the key
store and launcher below and add the pinned image IDs and Maven paths; see `docs/local-startup.md`.

This setup prepares the GSUIF backend for later OpenRouter integration. The bounded Gadget qualification harness is opt-in and separate from application generation. Generated applications do not use these variables.

On Windows, run `scripts/Save-OpenRouterKey.ps1` interactively once. It prompts with masked input and writes DPAPI-encrypted ciphertext to `%APPDATA%\GSUIF\openrouter-key.dpapi`, outside this repository and bound to the current Windows user. The script restricts file access to that user. Repeat the command to rotate the key; do not paste it into a terminal command, chat, repository file, CI log or issue.

Copy `config/openrouter.local.example.ps1` to `config/openrouter.local.ps1` and set `OPENROUTER_MODEL` to the explicitly chosen model. The example contains no secret. The local copy and `*.dpapi` files are ignored by Git. The qualification candidate is the exact `qwen/qwen3-coder` slug. The qualification child pins it even if an older ignored local file still names the `:free` variant.

Start the backend from the same Windows account with `scripts/Invoke-OpenRouterLocal.ps1 -BackendCommand <backend executable> -BackendArguments <arguments>`. The loader reads and decrypts the user-bound file in memory, sets `OPENROUTER_API_KEY` for the launched process, and restores the caller's original key and model presence and values on success or error. It rejects a pre-existing key without changing it. The local model file must not set an API key. The backend receives both variables from the launcher; nested qualification processes launched by that backend inherit them. An independently launched process must use the loader again. Spring maps them to `gsuif.generation.openrouter.api-key` and `.model`; neither has an operational default. A local `.env` file is not loaded automatically.

For a presence-only check, launch a child through the loader. For example, `.\scripts\Invoke-OpenRouterLocal.ps1 -BackendCommand powershell.exe -BackendArguments @('-NoProfile', '-Command', 'if (-not $env:OPENROUTER_API_KEY -or -not $env:OPENROUTER_MODEL) { exit 1 }')` checks inside the launched child and prints neither value. An unrelated terminal or the caller after cleanup does not inherit the temporary values. Ordinary CI uses mocks and does not need the key. Deployment-platform runtime-secret wiring remains deferred until hosting is selected.

## Bounded Gadget qualification

Run mock-only checks with `python -m unittest scripts/test_qualify_openrouter.py -v`. To make the approved live call from the same Windows account that saved the key, run from the worktree root:

```powershell
& .\scripts\Invoke-OpenRouterLocal.ps1 -BackendCommand powershell.exe -BackendArguments @('-NoProfile', '-File', '.\scripts\Run-GadgetQualification.ps1')
```

The child pins `OPENROUTER_MODEL=qwen/qwen3-coder`. It sends a locked Gadget 2.0.0 contract and deterministic Java service interface, requesting only `qualification/gadget/GadgetServiceImpl.java`. It makes at most two chat-completion HTTP requests, with `max_tokens=4096` each, 10-second connect timeout, 120-second response timeout, 250-second overall deadline across HTTP and local checks, and one retry after two seconds only for transport errors, 429 or 5xx. A supervised HTTP worker is terminated at the overall deadline, including a stalled DNS lookup. The request sets `provider.allow_fallbacks=false` and names no alternate model. It does not pin the initial serving provider; OpenRouter chooses it. A fixed provider would require an explicit provider choice before running. The harness never sends an automatic repair prompt.

Qualification also requires Docker with a locally present, reviewed Linux JDK 21 image. Set `GSUIF_QUALIFICATION_IMAGE` in the ignored local configuration to that image's immutable `sha256:` ID (obtain it locally with `docker image inspect --format '{{.Id}} {{.Os}}' <reviewed-image>` and verify the OS is `linux`). The harness never pulls an image. Both `javac` and `java` run inside one short-lived non-root container with `--network=none`, a read-only root, only the three fixture/source files mounted read-only, capped memory/CPU/processes, dropped capabilities and no new privileges. Docker and HTTP helper subprocesses receive an allowlisted environment without `OPENROUTER_API_KEY`; the trusted HTTP worker receives the key only on its private standard-input pipe. The container receives no key. Timeout kills the Docker client and force-removes its named container; inability to confirm removal fails closed. Compilation and in-memory CRUD behavior are reported separately. Source filtering is a contract check, not the execution boundary.

The harness fails with `SANDBOX_UNAVAILABLE` if the image ID or Docker isolation is missing, before starting `javac` or `java`. On 15 October 2026, fixed Java source and disposable values passed a local Docker Desktop smoke check with the official Eclipse Temurin JDK 21 image. The image ID is pinned in the ignored `config/openrouter.local.ps1`; that smoke check did not use the key or make a live model request. The key owner's execution context must still be available for live qualification. This is a qualification fixture, not generated application assembly or a claim of end-to-end runtime support.

Each qualification attempt gets a new directory under ignored `target/openrouter-qualification/gadget/attempts`; its `result.json` and any accepted Java source stay together there. Earlier attempt directories are never reused or overwritten. The prompt, raw provider response, key and authorization header are not saved. Compilation diagnostics are intentionally not printed or persisted; `result.json` records only bounded outcome codes, boundary reasons and HTTP attempt statuses/categories. A failed qualification does not trigger another model or request beyond the stated retry policy.

Source normalization accepts raw Java or one complete outer Markdown code fence labelled `java` or unlabelled, with only surrounding whitespace outside it. It rejects prose outside the fence, multiple or nested fences, and other fence labels. The report records `normalization=RAW_JAVA` or `normalization=OUTER_JAVA_FENCE` after a valid wrapper is identified; malformed wrappers retain `OUTPUT_BOUNDARY` and `FORMAT_MARKDOWN_FENCE`. The same source, contract and security checks apply to the unwrapped Java before any sandbox compilation or behavior check. The discarded earlier provider response is not retained and cannot be reclassified.

## Locked qualification fixture bytes

The Gadget qualification fixture pins SHA-256 over UTF-8 source bytes with LF
line endings. The loader converts CRLF to LF before hashing and adding it to the
prompt; both LF and CRLF may occur in one checkout. Bare CR and substantive
content changes fail with `FIXTURE_CHANGED`. The committed Git
blob is the canonical LF copy; a Windows worktree's line-ending conversion does
not change the locked prompt.
