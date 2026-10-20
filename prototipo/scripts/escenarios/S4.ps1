<#
.SYNOPSIS
  S4. Rechazo del proveedor, DLQ, triaje del agente, aprobación y reproceso
.DESCRIPTION
  Requiere la plataforma en marcha (scripts\run-local.ps1 -Background) y el entorno de Python del plano
  agéntico. Activa en Alfa un rechazo de catálogo de una sola vez, ejecuta el agente de triaje y reprocesa como operadora. El resultado queda en results\escenarios\S4.json.
#>
$ErrorActionPreference = 'Stop'
Import-Module (Join-Path $PSScriptRoot '..\lib\Veriagent.psm1') -Force
exit (Invoke-AgenticScenario -Name 's4')
