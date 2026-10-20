#!/usr/bin/env bash
# Etapas del pipeline del prototipo. Las ejecuta Jenkins (Jenkinsfile) y también se pueden correr en local (Linux o
# Git Bash en Windows). Lo que corre en Jenkins es exactamente lo que corre en local.
# Uso: ci/run-pipeline.sh [etapa...]
#   build test-java test-python gate-secrets gate-deps gate-normative test-gate-normative gate-bank gate-mapping package smoke
#   normative-manifest (regenera ci/normativa.sha256 tras un cambio de normativa revisado)
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"
REPORTS="$ROOT/build-reports"; mkdir -p "$REPORTS"
FX_LIB="${FX_LIB:-/usr/share/openjfx/lib}"
MIN_RUNTIME_DETECTION="${MIN_RUNTIME_DETECTION:-0.80}"
SMOKE_PORT="${SMOKE_PORT:-18080}"
if [ -z "${PYTHON:-}" ]; then
  if [ -x agentic-plane/.venv/bin/python ]; then PYTHON=agentic-plane/.venv/bin/python
  elif [ -x agentic-plane/.venv/Scripts/python.exe ]; then PYTHON=agentic-plane/.venv/Scripts/python.exe
  else PYTHON="$(command -v python3)"; fi
fi
PYTHON="$(cd "$(dirname "$PYTHON")" && pwd)/$(basename "$PYTHON")"
MVN=(mvn -B -q -f integration-plane/pom.xml)
STAGES=("$@"); [ ${#STAGES[@]} -eq 0 ] && STAGES=(build test-java test-python gate-secrets gate-deps gate-normative test-gate-normative gate-bank gate-mapping package smoke)
log() { printf '\n==> [%s] %s\n' "$(date +%H:%M:%S)" "$*"; }

for st in "${STAGES[@]}"; do case "$st" in
  build)
    log "Compilar el plano de integración y el kit (Maven, Java 21)"
    "${MVN[@]}" install -DskipTests
    log "Clases del kit para el plano agéntico (verificación por lotes del banco de defectos)"
    rm -rf verification-kit/build && mkdir -p verification-kit/build/classes
    javac -Xlint:all -d verification-kit/build/classes $(find verification-kit/src/main/java -name '*.java')
    if [ -d "$FX_LIB" ]; then
      log "Compilar la consola JavaFX"
      rm -rf review-console-javafx/build && mkdir -p review-console-javafx/build/classes
      javac --module-path "$FX_LIB" --add-modules javafx.controls -d review-console-javafx/build/classes $(find review-console-javafx/src/main/java -name '*.java')
      cp -r review-console-javafx/src/main/resources/* review-console-javafx/build/classes/
    else
      echo "OpenJFX no disponible en $FX_LIB: la consola JavaFX no se compila en esta etapa"
    fi ;;
  test-java)
    log "Pruebas Java (JUnit 5, todo el reactor; el kit corre con las configuraciones regionales es y en)"
    "${MVN[@]}" verify
    rm -rf "$REPORTS/junit-java" && mkdir -p "$REPORTS/junit-java"
    find . -path '*/target/surefire-reports/*.xml' -exec cp {} "$REPORTS/junit-java/" \;
    echo "Reportes JUnit: $(ls "$REPORTS/junit-java" | wc -l)" ;;
  test-python)
    log "Pruebas del plano agéntico (pytest)"
    (cd agentic-plane && "$PYTHON" -m pytest -q --junitxml="$REPORTS/junit-python.xml") ;;
  gate-secrets)
    log "Gate: secretos en archivos rastreados (claves, tokens, llaves privadas, correos personales, tokens de demostración)"
    "$PYTHON" - <<'PY'
import re, subprocess, sys
files = subprocess.run(["git", "ls-files", "."], capture_output=True, text=True, check=True).stdout.split("\n")
secret = re.compile(r"(sk-ant-[A-Za-z0-9_-]{10,}|ghp_[A-Za-z0-9]{20,}|github_pat_[A-Za-z0-9_]{10,}|AKIA[0-9A-Z]{16}|"
                    r"-----BEGIN [A-Z ]*PRIVATE KEY|xox[baprs]-[A-Za-z0-9-]{10,}|demo_[A-Za-z0-9_-]{30,}|"
                    r"(api[_-]?key|secret|token|passw(or)?d)[\"' ]*[:=][\"' ]*[A-Za-z0-9/+_-]{16,})")
allowed = re.compile(r"placeholder|<[A-Z_]+>|\$\{|CAMBIAR|example|ejemplo|os\.environ|getenv|System\.getenv")
mail = re.compile(r"[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}")
mail_ok = re.compile(r"@[A-Za-z0-9.-]*\.(test|example|invalid)$|@example\.(com|org|net)$|^noreply@")
bad, checked = [], 0
for f in filter(None, files):
    if f.endswith((".png", ".jpg", ".jar", ".db")):
        continue
    if f.endswith("demo-tokens.json") or f.endswith(".env") or f.startswith("results/runtime/"):
        bad.append(f"{f}: archivo de credenciales o de ejecución rastreado")
        continue
    try:
        text = open(f, encoding="utf-8").read()
    except (UnicodeDecodeError, FileNotFoundError):
        continue
    checked += 1
    for n, line in enumerate(text.splitlines(), 1):
        if secret.search(line) and not allowed.search(line):
            bad.append(f"{f}:{n}: posible secreto")
        for m in mail.findall(line):
            if not mail_ok.search(m):
                bad.append(f"{f}:{n}: correo no reservado")
print(f"archivos de texto revisados: {checked}")
if bad:
    print("\n".join(bad[:50]))
    sys.exit("GATE FALLIDO: secretos o datos personales en archivos rastreados")
print("GATE OK")
PY
    ;;
  gate-deps)
    log "Gate: dependencias (versiones fijas, sin SNAPSHOT ni rangos; entorno de Python consistente con pyproject)"
    if grep -rhoE '<version>[^<]*(SNAPSHOT|\[|\(|,)[^<]*</version>' --include=pom.xml integration-plane verification-kit data-generator; then
      echo "GATE FALLIDO: versiones SNAPSHOT o rangos en los pom.xml"; exit 1
    fi
    "${MVN[@]}" dependency:list -DoutputFile="$REPORTS/deps-maven.txt" -DincludeScope=runtime >/dev/null
    echo "Dependencias de ejecución resueltas por módulo: $(find integration-plane data-generator verification-kit -name deps-maven.txt -path '*build-reports*' 2>/dev/null | wc -l) listas"
    "$PYTHON" - <<'PY'
import sys, tomllib
from importlib.metadata import PackageNotFoundError, version
from packaging.requirements import Requirement
deps = tomllib.load(open("agentic-plane/pyproject.toml", "rb"))["project"]["dependencies"]
bad = []
for d in deps:
    r = Requirement(d)
    try:
        v = version(r.name)
    except PackageNotFoundError:
        bad.append(f"{r.name}: no instalado"); continue
    if r.specifier and v not in r.specifier:
        bad.append(f"{r.name} {v} no cumple {r.specifier}")
    print(f"  {r.name} {v} ({r.specifier or 'sin restricción'})")
if bad:
    sys.exit("GATE FALLIDO: " + "; ".join(bad))
PY
    if command -v trivy >/dev/null; then
      trivy fs --exit-code 1 --severity HIGH,CRITICAL --scanners vuln .
    else
      echo "AVISO: trivy no está instalado; el escaneo de vulnerabilidades conocidas no se ejecutó en esta etapa (en Jenkins es obligatorio)"
    fi
    echo "GATE OK" ;;
  gate-normative)
    log "Gate: huellas SHA-256 de la normativa (ci/normativa.sha256)"
    bash ci/gate-normative.sh "$ROOT" ;;
  test-gate-normative)
    log "Prueba del gate de huellas: el estado limpio pasa y una alteración lo hace fallar"
    bash ci/test-gate-normative.sh ;;
  normative-manifest)
    log "Regenerar el manifiesto de huellas de la normativa"
    { echo "# Huellas SHA-256 de la normativa sintética (especificaciones, catálogos y esquemas), con finales de línea LF"
      echo "# (.gitattributes). El gate gate-normative falla si cambian sin actualizar este manifiesto."
      echo "# Regenerar: ci/run-pipeline.sh normative-manifest"
      sha256sum specs/*.md catalogs/*.csv schemas/* | sed 's/ \*/  /'; } > ci/normativa.sha256
    cat ci/normativa.sha256 ;;
  gate-bank)
    log "Gate: banco de 22 defectos sobre el kit (umbral de detección en ejecución $MIN_RUNTIME_DETECTION)"
    (cd agentic-plane && "$PYTHON" -m tfg_agents.evaluation --docs 300 --out "$REPORTS/defect-bank" >/dev/null)
    "$PYTHON" - "$REPORTS/defect-bank/evaluation.json" "$MIN_RUNTIME_DETECTION" <<'PY'
import json, sys
r = json.load(open(sys.argv[1], encoding="utf-8")); t = r["total"]; thr = float(sys.argv[2])
rt = t["runtime"] / t["mutants"]
print(f"mutantes={t['mutants']} esquema={t['schema']} ejecución={t['runtime']} ({rt:.0%}) ci={t['ci']} FP={r['falsePositives']}")
fails = []
if t["ci"] != t["mutants"]: fails.append("el gate de CI no detectó todos los mutantes")
if rt < thr: fails.append(f"detección en ejecución {rt:.0%} por debajo del umbral {thr:.0%}")
if any(r["falsePositives"].values()): fails.append("hay falsos positivos sobre salidas correctas")
if fails: sys.exit("GATE FALLIDO: " + "; ".join(fails))
print("GATE OK")
PY
    ;;
  gate-mapping)
    log "Gate: el mapeo vigente del Perfil Alfa pasa el kit en modo CI sobre los ejemplos validados"
    (cd agentic-plane && "$PYTHON" - <<'PY'
from tfg_agents.orchestrator import verify_proposal, GOLDEN
from tfg_agents.mapping import load_mapping
v = verify_proposal(load_mapping(GOLDEN), sample=100)
print(f"documentos={v['documents']} con hallazgos={v['failedDocuments']}")
assert v["gatePassed"], v["examples"]
print("GATE OK")
PY
    ) ;;
  package)
    log "Empaquetar la plataforma (JAR de Spring Boot) y el kit"
    "${MVN[@]}" package -DskipTests -pl platform-app -am
    mkdir -p dist
    cp integration-plane/platform-app/target/platform-app-0.1.0.jar dist/
    printf 'Main-Class: org.tfg.kit.cli.Main\nImplementation-Title: tfg-verification-kit\nImplementation-Version: 0.2.0\n' > verification-kit/build/MANIFEST.MF
    jar --create --file dist/verification-kit-0.2.0.jar --manifest verification-kit/build/MANIFEST.MF -C verification-kit/build/classes .
    (cd dist && sha256sum platform-app-0.1.0.jar verification-kit-0.2.0.jar | tee SHA256SUMS) ;;
  smoke)
    log "Prueba de humo: la plataforma en el perfil local en el puerto $SMOKE_PORT"
    SMOKE_HOME="$(mktemp -d)"; mkdir -p "$SMOKE_HOME/results/runtime"
    cp -r specs catalogs schemas data "$SMOKE_HOME/"
    mkdir -p "$SMOKE_HOME/agentic-plane" && cp -r agentic-plane/golden "$SMOKE_HOME/agentic-plane/"
    VERIAGENT_HOME="$SMOKE_HOME" java -jar dist/platform-app-0.1.0.jar --spring.profiles.active=local --server.port="$SMOKE_PORT" \
      --veriagent.providers.endpoints.alfa.base-url="http://127.0.0.1:$SMOKE_PORT/sim/alfa" \
      >"$REPORTS/smoke.log" 2>&1 & PID=$!
    trap 'kill $PID 2>/dev/null || true' EXIT
    URL="http://127.0.0.1:$SMOKE_PORT"
    for i in $(seq 1 120); do curl -sf "$URL/actuator/health" >/dev/null 2>&1 && break; sleep 0.5; done
    curl -sf "$URL/actuator/health"; echo
    TOKENS="$SMOKE_HOME/results/runtime/demo-tokens.json"
    OP="$("$PYTHON" -c "import json,sys; print(next(t['token'] for t in json.load(open(sys.argv[1]))['tokens'] if t['role']=='OPERATOR'))" "$TOKENS")"
    code=$(curl -s -o /dev/null -w '%{http_code}' "$URL/api/invoices/stats"); [ "$code" = "401" ] || { echo "sin token: esperaba 401 y llegó $code"; exit 1; }
    code=$(curl -s -o /dev/null -w '%{http_code}' -H "Authorization: Bearer $OP" "$URL/api/invoices/stats"); [ "$code" = "200" ] || { echo "con token: esperaba 200 y llegó $code"; exit 1; }
    "$PYTHON" - "$URL" "$OP" <<'PY'
import json, sys, urllib.request
url, tok = sys.argv[1], sys.argv[2]
fx = json.load(open("data/fixtures/alfa-CO.json", encoding="utf-8"))["fixtures"][0]
req = urllib.request.Request(url + "/api/invoices", data=json.dumps(fx["canonical"]).encode(), method="POST",
                             headers={"Authorization": "Bearer " + tok, "Content-Type": "application/json"})
r = json.load(urllib.request.urlopen(req, timeout=60))
assert r["status"] == "AUTHORIZED", r
print("Emisión síncrona de humo:", r["status"], r["authorizationNumber"])
profiles = json.load(urllib.request.urlopen(urllib.request.Request(url + "/api/kit/profiles", headers={"Authorization": "Bearer " + tok})))
manifest = {l.split()[1].split("/")[-1]: l.split()[0] for l in open("ci/normativa.sha256", encoding="utf-8") if not l.startswith("#")}
for p in profiles:
    assert manifest.get(p["specDocument"]) == p["specSha256"], f"la huella del perfil {p['id']} no coincide con el manifiesto"
print("Huellas de las especificaciones del kit en ejecución = manifiesto:", ", ".join(p["id"] for p in profiles))
PY
    echo "Humo OK"
    kill $PID; trap - EXIT ;;
  *) echo "Etapa desconocida: $st"; exit 64 ;;
esac; done
log "Pipeline completo"
