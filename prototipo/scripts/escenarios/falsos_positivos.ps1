<#
.SYNOPSIS
  Falsos positivos del kit con n explícito (Fase 6b)
.DESCRIPTION
  Requiere la plataforma en marcha y los resultados de S1, S2, S3, S6, S7 y del rendimiento de la misma corrida. Cuenta los comprobantes correctos que pasaron por el kit y los bloqueados por error, con la cota superior del 95 %. Resultado en results\escenarios\falsos_positivos.json.
#>
$ErrorActionPreference = 'Stop'
Import-Module (Join-Path $PSScriptRoot '..\lib\Veriagent.psm1') -Force
exit (Invoke-AgenticScenario -Name 'falsos_positivos')
