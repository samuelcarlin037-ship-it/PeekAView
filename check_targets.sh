#!/usr/bin/env bash
# check_targets.sh — verifica che tutti i target di PeekAView esistano
# ancora in projectzomboid.jar. Da lanciare dalla root del repo.
#
#   ./check_targets.sh
#   PZ_DIR="/c/Program Files (x86)/Steam/steamapps/common/ProjectZomboid" ./check_targets.sh
#
# Legge PZ_DIR da build.local se presente. Non modifica nulla.

set -uo pipefail   # niente -e: vogliamo continuare anche sui MISSING

PROJECT_ROOT="$(cd "$(dirname "$0")" && pwd)"
[ -f "$PROJECT_ROOT/build.local" ] && source "$PROJECT_ROOT/build.local"

if [ -z "${PZ_DIR:-}" ] || [ ! -f "$PZ_DIR/projectzomboid.jar" ]; then
    echo "ERRORE: projectzomboid.jar non trovato." >&2
    echo "  Imposta PZ_DIR in build.local o via env, es:" >&2
    echo "  PZ_DIR=\"/c/Program Files (x86)/Steam/steamapps/common/ProjectZomboid\"" >&2
    exit 1
fi
PZ_JAR="$PZ_DIR/projectzomboid.jar"

# javap: prima lo Zulu sotto tools/, poi quello nel PATH
JAVAP="$(ls "$PROJECT_ROOT"/tools/zulu*/bin/javap.exe 2>/dev/null | head -n1)"
[ -z "$JAVAP" ] && JAVAP="$(command -v javap || true)"
if [ -z "$JAVAP" ]; then
    echo "ERRORE: javap non trovato (ne' in tools/zulu*/bin/ ne' nel PATH)." >&2
    exit 1
fi

echo "PZ_JAR : $PZ_JAR"
echo "javap  : $JAVAP"
echo

# --- Target: "classe metodo1 metodo2 ..." ---
TARGETS=(
  "zombie.iso.fboRenderChunk.FBORenderCell isTranslucentTree renderInternal isPotentiallyObscuringObject renderPlayers"
  "zombie.iso.fboRenderChunk.FBORenderCutaways cutawayVisit"
  "zombie.iso.fboRenderChunk.FBORenderCutaways\$OrphanStructures shouldCutaway isAdjacentToOrphanStructure"
  "zombie.iso.fboRenderChunk.FBORenderTrees init"
  "zombie.iso.IsoCell GetSquaresAroundPlayerSquare drawStencilMask update renderInternal doBuildingInternal"
  "zombie.iso.IsoWorld renderInternal"
  "zombie.iso.IsoMovingObject getX getY getZ getCurrentSquare"
  "zombie.iso.IsoObject getAlpha"
  "zombie.iso.LightingJNI updatePlayer checkPlayerTorches"
  "zombie.iso.weather.fx.WeatherFxMask initMask renderFxMask"
  "zombie.characters.IsoGameCharacter renderlast"
  "zombie.characters.IsoPlayer render"
  "zombie.ui.UIManager getTileFromMouse"
)

# --- Campi letti in reflection (i piu' fragili: non sono API pubblica) ---
FIELDS=(
  "zombie.iso.IsoMovingObject x y z current"
  "zombie.iso.IsoWorld drawWorld"
)

fail=0
tmp="$(mktemp)"
trap 'rm -f "$tmp"' EXIT

dump_class() {   # $1 = fqcn ; scrive su $tmp ; ritorna 1 se la classe manca
    "$JAVAP" -p -classpath "$PZ_JAR" "$1" > "$tmp" 2>/dev/null
    [ -s "$tmp" ]
}

echo "=== METODI PATCHATI ==="
for entry in "${TARGETS[@]}"; do
    read -r cls methods <<< "$entry"
    if ! dump_class "$cls"; then
        echo "  [CLASSE ASSENTE] $cls"
        fail=$((fail + 1))
        continue
    fi
    echo "  $cls"
    for m in $methods; do
        # match del nome metodo seguito da '(' -> evita falsi positivi su prefissi
        if grep -qE "[ .]${m}\(" "$tmp"; then
            n=$(grep -cE "[ .]${m}\(" "$tmp")
            if [ "$n" -gt 1 ]; then
                echo "      OK       $m   (${n} overload - controlla la firma)"
            else
                echo "      OK       $m"
            fi
        else
            echo "      MANCANTE $m"
            fail=$((fail + 1))
        fi
    done
done

echo
echo "=== CAMPI IN REFLECTION ==="
for entry in "${FIELDS[@]}"; do
    read -r cls fields <<< "$entry"
    if ! dump_class "$cls"; then
        echo "  [CLASSE ASSENTE] $cls"
        fail=$((fail + 1))
        continue
    fi
    echo "  $cls"
    for f in $fields; do
        if grep -qE "[ .]${f};" "$tmp"; then
            echo "      OK       $f"
        else
            echo "      MANCANTE $f"
            fail=$((fail + 1))
        fi
    done
done

echo
if [ "$fail" -eq 0 ]; then
    echo "RISULTATO: tutti i target presenti. Le firme restano da verificare a mano."
else
    echo "RISULTATO: $fail target mancanti. Questi sono i punti da riscrivere."
fi
exit 0
