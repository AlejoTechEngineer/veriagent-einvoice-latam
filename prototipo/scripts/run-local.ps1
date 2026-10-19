<#
.SYNOPSIS
  Arranca la plataforma en el perfil local: un solo proceso, bus en memoria, H2 en archivo, sin Docker.
.DESCRIPTION
  Escucha solo en 127.0.0.1:8080. Genera tokens de demostración aleatorios en results\runtime\demo-tokens.json
  (ignorado por git). Con -Build compila antes; con -Clean borra el estado local (base H2 y carpetas SFTP).
  Con -Background deja el proceso en segundo plano y guarda su PID en results\runtime\app.pid.
#>
param([switch]$Build, [switch]$Clean, [switch]$Background)

$ErrorActionPreference = 'Stop'
Import-Module (Join-Path $PSScriptRoot 'lib\Veriagent.psm1') -Force
$home_ = Get-VeriagentHome
$java = Initialize-Java

if ($Build) {
    Push-Location (Join-Path $home_ 'integration-plane')
    try {
        & mvn -q install -DskipTests
        if ($LASTEXITCODE -ne 0) { throw 'La compilación falló.' }
    } finally { Pop-Location }
}
$jar = Get-PlatformJar
$runtime = Join-Path $home_ 'results\runtime'
if ($Clean) {
    foreach ($d in 'h2', 'sftp', 'archive', 'inbox', 'escenarios') {
        $p = Join-Path $runtime $d
        if (Test-Path $p) { Remove-Item -Recurse -Force $p }
    }
}
New-Item -ItemType Directory -Force -Path $runtime | Out-Null

$env:VERIAGENT_HOME = $home_
$args_ = @('-jar', $jar, '--spring.profiles.active=local')
if ($Background) {
    $log = Join-Path $runtime 'app.log'
    # Start-Process no entrecomilla los argumentos: la ruta puede tener espacios.
    $quoted = $args_ | ForEach-Object { if ($_ -match '\s') { '"' + $_ + '"' } else { $_ } }
    $p = Start-Process -FilePath $java -ArgumentList $quoted -WorkingDirectory $home_ -PassThru -WindowStyle Hidden `
        -RedirectStandardOutput $log -RedirectStandardError (Join-Path $runtime 'app.err.log')
    $p.Id | Out-File -Encoding ascii (Join-Path $runtime 'app.pid')
    Write-Host "Plataforma arrancando (PID $($p.Id)); registro en $log"
    if (-not (Wait-VeriagentHealth -TimeoutSeconds 180)) { throw 'La plataforma no respondió a tiempo; revisa el registro.' }
    Write-Host "Plataforma lista en $(Get-VeriagentBaseUrl)"
} else {
    Push-Location $home_
    try { & $java @args_ } finally { Pop-Location }
}
