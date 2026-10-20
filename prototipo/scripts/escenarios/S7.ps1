<#
.SYNOPSIS
  S7. Proveedor caído: reintentos y DLQ, sin pérdida de comprobantes
.DESCRIPTION
  Requiere la plataforma en marcha (scripts\run-local.ps1 -Background) y el entorno de Python del plano
  agéntico. Deja Alfa no disponible durante un lote mixto CO y PE, y reprocesa tras la recuperación. El resultado queda en results\escenarios\S7.json.
#>
$ErrorActionPreference = 'Stop'
Import-Module (Join-Path $PSScriptRoot '..\lib\Veriagent.psm1') -Force
exit (Invoke-AgenticScenario -Name 's7')
