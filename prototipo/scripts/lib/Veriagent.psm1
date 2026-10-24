# Funciones comunes de los scripts del prototipo (PowerShell 5.1 o superior).
# Sin secretos: los tokens de demostración se leen del archivo local generado al arrancar (ignorado por git).

Set-StrictMode -Version Latest

function Get-VeriagentHome {
    return (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
}

function Get-VeriagentBaseUrl {
    if ($env:VERIAGENT_URL) { return $env:VERIAGENT_URL.TrimEnd('/') }
    return 'http://127.0.0.1:8080'
}

# Localiza un JDK 21: JAVA_HOME, luego un JDK portable en %USERPROFILE%\tools, luego el java del PATH.
function Initialize-Java {
    if ($env:JAVA_HOME -and (Test-Path (Join-Path $env:JAVA_HOME 'bin\java.exe'))) { return (Join-Path $env:JAVA_HOME 'bin\java.exe') }
    $portable = Get-ChildItem -Path (Join-Path $env:USERPROFILE 'tools') -Directory -Filter 'jdk-21*' -ErrorAction SilentlyContinue |
        Sort-Object Name -Descending | Select-Object -First 1
    if ($portable) {
        $env:JAVA_HOME = $portable.FullName
        $env:Path = (Join-Path $portable.FullName 'bin') + ';' + $env:Path
        return (Join-Path $portable.FullName 'bin\java.exe')
    }
    $cmd = Get-Command java -ErrorAction SilentlyContinue
    if ($cmd) { return $cmd.Source }
    throw 'No se encontró Java 21. Define JAVA_HOME o instala un JDK 21.'
}

function Get-PlatformJar {
    $jar = Join-Path (Get-VeriagentHome) 'integration-plane\platform-app\target\platform-app-0.1.0.jar'
    if (-not (Test-Path $jar)) { throw "No existe $jar. Ejecuta antes scripts\run-local.ps1 -Build (o scripts\demo.ps1 -Build)." }
    return $jar
}

# Compila el kit en verification-kit\build\classes, que el plano agéntico usa en modo lote (Maven no lo genera; el pipeline sí).
function Initialize-KitClasses {
    param([switch]$Force)
    $classes = Join-Path (Get-VeriagentHome) 'verification-kit\build\classes'
    if (-not $Force -and (Test-Path (Join-Path $classes 'org\tfg\kit\cli\Main.class'))) { return $classes }
    $java = Initialize-Java
    $javac = Join-Path (Split-Path $java) 'javac.exe'
    if (-not (Test-Path $javac)) { throw "No se encontró javac junto a $java. Hace falta un JDK 21, no un JRE." }
    if (Test-Path $classes) { Remove-Item -Recurse -Force $classes }
    New-Item -ItemType Directory -Force -Path $classes | Out-Null
    # Archivo de argumentos con barras normales y comillas: evita el límite de la línea de órdenes y las rutas con espacios.
    $argFile = Join-Path (Split-Path $classes) 'javac-sources.txt'
    Get-ChildItem (Join-Path (Get-VeriagentHome) 'verification-kit\src\main\java') -Recurse -Filter *.java |
        ForEach-Object { '"' + ($_.FullName -replace '\\', '/') + '"' } | Out-File -Encoding ascii $argFile
    & $javac -encoding UTF-8 -d $classes "@$argFile"
    if ($LASTEXITCODE -ne 0) { throw "javac terminó con código $LASTEXITCODE" }
    return $classes
}

# Token de demostración de un rol (REVIEWER, AGENT, OPERATOR, SYSTEM), leído del archivo local.
function Get-DemoToken {
    param([Parameter(Mandatory)][ValidateSet('REVIEWER', 'AGENT', 'OPERATOR', 'SYSTEM')][string]$Role, [string]$Subject)
    $file = Join-Path (Get-VeriagentHome) 'results\runtime\demo-tokens.json'
    if (-not (Test-Path $file)) { throw "No existe ${file}: arranca la plataforma con scripts\run-local.ps1." }
    $doc = Get-Content -Raw -Encoding UTF8 $file | ConvertFrom-Json
    $t = $doc.tokens | Where-Object { $_.role -eq $Role -and (-not $Subject -or $_.subject -eq $Subject) } | Select-Object -First 1
    if (-not $t) { throw "No hay token para el rol $Role." }
    return $t.token
}

function Invoke-VeriagentApi {
    param(
        [Parameter(Mandatory)][string]$Path,
        [string]$Method = 'GET',
        [string]$Role,
        $Body,
        [hashtable]$Headers = @{}
    )
    $h = @{} + $Headers
    if ($Role) { $h['Authorization'] = 'Bearer ' + (Get-DemoToken -Role $Role) }
    $params = @{ Uri = (Get-VeriagentBaseUrl) + $Path; Method = $Method; Headers = $h; UseBasicParsing = $true }
    if ($null -ne $Body) {
        $params['ContentType'] = 'application/json; charset=utf-8'
        $json = if ($Body -is [string]) { $Body } else { $Body | ConvertTo-Json -Depth 20 -Compress }
        $params['Body'] = [System.Text.Encoding]::UTF8.GetBytes($json)
    }
    try {
        $r = Invoke-WebRequest @params
        $content = [System.Text.Encoding]::UTF8.GetString($r.RawContentStream.ToArray())
        return [pscustomobject]@{ Status = [int]$r.StatusCode; Body = $(if ($content) { $content | ConvertFrom-Json } else { $null }) }
    } catch [System.Net.WebException] {
        $resp = $_.Exception.Response
        if (-not $resp) { throw }
        $reader = New-Object System.IO.StreamReader($resp.GetResponseStream(), [System.Text.Encoding]::UTF8)
        $content = $reader.ReadToEnd()
        $parsed = $null
        try { $parsed = $content | ConvertFrom-Json } catch { $parsed = $content }
        return [pscustomobject]@{ Status = [int]$resp.StatusCode; Body = $parsed }
    }
}

# Sube un archivo como multipart/form-data (PowerShell 5.1 no tiene -Form).
function Send-VeriagentFile {
    param([Parameter(Mandatory)][string]$Path, [Parameter(Mandatory)][string]$File, [Parameter(Mandatory)][string]$Role)
    Add-Type -AssemblyName System.Net.Http
    $client = New-Object System.Net.Http.HttpClient
    $client.Timeout = [TimeSpan]::FromMinutes(10)
    $client.DefaultRequestHeaders.Add('Authorization', 'Bearer ' + (Get-DemoToken -Role $Role))
    $form = New-Object System.Net.Http.MultipartFormDataContent
    $bytes = [System.IO.File]::ReadAllBytes($File)
    $part = New-Object System.Net.Http.ByteArrayContent(, $bytes)
    $form.Add($part, 'file', [System.IO.Path]::GetFileName($File))
    $r = $client.PostAsync((Get-VeriagentBaseUrl) + $Path, $form).Result
    $content = $r.Content.ReadAsStringAsync().Result
    $client.Dispose()
    return [pscustomobject]@{ Status = [int]$r.StatusCode; Body = $(if ($content) { $content | ConvertFrom-Json } else { $null }) }
}

function Wait-VeriagentHealth {
    param([int]$TimeoutSeconds = 120)
    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    while ((Get-Date) -lt $deadline) {
        try {
            $r = Invoke-WebRequest -Uri ((Get-VeriagentBaseUrl) + '/actuator/health') -UseBasicParsing -TimeoutSec 3
            if ($r.StatusCode -eq 200) { return $true }
        } catch { }
        Start-Sleep -Milliseconds 500
    }
    return $false
}

# Espera a que no queden comprobantes en tránsito (RECEIVED, PENDING_BATCH, SENT) y devuelve los indicadores.
function Wait-VeriagentQuiet {
    param([int]$TimeoutSeconds = 300, [long]$ExpectedRegistered = -1)
    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    do {
        $s = (Invoke-VeriagentApi -Path '/api/invoices/stats' -Role OPERATOR).Body
        $c = $s.conservation
        if ($c.inFlight -eq 0 -and ($ExpectedRegistered -lt 0 -or $c.registered -ge $ExpectedRegistered)) { return $s }
        Start-Sleep -Milliseconds 500
    } while ((Get-Date) -lt $deadline)
    return $s
}

# Ejecuta el generador de datos (incluido en el JAR de la plataforma).
function Invoke-DataGenerator {
    param([Parameter(Mandatory)][string[]]$Arguments)
    $java = Initialize-Java
    $jar = Get-PlatformJar
    & $java "-Dloader.main=org.veriagent.datagen.GeneratorCli" -cp $jar org.springframework.boot.loader.launch.PropertiesLauncher @Arguments --home (Get-VeriagentHome)
    if ($LASTEXITCODE -ne 0) { throw "El generador de datos terminó con código $LASTEXITCODE" }
}

# Python del plano agéntico (entorno virtual del proyecto).
function Get-AgenticPython {
    $py = Join-Path (Get-VeriagentHome) 'agentic-plane\.venv\Scripts\python.exe'
    if (-not (Test-Path $py)) { throw "No existe $py. Crea el entorno: cd agentic-plane; uv venv --python 3.12; uv pip install -e "".[test]""" }
    return $py
}

# Ejecuta escenarios del plano agéntico (S3 a S8, banco y rendimiento) contra la plataforma en marcha.
function Invoke-AgenticScenario {
    param([Parameter(Mandatory)][string[]]$Name)
    Initialize-Java | Out-Null
    $py = Get-AgenticPython
    Push-Location (Join-Path (Get-VeriagentHome) 'agentic-plane')
    try {
        & $py -m tfg_agents.scenarios @Name | Out-Host
        return $LASTEXITCODE
    } finally { Pop-Location }
}

# API de revisión de propuestas de IA en 127.0.0.1:8090 (proceso Python en segundo plano).
function Start-ReviewApi {
    $py = Join-Path (Get-VeriagentHome) 'agentic-plane\.venv\Scripts\python.exe'
    if (-not (Test-Path $py)) { Write-Host 'Sin entorno de Python: la pantalla de propuestas no estará disponible.'; return }
    $runtime = Join-Path (Get-VeriagentHome) 'results\runtime'
    $p = Start-Process -FilePath $py -ArgumentList @('-m', 'uvicorn', 'tfg_agents.review_api:app', '--host', '127.0.0.1', '--port', '8090') `
        -WorkingDirectory (Join-Path (Get-VeriagentHome) 'agentic-plane') -PassThru -WindowStyle Hidden `
        -RedirectStandardOutput (Join-Path $runtime 'review-api.log') -RedirectStandardError (Join-Path $runtime 'review-api.err.log')
    $p.Id | Out-File -Encoding ascii (Join-Path $runtime 'review-api.pid')
    Write-Host "API de revisión de propuestas en http://127.0.0.1:8090 (PID $($p.Id))"
}

function Stop-ReviewApi {
    $pidFile = Join-Path (Get-VeriagentHome) 'results\runtime\review-api.pid'
    if (-not (Test-Path $pidFile)) { return }
    $id = [int](Get-Content $pidFile)
    if (Get-Process -Id $id -ErrorAction SilentlyContinue) { Stop-Process -Id $id -Confirm:$false; Write-Host "API de revisión detenida (PID $id)." }
    Remove-Item $pidFile -Confirm:$false
}

# Los resultados de los escenarios se publican en results\escenarios (rastreado); las entradas generadas, en runtime.
function Write-ScenarioResult {
    param([Parameter(Mandatory)][string]$Id, [Parameter(Mandatory)]$Result)
    $dir = Join-Path (Get-VeriagentHome) 'results\escenarios'
    New-Item -ItemType Directory -Force -Path $dir | Out-Null
    $file = Join-Path $dir "$Id.json"
    $Result | ConvertTo-Json -Depth 20 | Out-File -Encoding utf8 $file
    return $file
}

Export-ModuleMember -Function *
