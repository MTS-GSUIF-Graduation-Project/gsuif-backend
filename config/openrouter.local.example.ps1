# Copy this file to openrouter.local.ps1 (ignored) and select a model explicitly.
# The API key is stored by Save-OpenRouterKey.ps1 under the current user's APPDATA.
$env:OPENROUTER_MODEL = 'qwen/qwen3-coder'
# Optional qualification prerequisite: set this to the reviewed, locally present
# Linux JDK 21 Docker image ID. The harness accepts only an immutable sha256 ID.
# $env:GSUIF_QUALIFICATION_IMAGE = 'sha256:<64 lowercase hex digits>'
