<#
.SYNOPSIS
  S6. Idempotencia ante envío duplicado
.DESCRIPTION
  Requiere la plataforma en marcha (scripts\run-local.ps1 -Background) y el entorno de Python del plano
  agéntico. Reenvía un comprobante, un lote completo y fuerza una respuesta duplicada del proveedor. El resultado queda en results\escenarios\S6.json.
#>
$ErrorActionPreference = 'Stop'
Import-Module (Join-Path $PSScriptRoot '..\lib\Veriagent.psm1') -Force
exit (Invoke-AgenticScenario -Name 's6')
