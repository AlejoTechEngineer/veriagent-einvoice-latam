<#
.SYNOPSIS
  S5. Incorporación del país sintético XX con el proveedor Gamma
.DESCRIPTION
  Requiere la plataforma en marcha (scripts\run-local.ps1 -Background) y el entorno de Python del plano
  agéntico. El agente propone el mapeo desde la especificación; reviewer-a aprueba y reviewer-b activa. El resultado queda en results\escenarios\S5.json.
#>
$ErrorActionPreference = 'Stop'
Import-Module (Join-Path $PSScriptRoot '..\lib\Veriagent.psm1') -Force
exit (Invoke-AgenticScenario -Name 's5')
