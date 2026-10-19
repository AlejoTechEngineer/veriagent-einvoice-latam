<#
.SYNOPSIS
  S2. Lote asíncrono de 1.000 comprobantes válidos.
.DESCRIPTION
  Requiere la plataforma en marcha. Genera 1.000 comprobantes válidos con semilla fija (600 de Colombia para Alfa,
  por ZIP sobre HTTP, y 400 de Perú para Beta, por carpeta SFTP), los carga como un lote por la ingesta y espera
  a que no quede ninguno en tránsito. Comprueba que todos terminan autorizados y que entradas = salidas + DLQ.
#>
param([int]$Colombia = 600, [int]$Peru = 400, [long]$Seed = 202, [int]$TimeoutSeconds = 600)
$ErrorActionPreference = 'Stop'
Import-Module (Join-Path $PSScriptRoot '..\lib\Veriagent.psm1') -Force

$work = Join-Path (Get-VeriagentHome) 'results\runtime\escenarios'
New-Item -ItemType Directory -Force -Path $work | Out-Null
$file = Join-Path $work 'S2-lote.jsonl'
$total = $Colombia + $Peru
Invoke-DataGenerator -Arguments @('generate', '--mix', "CO:$Colombia,PE:$Peru", '--seed', "$Seed", '--offset', "$(200000 + $Seed * 10)", '--out', $file)

$before = (Invoke-VeriagentApi -Path '/api/invoices/stats' -Role OPERATOR).Body
$sw = [System.Diagnostics.Stopwatch]::StartNew()
$up = Send-VeriagentFile -Path '/api/ingest/batches?mode=now' -File $file -Role OPERATOR
$accepted = $sw.Elapsed.TotalSeconds
$after = Wait-VeriagentQuiet -TimeoutSeconds $TimeoutSeconds -ExpectedRegistered ($before.conservation.registered + $total)
$sw.Stop()

$c = $after.conservation
$newAuthorized = $c.authorized - $before.conservation.authorized
$newRegistered = $c.registered - $before.conservation.registered
$checks = [ordered]@{
    'Lote aceptado'                  = ($up.Status -in 200, 202)
    "Registrados $total"             = ($newRegistered -eq $total)
    "Autorizados $total"             = ($newAuthorized -eq $total)
    'Ninguno en tránsito'            = ($c.inFlight -eq 0)
    'Entradas = salidas + DLQ'       = [bool]$c.balanced
}
$result = [ordered]@{
    escenario         = 'S2'
    descripcion       = 'Lote asíncrono de comprobantes válidos'
    comprobantes      = $total
    mezcla            = @{ CO = $Colombia; PE = $Peru }
    ingesta           = $up.Body
    segundosAceptacion = [math]::Round($accepted, 2)
    segundosTotales   = [math]::Round($sw.Elapsed.TotalSeconds, 2)
    rendimientoPorSeg = [math]::Round($total / $sw.Elapsed.TotalSeconds, 1)
    conteoPorEstado   = $after.byStatus
    porPaisProveedor  = $after.byCountryProvider
    conservacion      = $c
    comprobaciones    = $checks
    aprobado          = -not ($checks.Values -contains $false)
}
$out = Write-ScenarioResult -Id 'S2' -Result $result
$checks.GetEnumerator() | ForEach-Object { '{0,-36} {1}' -f $_.Key, $(if ($_.Value) { 'OK' } else { 'FALLA' }) }
"$total comprobantes en $([math]::Round($sw.Elapsed.TotalSeconds, 1)) s ($($result.rendimientoPorSeg) por segundo). Resultado en $out"
if (-not $result.aprobado) { exit 1 }
