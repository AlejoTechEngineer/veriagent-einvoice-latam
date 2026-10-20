<#
.SYNOPSIS
  Ejecuta S1 a S8, el banco de 22 defectos, las mediciones, los falsos positivos y el banco ampliado sobre una plataforma local limpia.
.DESCRIPTION
  Arranca la plataforma con estado limpio, ejecuta los escenarios en orden y la detiene al final. Con
  -SinRendimiento omite la medición del lote de 10.000 comprobantes y el conteo de falsos positivos, que depende de ella.
#>
param([switch]$SinRendimiento)
$ErrorActionPreference = 'Stop'
Import-Module (Join-Path $PSScriptRoot '..\lib\Veriagent.psm1') -Force
& (Join-Path $PSScriptRoot '..\run-local.ps1') -Clean -Background
$fallos = @()
try {
    foreach ($s in 'S1', 'S2') {
        & (Join-Path $PSScriptRoot "$s.ps1")
        if ($LASTEXITCODE -ne 0) { $fallos += $s }
    }
    $names = @('s3', 's4', 's5', 's6', 's7', 's8', 'banco')
    if (-not $SinRendimiento) { $names += 'rendimiento', 'falsos_positivos' }
    $names += 'banco_ampliado'
    foreach ($n in $names) {
        if ((Invoke-AgenticScenario -Name $n) -ne 0) { $fallos += $n.ToUpper() }
    }
} finally {
    & (Join-Path $PSScriptRoot '..\stop-local.ps1')
}
if ($fallos.Count -gt 0) { Write-Host "Escenarios con fallos: $($fallos -join ', ')"; exit 1 }
Write-Host 'Todos los escenarios pasaron. Resultados en results\escenarios.'
