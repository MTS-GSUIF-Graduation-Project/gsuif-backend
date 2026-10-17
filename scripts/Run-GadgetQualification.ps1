$ErrorActionPreference = 'Stop'
# This child is launched by Invoke-OpenRouterLocal.ps1. Pin the approved exact slug;
# an older ignored local model file may still contain the :free variant.
$env:OPENROUTER_MODEL = 'qwen/qwen3-coder'
& python (Join-Path $PSScriptRoot 'qualify_openrouter.py') --live
exit $LASTEXITCODE
