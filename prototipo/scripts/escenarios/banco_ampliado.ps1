<#
.SYNOPSIS
  Banco ampliado de mutantes por el sistema real (Fase 6b)
.DESCRIPTION
  Requiere la plataforma en marcha y el entorno de Python del plano agéntico. Genera el banco con los operadores de D1 a D6 (semilla 2026, hasta 30 por tipo), excluye equivalentes y verifica cada mutante por el registro de mapeos. Resultado en results\escenarios\banco_ampliado.json.
#>
$ErrorActionPreference = 'Stop'
Import-Module (Join-Path $PSScriptRoot '..\lib\Veriagent.psm1') -Force
exit (Invoke-AgenticScenario -Name 'banco_ampliado')
