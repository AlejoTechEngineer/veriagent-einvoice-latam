#!/usr/bin/env bash
# Gate de huellas de la normativa: recalcula el SHA-256 de cada especificación, catálogo y esquema y lo compara con
# el manifiesto versionado (ci/normativa.sha256). Falla si una huella difiere, si falta un documento del manifiesto
# o si hay un documento normativo que el manifiesto no lista.
# Uso: ci/gate-normative.sh [raíz del prototipo] [manifiesto]
set -euo pipefail
ROOT="${1:-$(cd "$(dirname "$0")/.." && pwd)}"
MANIFEST="${2:-$ROOT/ci/normativa.sha256}"
cd "$ROOT"
[ -f "$MANIFEST" ] || { echo "GATE FALLIDO: no existe el manifiesto $MANIFEST"; exit 1; }

expected=$(grep -v '^#' "$MANIFEST" | grep -v '^[[:space:]]*$' | awk '{print $2}' | sort)
actual=$(ls specs/*.md catalogs/*.csv schemas/* 2>/dev/null | sort)
fail=0
while read -r f; do
  [ -n "$f" ] || continue
  echo "$expected" | grep -qxF "$f" || { echo "GATE FALLIDO: $f no está en el manifiesto"; fail=1; }
done <<< "$actual"
while read -r f; do
  [ -n "$f" ] || continue
  [ -f "$f" ] || { echo "GATE FALLIDO: falta $f, listado en el manifiesto"; fail=1; }
done <<< "$expected"
while read -r sum f; do
  [ -f "$f" ] || continue
  got=$(sha256sum "$f" | awk '{print $1}')
  if [ "$got" = "$sum" ]; then echo "  OK      $f"
  else echo "  DIFIERE $f (manifiesto ${sum:0:12}..., calculada ${got:0:12}...)"; fail=1; fi
done < <(grep -v '^#' "$MANIFEST" | grep -v '^[[:space:]]*$')
[ "$fail" -eq 0 ] || { echo "GATE FALLIDO: la normativa no coincide con el manifiesto"; exit 1; }
echo "GATE OK: $(echo "$expected" | wc -l | tr -d ' ') documentos normativos sin cambios"
