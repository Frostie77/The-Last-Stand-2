$ErrorActionPreference = 'Stop'
& powershell -NoProfile -ExecutionPolicy Bypass -File (Join-Path $PSScriptRoot 'build-test.ps1')
if ($LASTEXITCODE -ne 0) { throw 'Build/install failed; reload capture was not started.' }
Write-Host 'Open the updated Debug game and equip the sawn-off shotgun. Fire once so its magazine is not full.'
Read-Host 'Leave FrostFrontend closed. Press Enter here when ready to test manual reload' | Out-Null
Write-Host 'During capture, press X to reload, then move BOTH sticks and try Fire and Switch.'
& (Join-Path $PSScriptRoot 'capture-controller-input.ps1') -CaptureSeconds 12
