<#
.SYNOPSIS
  Demostración: arranca la plataforma local con estado limpio, siembra datos sintéticos y abre el front.
.DESCRIPTION
  Detiene una plataforma anterior, borra el estado local (como run-local.ps1 -Clean) y la base de propuestas de
  demostración (results\proposals.db), y arranca la plataforma con la API de revisión. Después siembra:
    - un lote de comprobantes válidos (por defecto 30 de Colombia y 20 de Perú) cargado por la ingesta;
    - una emisión síncrona autorizada;
    - un comprobante rechazado por el proveedor simulado Alfa, que queda en la DLQ;
    - dos propuestas de mapeo del agente (una bloqueada por el kit y otra pendiente de revisión).
  Al final muestra los tokens de demostración y abre http://127.0.0.1:8080/ en el navegador. La plataforma queda
  en marcha; se detiene con scripts\stop-local.ps1. Requiere el entorno de Python del plano agéntico. Con -Build
  compila antes (necesario la primera vez); con -NoAbrir no abre el navegador.
#>
param([switch]$Build, [switch]$NoAbrir, [int]$Colombia = 30, [int]$Peru = 20, [long]$Seed = 7)
$ErrorActionPreference = 'Stop'
Import-Module (Join-Path $PSScriptRoot 'lib\Veriagent.psm1') -Force
$home_ = Get-VeriagentHome
$py = Get-AgenticPython

& (Join-Path $PSScriptRoot 'stop-local.ps1')
$db = Join-Path $home_ 'results\proposals.db'
if (Test-Path $db) { Remove-Item -Force $db }
& (Join-Path $PSScriptRoot 'run-local.ps1') -Clean -Background -Build:$Build

$work = Join-Path $home_ 'results\runtime\demo'
New-Item -ItemType Directory -Force -Path $work | Out-Null

Write-Host '== Lote de comprobantes válidos'
$total = $Colombia + $Peru
$lote = Join-Path $work 'demo-lote.jsonl'
Invoke-DataGenerator -Arguments @('generate', '--mix', "CO:$Colombia,PE:$Peru", '--seed', "$Seed", '--offset', "$(700000 + $Seed * 10)", '--out', $lote)
$up = Send-VeriagentFile -Path '/api/ingest/batches?mode=now' -File $lote -Role OPERATOR
if ($up.Status -notin 200, 202) { throw "La ingesta respondió $($up.Status)." }
$st = Wait-VeriagentQuiet -TimeoutSeconds 300 -ExpectedRegistered $total
Write-Host "Registrados $($st.conservation.registered), autorizados $($st.conservation.authorized)"

Write-Host '== Emisión síncrona'
$f = Join-Path $work 'demo-sincrona.json'
Invoke-DataGenerator -Arguments @('generate', '--country', 'CO', '--count', '1', '--seed', "$($Seed + 1)", '--offset', "$(710000 + $Seed)", '--out', $f)
$inv = (Get-Content -Raw -Encoding UTF8 $f | ConvertFrom-Json)[0]
$r = Invoke-VeriagentApi -Path '/api/invoices' -Method POST -Role OPERATOR -Body ($inv | ConvertTo-Json -Depth 20 -Compress)
Write-Host "$($r.Body.invoiceKey): $($r.Body.status)"

Write-Host '== Rechazo del proveedor hacia la DLQ'
$f = Join-Path $work 'demo-rechazo.json'
Invoke-DataGenerator -Arguments @('generate', '--country', 'CO', '--count', '1', '--seed', "$($Seed + 2)", '--offset', "$(720000 + $Seed)", '--out', $f)
$inv = (Get-Content -Raw -Encoding UTF8 $f | ConvertFrom-Json)[0]
$fault = Invoke-VeriagentApi -Path '/api/simulators/alfa/faults' -Method POST -Role OPERATOR -Body @{ fault = 'reject-catalog'; remaining = 1 }
if ($fault.Status -ne 200) { throw "No se pudo programar el rechazo del simulador ($($fault.Status))." }
$r = Invoke-VeriagentApi -Path '/api/invoices' -Method POST -Role OPERATOR -Body ($inv | ConvertTo-Json -Depth 20 -Compress)
Invoke-VeriagentApi -Path '/api/simulators/alfa/faults' -Method DELETE -Role OPERATOR | Out-Null
Write-Host "$($r.Body.invoiceKey): HTTP $($r.Status) $($r.Body.reasonCode)"
$st = Wait-VeriagentQuiet -TimeoutSeconds 60
$dlq = @((Invoke-VeriagentApi -Path '/api/dlq?status=OPEN&limit=50' -Role OPERATOR).Body)
Write-Host "DLQ abierta: $($dlq.Count)"

Write-Host '== Propuestas de mapeo del agente'
Initialize-KitClasses | Out-Null
$env:Path = (Split-Path (Initialize-Java)) + ';' + $env:Path   # el kit en modo lote se invoca como «java» desde Python
Push-Location (Join-Path $home_ 'agentic-plane')
try {
    & $py -m tfg_agents.demo_seed
    if ($LASTEXITCODE -ne 0) { throw "La siembra de propuestas terminó con código $LASTEXITCODE" }
} finally { Pop-Location }

$url = (Get-VeriagentBaseUrl) + '/'
Write-Host ''
Write-Host "Front: $url"
Write-Host 'Tokens de demostración (aleatorios, cambian en cada arranque; pega uno en el front para entrar):'
foreach ($role in 'OPERATOR', 'REVIEWER', 'AGENT') { Write-Host ('  {0,-9} {1}' -f $role, (Get-DemoToken -Role $role)) }
Write-Host 'Para detener la plataforma: scripts\stop-local.ps1'
if (-not $NoAbrir) { Start-Process $url }
