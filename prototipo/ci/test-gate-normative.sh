#!/usr/bin/env bash
# Prueba del gate de huellas de la normativa sobre una copia temporal: el estado limpio pasa y cada alteración
# (un byte en una especificación, en un catálogo o en un esquema, un documento borrado o uno no declarado) lo hace
# fallar. No toca los archivos del repositorio.
set -uo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
GATE="$ROOT/ci/gate-normative.sh"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
pass=0; fail=0

fresh() {
  rm -rf "$WORK/p" && mkdir -p "$WORK/p/ci"
  cp -r "$ROOT/specs" "$ROOT/catalogs" "$ROOT/schemas" "$WORK/p/"
  cp "$ROOT/ci/normativa.sha256" "$WORK/p/ci/"
}

expect() {  # expect <código esperado: 0 pasa, 1 falla> <caso>
  local want=$1 name=$2 out code
  out=$(bash "$GATE" "$WORK/p" 2>&1); code=$?
  [ "$code" -ne 0 ] && code=1
  if [ "$code" -eq "$want" ]; then
    echo "  OK    $name"; pass=$((pass + 1))
  else
    echo "  FALLA $name (código $code, se esperaba $want)"; echo "$out" | sed 's/^/        /'; fail=$((fail + 1))
  fi
}

echo "Prueba del gate de huellas de la normativa"
fresh; expect 0 "estado limpio: el gate pasa"
fresh; printf ' ' >> "$WORK/p/specs/perfil-alfa-v2.1.md"; expect 1 "un espacio añadido a una especificación"
fresh; sed -i '2s/,/;/' "$WORK/p/catalogs/catalogos-beta-pe-v1.csv"; expect 1 "una fila alterada en un catálogo"
fresh; sed -i 's/"string"/"number"/' "$WORK/p/schemas/gamma-v1.schema.json"; expect 1 "un tipo cambiado en un esquema"
fresh; sed -i 's/$/\r/' "$WORK/p/specs/proveedor-gamma-v1.md"; expect 1 "finales de línea CRLF en una especificación"
fresh; rm "$WORK/p/catalogs/catalogos-gamma-v1.csv"; expect 1 "un catálogo borrado"
fresh; echo "codigo,descripcion" > "$WORK/p/catalogs/catalogos-nuevo.csv"; expect 1 "un catálogo nuevo sin declarar en el manifiesto"
fresh; expect 0 "estado limpio de nuevo: el gate pasa"

echo "Resultado: $pass casos correctos, $fail incorrectos"
[ "$fail" -eq 0 ]
