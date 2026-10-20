<#
.SYNOPSIS
  RENDIMIENTO. Latencia de verificación y de emisión, y rendimiento del lote
.DESCRIPTION
  Requiere la plataforma en marcha (scripts\run-local.ps1 -Background) y el entorno de Python del plano
  agéntico. Mide p50 y p95 y procesa un lote de 10.000 comprobantes. El resultado queda en results\escenarios\RENDIMIENTO.json.
#>
$ErrorActionPreference = 'Stop'
Import-Module (Join-Path $PSScriptRoot '..\lib\Veriagent.psm1') -Force
exit (Invoke-AgenticScenario -Name 'rendimiento')
