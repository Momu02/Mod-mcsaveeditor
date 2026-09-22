#!/usr/bin/env bash
# Stress-test runner for MC Save Editor.
# Boots a real Forge 1.20.1 dedicated server with the built mod jar once per
# sample-data scenario, asserts clean startup, and records boot timings.
#
# Usage: run_stress.sh <mod-jar> [work-dir]
#   mod-jar : path to the built mcsaveeditor jar
#   work-dir: scratch directory (default: ./stress-work)
#
# Exit code: number of failed scenarios (0 = all green).

set -u

JAR="${1:?usage: run_stress.sh <mod-jar> [work-dir]}"
WORK="${2:-$PWD/stress-work}"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"

FORGE_VERSION="1.20.1-47.3.0"
INSTALLER_URL="https://maven.minecraftforge.net/net/minecraftforge/forge/${FORGE_VERSION}/forge-${FORGE_VERSION}-installer.jar"
BOOT_TIMEOUT=600

SCENARIOS="empty normal old-version corrupt huge player-base entities-poi edge-selection"
SEED=123456789

failures=0
declare -A BOOT_SECS
declare -A BOOT_STATUS

log() { echo "[stress] $*"; }

# --- 0. prerequisites --------------------------------------------------------
command -v java >/dev/null || { log "FATAL: java not found"; exit 99; }
command -v python3 >/dev/null || { log "FATAL: python3 not found"; exit 99; }
[ -f "$JAR" ] || { log "FATAL: mod jar not found: $JAR"; exit 99; }
java -version 2>&1 | head -1
mkdir -p "$WORK/cache"

# --- 1. sample data ----------------------------------------------------------
log "generating sample data..."
python3 "$SCRIPT_DIR/generate_sample_data.py" --out "$WORK/sample-data" --check \
    || { log "FATAL: sample data generation/validation failed"; exit 99; }

# --- 2. install forge server once -------------------------------------------
INSTALLER="$WORK/cache/forge-${FORGE_VERSION}-installer.jar"
if [ ! -f "$INSTALLER" ]; then
    log "downloading forge installer..."
    curl -sSL --max-time 300 -o "$INSTALLER" "$INSTALLER_URL" \
        || { log "FATAL: installer download failed"; exit 99; }
fi
BASE="$WORK/server-base"
if [ ! -f "$BASE/run.sh" ]; then
    log "installing forge server (takes a few minutes)..."
    mkdir -p "$BASE"
    (cd "$BASE" && java -jar "$INSTALLER" --installServer) >"$WORK/install.log" 2>&1 \
        || { log "FATAL: server install failed, see install.log"; tail -20 "$WORK/install.log"; exit 99; }
fi
chmod +x "$BASE/run.sh"
echo "-Xmx2G" > "$BASE/user_jvm_args.txt"

write_common_files() {
    local dir="$1"
    echo "eula=true" > "$dir/eula.txt"
    cat > "$dir/server.properties" <<EOF
level-name=world
level-seed=${SEED}
online-mode=false
difficulty=peaceful
gamemode=survival
view-distance=6
simulation-distance=4
motd=MC Save Editor stress test
enable-rcon=false
EOF
    mkdir -p "$dir/mods"
    cp "$JAR" "$dir/mods/"
}

# --- 3. template boot: produce a vanilla-valid level.dat --------------------
log "template boot to generate level.dat..."
rm -rf "$WORK/template-run" && mkdir -p "$WORK/template-run"
cp -r "$BASE/." "$WORK/template-run/"
write_common_files "$WORK/template-run"
(cd "$WORK/template-run" && timeout "$BOOT_TIMEOUT" ./run.sh >server.log 2>&1)
if ! grep -q 'Done (' "$WORK/template-run/server.log"; then
    log "FATAL: template server never booted"
    tail -30 "$WORK/template-run/server.log"
    exit 99
fi
sleep 5  # let the SIGTERM shutdown release the world lock
TEMPLATE_DAT="$WORK/template-run/world/level.dat"
[ -f "$TEMPLATE_DAT" ] || { log "FATAL: no level.dat generated"; exit 99; }
log "template OK: $(grep -o 'Done ([0-9.]*s)' "$WORK/template-run/server.log" | head -1)"

# --- 4. per-scenario boots ---------------------------------------------------
boot_scenario() {
    local name="$1"
    local dir="$WORK/run-$name"
    log "=== scenario: $name ==="
    rm -rf "$dir" && mkdir -p "$dir/world"
    cp -r "$BASE/." "$dir/"
    write_common_files "$dir"
    cp "$TEMPLATE_DAT" "$dir/world/level.dat"
    rm -rf "$dir/world/region" "$dir/world/entities" "$dir/world/poi"
    for sub in region entities poi; do
        if [ -d "$WORK/sample-data/$name/$sub" ]; then
            mkdir -p "$dir/world/$sub"
            cp "$WORK/sample-data/$name/$sub/"*.mca "$dir/world/$sub/" 2>/dev/null || true
            cp "$WORK/sample-data/$name/$sub/"*.txt "$dir/world/$sub/" 2>/dev/null || true
        fi
    done

    local start end secs
    start=$(date +%s)
    (cd "$dir" && timeout "$BOOT_TIMEOUT" ./run.sh >server.log 2>&1)
    end=$(date +%s)
    secs=$((end - start))

    local done_line
    done_line=$(grep -o 'Done ([0-9.]*s)' "$dir/server.log" | head -1)
    local warns errors modmark
    warns=$(grep -ci 'warn' "$dir/server.log" || true)
    errors=$(grep -ci 'error\|exception' "$dir/server.log" || true)
    modmark=$(grep -ci 'mcsaveeditor' "$dir/server.log" || true)

    if [ -n "$done_line" ]; then
        BOOT_STATUS[$name]="PASS ($done_line)"
        BOOT_SECS[$name]="$secs"
        log "$name: PASS $done_line (wall ${secs}s, warn=$warns err=$errors modrefs=$modmark)"
        [ "$modmark" -gt 0 ] || log "$name: WARNING: no mcsaveeditor marker in log"
    else
        BOOT_STATUS[$name]="FAIL (no Done marker, wall ${secs}s)"
        BOOT_SECS[$name]="$secs"
        failures=$((failures + 1))
        log "$name: FAIL, last log lines:"
        tail -15 "$dir/server.log"
    fi
}

for scenario in $SCENARIOS; do
    boot_scenario "$scenario"
done

# --- 5. report ---------------------------------------------------------------
{
    echo "{"
    echo '  "jar": "'$(basename "$JAR")'",'
    echo '  "forge": "'$FORGE_VERSION'",'
    echo '  "scenarios": {'
    first=1
    for scenario in $SCENARIOS; do
        [ $first -eq 1 ] || echo ","
        first=0
        printf '    "%s": {"status": "%s", "wall_seconds": %s}' \
            "$scenario" "${BOOT_STATUS[$scenario]}" "${BOOT_SECS[$scenario]}"
    done
    echo ""
    echo "  },"
    echo '  "failed": '$failures
    echo "}"
} > "$WORK/timings.json"

echo ""
echo "scenario        | wall(s) | result"
echo "----------------|---------|--------------------------------"
for scenario in $SCENARIOS; do
    printf "%-15s | %-7s | %s\n" "$scenario" "${BOOT_SECS[$scenario]}" "${BOOT_STATUS[$scenario]}"
done
echo "failed: $failures"

(cd "$WORK" && tar czf stress-logs.tar.gz timings.json install.log \
    template-run/server.log run-*/server.log 2>/dev/null)
log "logs archived: $WORK/stress-logs.tar.gz"

exit "$failures"
