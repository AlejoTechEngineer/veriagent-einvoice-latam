<#
.SYNOPSIS
  S8. Reversión de un mapeo
.DESCRIPTION
  Requiere la plataforma en marcha (scripts\run-local.ps1 -Background) y el entorno de Python del plano
  agéntico. El agente de legado propone una versión para Beta PE; se aprueba, se activa y se revierte. El resultado queda en results\escenarios\S8.json.
#>
$ErrorActionPreference = 'Stop'
Import-Module (Join-Path $PSScriptRoot '..\lib\Veriagent.psm1') -Force
exit (Invoke-AgenticScenario -Name 's8')
