#!/usr/bin/env bash
# Etapas del pipeline. Las ejecuta Jenkins (Jenkinsfile) y también se pueden correr en local.
# Uso: ci/run-pipeline.sh [etapa...]   etapas: build test-java test-python gate-bank gate-mapping package smoke
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"
JUNIT_JAR="${JUNIT_JAR:-/usr/share/java/junit-platform-console-standalone.jar}"
FX_LIB="${FX_LIB:-/usr/share/openjfx/lib}"
REPORTS="$ROOT/build-reports"; mkdir -p "$REPORTS"
MIN_RUNTIME_DETECTION="${MIN_RUNTIME_DETECTION:-0.80}"
STAGES=("$@"); [ ${#STAGES[@]} -eq 0 ] && STAGES=(build test-java test-python gate-bank gate-mapping package smoke)
log() { printf '\n==> [%s] %s\n' "$(date +%H:%M:%S)" "$*"; }

for st in "${STAGES[@]}"; do case "$st" in
  build)
    log "Compilar kit de verificación (Java 21, solo JDK)"
    rm -rf verification-kit/build && mkdir -p verification-kit/build/classes
    javac -Xlint:all -Werror -d verification-kit/build/classes $(find verification-kit/src/main/java -name '*.java')
    if [ -d "$FX_LIB" ]; then
      log "Compilar consola JavaFX"
      rm -rf review-console-javafx/build && mkdir -p review-console-javafx/build/classes
      javac --module-path "$FX_LIB" --add-modules javafx.controls -d review-console-javafx/build/classes $(find review-console-javafx/src/main/java -name '*.java')
      cp -r review-console-javafx/src/main/resources/* review-console-javafx/build/classes/
    fi ;;
  test-java)
    log "Pruebas unitarias del kit (JUnit 5)"
    mkdir -p verification-kit/build/test-classes
    javac -d verification-kit/build/test-classes -cp "verification-kit/build/classes:$JUNIT_JAR" $(find verification-kit/src/test/java -name '*.java')
    (cd verification-kit && java -Dkit.home=.. -jar "$JUNIT_JAR" execute --class-path build/classes:build/test-classes \
      --select-package org.tfg.kit --reports-dir "$REPORTS/junit-java" --disable-banner --details=summary) ;;
  test-python)
    log "Pruebas del plano agéntico (pytest + hypothesis)"
    (cd agentic-plane && python3 -m pytest -q --junitxml="$REPORTS/junit-python.xml") ;;
  gate-bank)
    log "Gate: banco de defectos por inyección (umbral de detección en ejecución $MIN_RUNTIME_DETECTION)"
    (cd agentic-plane && python3 -m tfg_agents.evaluation --docs 300 --out "$REPORTS/defect-bank" >/dev/null)
    python3 - "$REPORTS/defect-bank/evaluation.json" "$MIN_RUNTIME_DETECTION" <<'PY'
import json, sys
r = json.load(open(sys.argv[1])); t = r["total"]; thr = float(sys.argv[2])
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
    log "Gate: el mapeo vigente pasa el kit en modo CI sobre los fixtures validados"
    (cd agentic-plane && python3 - <<'PY'
from tfg_agents.orchestrator import verify_proposal, GOLDEN
from tfg_agents.mapping import load_mapping
v = verify_proposal(load_mapping(GOLDEN), sample=100)
print(f"documentos={v['documents']} con hallazgos={v['failedDocuments']}")
assert v["gatePassed"], v["examples"]
print("GATE OK")
PY
    ) ;;
  package)
    log "Empaquetar kit como JAR ejecutable"
    mkdir -p dist
    printf 'Main-Class: org.tfg.kit.cli.Main\nImplementation-Title: tfg-verification-kit\nImplementation-Version: 0.1.0\n' > verification-kit/build/MANIFEST.MF
    jar --create --file dist/verification-kit-0.1.0.jar --manifest verification-kit/build/MANIFEST.MF -C verification-kit/build/classes .
    sha256sum dist/verification-kit-0.1.0.jar | tee dist/verification-kit-0.1.0.jar.sha256 ;;
  smoke)
    log "Prueba de humo: servicio HTTP del kit"
    java -jar dist/verification-kit-0.1.0.jar serve --home "$ROOT" --port 18085 & PID=$!
    trap 'kill $PID 2>/dev/null || true' EXIT
    for i in $(seq 1 30); do curl -sf localhost:18085/health >/dev/null && break; sleep 0.3; done
    curl -sf localhost:18085/health; echo
    code=$(curl -s -o /tmp/smoke.json -w '%{http_code}' -X POST 'localhost:18085/verify?format=xml&mode=RUNTIME' \
      --data-binary @verification-kit/src/test/resources/factura-valida.xml)
    [ "$code" = "200" ] || { echo "esperaba 200 y llegó $code"; cat /tmp/smoke.json; exit 1; }
    code=$(curl -s -o /tmp/smoke2.json -w '%{http_code}' -X POST 'localhost:18085/verify?format=xml' --data-binary '<Invoice xmlns="urn:tfg:alfa:invoice:2"/>')
    [ "$code" = "422" ] || { echo "esperaba 422 y llegó $code"; exit 1; }
    echo "Humo OK: válido=200, inválido=422"
    kill $PID; trap - EXIT ;;
  *) echo "Etapa desconocida: $st"; exit 64 ;;
esac; done
log "Pipeline completo"
