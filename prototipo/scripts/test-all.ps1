<#
.SYNOPSIS
  Ejecuta las pruebas automáticas del prototipo: el reactor Maven (mvn verify, incluye el kit) y pytest del plano agéntico.
.DESCRIPTION
  No necesita la plataforma en marcha ni Docker. Antes de pytest compila las clases del kit en
  verification-kit\build\classes, que las pruebas del modo lote necesitan y Maven no genera. Requiere JDK 21, Maven
  y el entorno de Python del plano agéntico (cd agentic-plane; uv venv --python 3.12; uv pip install -e ".[test]").
  Con -SoloJava o -SoloPython ejecuta solo una parte. Termina con código 1 si alguna parte falla.
#>
param([switch]$SoloJava, [switch]$SoloPython)
$ErrorActionPreference = 'Stop'
Import-Module (Join-Path $PSScriptRoot 'lib\Veriagent.psm1') -Force
$home_ = Get-VeriagentHome
$java = Initialize-Java
$release = Join-Path (Split-Path (Split-Path $java)) 'release'
if ((Test-Path $release) -and -not (Select-String -Path $release -Pattern 'JAVA_VERSION="21' -Quiet)) {
    throw "El Java en uso ($java) no es la versión 21. Apunta JAVA_HOME a un JDK 21."
}
$fallos = @()
$t0 = Get-Date

if (-not $SoloPython) {
    Write-Host '== Pruebas Java: mvn verify'
    & mvn -B -q -f (Join-Path $home_ 'integration-plane\pom.xml') verify
    if ($LASTEXITCODE -ne 0) { $fallos += 'Maven' }
    # Resumen de los informes de surefire escritos en esta ejecución.
    $n = 0; $f = 0; $e = 0; $s = 0; $suites = 0
    Get-ChildItem -Path $home_ -Recurse -Filter 'TEST-*.xml' -ErrorAction SilentlyContinue |
        Where-Object { $_.FullName -match 'surefire-reports' -and $_.LastWriteTime -ge $t0 } | ForEach-Object {
            [xml]$x = Get-Content -Raw $_.FullName
            $suites++; $n += [int]$x.testsuite.tests; $f += [int]$x.testsuite.failures; $e += [int]$x.testsuite.errors; $s += [int]$x.testsuite.skipped
        }
    Write-Host "Maven: $suites suites, $n pruebas, $f fallos, $e errores, $s omitidas"
}

if (-not $SoloJava) {
    Write-Host '== Clases del kit para el modo lote (javac)'
    Initialize-KitClasses -Force | Out-Null
    Write-Host '== Pruebas Python: pytest'
    $py = Get-AgenticPython
    $env:Path = (Split-Path $java) + ';' + $env:Path      # el kit en modo lote se invoca como «java» desde Python
    Push-Location (Join-Path $home_ 'agentic-plane')
    try {
        & $py -m pytest -q
        if ($LASTEXITCODE -ne 0) { $fallos += 'pytest' }
    } finally { Pop-Location }
}

$seg = [math]::Round(((Get-Date) - $t0).TotalSeconds)
if ($fallos.Count -gt 0) { Write-Host "Fallaron: $($fallos -join ', ') ($seg s)"; exit 1 }
Write-Host "Todas las pruebas pasaron ($seg s)."
