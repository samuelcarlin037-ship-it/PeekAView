#!/usr/bin/env bash
set -euo pipefail

# --- Paths ---
PROJECT_ROOT="$(cd "$(dirname "$0")" && pwd)"
SRC_DIR="$PROJECT_ROOT/src"
BUILD_DIR="${BUILD_DIR:-$PROJECT_ROOT/build}"
CLASSES_DIR="$BUILD_DIR/classes"
JAR_OUT="$BUILD_DIR/peekaview.jar"

# Per-machine overrides — create build.local (gitignored) to set PZ_DIR etc.
# See build.local.example for the template.
if [ -f "$PROJECT_ROOT/build.local" ]; then
    # shellcheck disable=SC1091
    source "$PROJECT_ROOT/build.local"
fi

if [ -z "${PZ_DIR:-}" ] || [ ! -f "$PZ_DIR/projectzomboid.jar" ]; then
    echo "[build] ERROR: Project Zomboid install not found." >&2
    echo "        Set PZ_DIR in build.local, e.g.:" >&2
    echo "          cp build.local.example build.local" >&2
    echo "          # then edit build.local to match your Steam install" >&2
    echo "        or via env:" >&2
    echo "          PZ_DIR=/d/Steam/steamapps/common/ProjectZomboid ./build.sh" >&2
    exit 1
fi

# If PZ is running, the JVM holds a Windows file lock on peekaview.jar.
# The install step at the bottom does `rm -rf MOD_INSTALL_ROOT` followed
# by `cp -r STAGE MOD_INSTALL_ROOT` — rm fails on the locked JAR mid-tree
# and `set -e` exits the script before cp runs, leaving the deployed
# mod folder with only the JAR plus empty path skeletons. Pre-flight
# check here aborts cleanly with a clear message instead.
# Skip on non-Windows shells (no tasklist) or with SKIP_PZ_CHECK=1.
if [ -z "${SKIP_PZ_CHECK:-}" ] && command -v tasklist >/dev/null 2>&1; then
    if tasklist //FI "IMAGENAME eq ProjectZomboid64.exe" //FO CSV //NH 2>/dev/null | grep -qi ProjectZomboid64; then
        echo "[build] ERROR: Project Zomboid is running. Close it before building." >&2
        echo "        Otherwise the install step would leave the mod folder half-deployed:" >&2
        echo "        rm -rf hits the locked JAR mid-tree, set -e aborts before cp -r." >&2
        echo "        Override with SKIP_PZ_CHECK=1 if you know what you're doing." >&2
        exit 1
    fi
fi

: "${MOD_INSTALL_ROOT:=$USERPROFILE/Zomboid/mods/PeekAView}"

PZ_JAR="$PZ_DIR/projectzomboid.jar"
ZB_JAR="$PZ_DIR/ZombieBuddy.jar"

# Pick the Zulu JDK under tools/ (glob tolerates version bumps).
JDK_DIR="$(ls -d "$PROJECT_ROOT"/tools/zulu*-win_x64 2>/dev/null | head -n 1)"
if [ -z "$JDK_DIR" ]; then
    echo "[build] ERROR: no Zulu JDK found under $PROJECT_ROOT/tools/zulu*-win_x64" >&2
    echo "        Download a Zulu JDK 25 Windows x64 build from https://www.azul.com/downloads/" >&2
    echo "        and extract it into tools/ so that tools/zulu25.../bin/javac.exe exists." >&2
    exit 1
fi
JAVAC="$JDK_DIR/bin/javac.exe"
JAR="$JDK_DIR/bin/jar.exe"

# --- Clean ---
rm -rf "$BUILD_DIR"
mkdir -p "$CLASSES_DIR"

# Windows/MSYS: la lista di path separata da ";" non viene convertita
# automaticamente, quindi javac.exe riceverebbe path stile /c/... e non
# troverebbe i jar. Convertiamo esplicitamente con cygpath.
if command -v cygpath >/dev/null 2>&1; then
    CLASSPATH_ARG="$(cygpath -w "$PZ_JAR");$(cygpath -w "$ZB_JAR")"
else
    CLASSPATH_ARG="$PZ_JAR:$ZB_JAR"
fi

# Windows/MSYS: la lista di path separata da ";" non viene convertita
# automaticamente, quindi javac.exe riceverebbe path stile /c/... e non
# troverebbe i jar. Convertiamo esplicitamente con cygpath.
if command -v cygpath >/dev/null 2>&1; then
    CLASSPATH_ARG="$(cygpath -w "$PZ_JAR");$(cygpath -w "$ZB_JAR")"
else
    CLASSPATH_ARG="$PZ_JAR:$ZB_JAR"
fi

# --- Compile ---
echo "[build] Compiling..."
mapfile -t SOURCES < <(find "$SRC_DIR" -name '*.java')

"$JAVAC" \
    --release 17 \
    -classpath "$CLASSPATH_ARG" \
    -d "$CLASSES_DIR" \
    "${SOURCES[@]}"

# --- Bundle LICENSE into jar (root + META-INF for tooling conventions) ---
mkdir -p "$CLASSES_DIR/META-INF"
cp "$PROJECT_ROOT/LICENSE" "$CLASSES_DIR/LICENSE"
cp "$PROJECT_ROOT/LICENSE" "$CLASSES_DIR/META-INF/LICENSE"

# --- Package jar ---
echo "[build] Packaging jar..."
"$JAR" --create --file "$JAR_OUT" -C "$CLASSES_DIR" .

# --- Stage mod directory ---
echo "[build] Staging mod directory..."
STAGE="$BUILD_DIR/stage/PeekAView"
rm -rf "$STAGE"
mkdir -p "$STAGE/42.13/media/java/client"
cp "$PROJECT_ROOT/mod_files/mod.info" "$STAGE/mod.info"
cp "$PROJECT_ROOT/mod_files/42.13/mod.info" "$STAGE/42.13/mod.info"
cp "$PROJECT_ROOT/poster.png" "$STAGE/poster.png"
cp "$PROJECT_ROOT/poster.png" "$STAGE/42.13/poster.png"
cp "$PROJECT_ROOT/icon.png" "$STAGE/icon.png"
cp "$PROJECT_ROOT/icon.png" "$STAGE/42.13/icon.png"
cp "$JAR_OUT" "$STAGE/42.13/media/java/client/peekaview.jar"

if [ -d "$PROJECT_ROOT/mod_files/42.13/media/lua" ]; then
    cp -r "$PROJECT_ROOT/mod_files/42.13/media/lua" "$STAGE/42.13/media/lua"
fi

# Empty common/ folder — pre-42.15 builds require it next to versioned folders
mkdir -p "$STAGE/common"

# Generate UI_<LANG>.txt alongside UI.json — pre-42.15 builds parse only the
# old Lua-table format, 42.15+ parses only JSON. Shipping both keeps the mod
# working across the format break. UI.json is canonical; the .txt files are
# build artifacts and not committed.
TRANSLATE_ROOT="$STAGE/42.13/media/lua/shared/Translate"
if [ -d "$TRANSLATE_ROOT" ]; then
    PYTHON_BIN="${PYTHON_BIN:-$(command -v python3 || command -v python || true)}"
    if [ -z "$PYTHON_BIN" ]; then
        echo "[build] ERROR: python3/python not found — needed to generate .txt translations." >&2
        echo "        Install Python 3 or set PYTHON_BIN in build.local." >&2
        exit 1
    fi
    "$PYTHON_BIN" "$PROJECT_ROOT/scripts/json_to_lua.py" "$TRANSLATE_ROOT"
fi

# --- Install to Zomboid mods dir ---
echo "[build] Installing to $MOD_INSTALL_ROOT"
rm -rf "$MOD_INSTALL_ROOT"
mkdir -p "$(dirname "$MOD_INSTALL_ROOT")"
cp -r "$STAGE" "$MOD_INSTALL_ROOT"

# --- Sync to Workshop staging dir, if configured ---
# PZ scans the Workshop upload staging tree (~/Zomboid/Workshop/<Mod>/Contents/mods/<Mod>/)
# and prefers it over ~/Zomboid/mods/. So when an author also tests via the staging
# tree, MOD_INSTALL_ROOT alone is not enough — the staging copy must stay in sync,
# otherwise PZ keeps loading the older bytes. Set WORKSHOP_STAGE_MOD in
# build.local to point at the Contents/mods/<Mod> dir; the parent (with
# workshop.txt/preview.png) is preserved.
if [ -n "${WORKSHOP_STAGE_MOD:-}" ]; then
    echo "[build] Syncing to Workshop stage: $WORKSHOP_STAGE_MOD"
    rm -rf "$WORKSHOP_STAGE_MOD"
    mkdir -p "$(dirname "$WORKSHOP_STAGE_MOD")"
    cp -r "$STAGE" "$WORKSHOP_STAGE_MOD"
fi

echo "[build] Done."
echo "       Jar:     $JAR_OUT"
echo "       Install: $MOD_INSTALL_ROOT"
if [ -n "${WORKSHOP_STAGE_MOD:-}" ]; then
    echo "       WS:      $WORKSHOP_STAGE_MOD"
fi
