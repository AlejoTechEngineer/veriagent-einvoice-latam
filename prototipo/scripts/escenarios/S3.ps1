<#
.SYNOPSIS
  S3. Defectos D1 a D6 bloqueados antes del envío, con regla y norma
.DESCRIPTION
  Requiere la plataforma en marcha (scripts\run-local.ps1 -Background) y el entorno de Python del plano
  agéntico. Envía los defectos de datos por la fachada y por un lote, y propone mapeos D5 y D6 al registro. El resultado queda en results\escenarios\S3.json.
#>
$ErrorActionPreference = 'Stop'
Import-Module (Join-Path $PSScriptRoot '..\lib\Veriagent.psm1') -Force
exit (Invoke-AgenticScenario -Name 's3')
