<#
.SYNOPSIS
  S1. Emisión síncrona correcta: autorizada en la misma llamada y con respuesta publicada.
.DESCRIPTION
  Requiere la plataforma en marcha (scripts\run-local.ps1 -Background). Genera un comprobante válido de Colombia
  con el generador (semilla fija), lo emite por POST /api/invoices con el rol OPERATOR y comprueba el estado
  final, el número de autorización, la respuesta publicada, el archivo legal y la idempotencia.
#>
param([long]$Seed = 101)
$ErrorActionPreference = 'Stop'
Import-Module (Join-Path $PSScriptRoot '..\lib\Veriagent.psm1') -Force

$work = Join-Path (Get-VeriagentHome) 'results\runtime\escenarios'
New-Item -ItemType Directory -Force -Path $work | Out-Null
$file = Join-Path $work 'S1-entrada.json'
Invoke-DataGenerator -Arguments @('generate', '--country', 'CO', '--count', '1', '--seed', "$Seed", '--offset', "$(100000 + $Seed)", '--out', $file)
$invoice = (Get-Content -Raw -Encoding UTF8 $file | ConvertFrom-Json)[0]
$body = $invoice | ConvertTo-Json -Depth 20 -Compress

$sw = [System.Diagnostics.Stopwatch]::StartNew()
$r = Invoke-VeriagentApi -Path '/api/invoices' -Method POST -Role OPERATOR -Body $body
$sw.Stop()
$again = Invoke-VeriagentApi -Path '/api/invoices' -Method POST -Role OPERATOR -Body $body
$key = $r.Body.invoiceKey
Start-Sleep -Milliseconds 1000
$detail = (Invoke-VeriagentApi -Path ('/api/invoices/' + [uri]::EscapeDataString($key)) -Role OPERATOR).Body
$deliveries = @((Invoke-VeriagentApi -Path '/api/publisher/deliveries?limit=500' -Role OPERATOR).Body | Where-Object { $_.invoice_key -eq $key })
$stats = (Invoke-VeriagentApi -Path '/api/invoices/stats' -Role OPERATOR).Body

$checks = [ordered]@{
    'HTTP 200'                         = ($r.Status -eq 200)
    'Estado AUTHORIZED'                = ($r.Body.status -eq 'AUTHORIZED')
    'Número de autorización'           = [bool]$r.Body.authorizationNumber
    'Repetir la emisión es idempotente' = ($again.Status -eq 200 -and $again.Body.duplicate -eq $true -and $again.Body.authorizationNumber -eq $r.Body.authorizationNumber)
    'Respuesta publicada'              = ($deliveries.Count -ge 1)
    'Archivo legal con huella'         = (@($detail.archive).Count -eq 1 -and [bool]$detail.archive[0].object_sha256)
    'Balance de conservación'          = [bool]$stats.conservation.balanced
}
$result = [ordered]@{
    escenario       = 'S1'
    descripcion     = 'Emisión síncrona correcta'
    comprobante     = $key
    estado          = $r.Body.status
    autorizacion    = $r.Body.authorizationNumber
    latenciaMs      = $sw.ElapsedMilliseconds
    historial       = $detail.timeline
    respuesta       = $deliveries
    archivoLegal    = $detail.archive
    conteoPorEstado = $stats.byStatus
    conservacion    = $stats.conservation
    comprobaciones  = $checks
    aprobado        = -not ($checks.Values -contains $false)
}
$out = Write-ScenarioResult -Id 'S1' -Result $result
$checks.GetEnumerator() | ForEach-Object { '{0,-36} {1}' -f $_.Key, $(if ($_.Value) { 'OK' } else { 'FALLA' }) }
"Latencia de la emisión: $($sw.ElapsedMilliseconds) ms. Resultado en $out"
if (-not $result.aprobado) { exit 1 }
