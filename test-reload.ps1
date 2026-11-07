$ErrorActionPreference = 'Stop'
& powershell -NoProfile -ExecutionPolicy Bypass -File (Join-Path $PSScriptRoot 'build-test.ps1')
if ($LASTEXITCODE -ne 0) { throw 'Build/install failed; reload capture was not started.' }
Write-Host 'Open the updated Debug game and enter any night fight. The test will equip the sawn-off for you.'
Read-Host 'Leave FrostFrontend closed and release Fire. Press Enter here when ready' | Out-Null
& (Join-Path $PSScriptRoot 'capture-controller-input.ps1') -SawnOff
