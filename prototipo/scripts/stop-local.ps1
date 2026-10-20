<#
.SYNOPSIS
  Detiene la plataforma arrancada con run-local.ps1 -Background.
#>
$ErrorActionPreference = 'Stop'
Import-Module (Join-Path $PSScriptRoot 'lib\Veriagent.psm1') -Force
$pidFile = Join-Path (Get-VeriagentHome) 'results\runtime\app.pid'
if (-not (Test-Path $pidFile)) { Write-Host 'No hay plataforma en segundo plano.'; Stop-ReviewApi; return }
$id = [int](Get-Content $pidFile)
$p = Get-Process -Id $id -ErrorAction SilentlyContinue
if ($p) { Stop-Process -Id $id -Confirm:$false; Write-Host "Plataforma detenida (PID $id)." }
Remove-Item $pidFile -Confirm:$false
Stop-ReviewApi
