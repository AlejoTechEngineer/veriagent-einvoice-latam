<#
.SYNOPSIS
  BANCO. Banco de 22 defectos por el sistema real
.DESCRIPTION
  Requiere la plataforma en marcha (scripts\run-local.ps1 -Background) y el entorno de Python del plano
  agéntico. Propone cada mutante al registro de mapeos y mide qué bloquea el control del kit. El resultado queda en results\escenarios\BANCO.json.
#>
$ErrorActionPreference = 'Stop'
Import-Module (Join-Path $PSScriptRoot '..\lib\Veriagent.psm1') -Force
exit (Invoke-AgenticScenario -Name 'banco')
