#!/usr/bin/env bash
# Paper boot test (PandiO/knk-workspace#420): starts a real Paper server headless with the shaded KnK jar, WorldGuard
# and WorldEdit, lets it settle, optionally runs console commands, stops it and checks the log with check_log.py.
# Used by .github/workflows/paper-boot.yml; runs locally too (see docs/guides/developer/ci.md in knk-workspace).
#
# The server directory must already hold paper.jar and plugins/ with WorldGuard and WorldEdit (the workflow downloads
# and checks them). This script writes eula.txt, server.properties and the KnK config.yml, so use a throwaway folder.
#
# The plugin runs with its shipped config.yml, changed in one place: api.auth.api-key gets a dummy value, because KnK
# refuses to start with type apikey and an empty key. No API is started: api.base-url stays http://localhost:5294/api,
# nothing listens there, and check_log.py excuses errors caused by that unreachable API.
#
# Hook for in-plugin scenarios (PandiO/knk-workspace#421): --console-command sends a command to the server console
# once it is up, and --expect-line waits for a log line matching a regular expression (for example a scenario's pass
# line); --fail-line fails the run as soon as a matching line appears.
set -euo pipefail

usage() {
  cat <<'EOF'
Usage: boot-test.sh --server-dir DIR --plugin-jar JAR [options]
  --java BIN               java executable for the server (default: java)
  --expect-commit SHA      commit the KnK version stamp must report
  --boot-timeout SECONDS   time allowed for the server to start (default 240)
  --settle SECONDS         wait after "Done" so asynchronous startup work logs too (default 15)
  --console-command CMD    console command to send after settling (repeatable)
  --expect-line REGEX      log line that must appear after the commands (repeatable)
  --fail-line REGEX        log line that fails the run when it appears after the commands (repeatable)
  --expect-timeout SECONDS time allowed for the expected lines (default 120)
EOF
}

SERVER_DIR=""
PLUGIN_JAR=""
JAVA_BIN="java"
EXPECT_COMMIT=""
BOOT_TIMEOUT=240
SETTLE=15
EXPECT_TIMEOUT=120
COMMANDS=()
EXPECT_LINES=()
FAIL_LINES=()
# The address check_log.py treats as the unreachable API (the default api.base-url of config.yml).
UNREACHABLE_API="localhost:5294"

while [[ $# -gt 0 ]]; do
  case "$1" in
    --server-dir) SERVER_DIR="$2"; shift 2 ;;
    --plugin-jar) PLUGIN_JAR="$2"; shift 2 ;;
    --java) JAVA_BIN="$2"; shift 2 ;;
    --expect-commit) EXPECT_COMMIT="$2"; shift 2 ;;
    --boot-timeout) BOOT_TIMEOUT="$2"; shift 2 ;;
    --settle) SETTLE="$2"; shift 2 ;;
    --console-command) COMMANDS+=("$2"); shift 2 ;;
    --expect-line) EXPECT_LINES+=("$2"); shift 2 ;;
    --fail-line) FAIL_LINES+=("$2"); shift 2 ;;
    --expect-timeout) EXPECT_TIMEOUT="$2"; shift 2 ;;
    -h|--help) usage; exit 0 ;;
    *) echo "Unknown option: $1" >&2; usage >&2; exit 2 ;;
  esac
done
[[ -n "$SERVER_DIR" && -n "$PLUGIN_JAR" ]] || { usage >&2; exit 2; }

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PLUGIN_JAR="$(cd "$(dirname "$PLUGIN_JAR")" && pwd)/$(basename "$PLUGIN_JAR")"
cd "$SERVER_DIR"
[[ -f paper.jar ]] || { echo "::error::$SERVER_DIR/paper.jar is missing"; exit 2; }
ls plugins/worldguard-*.jar plugins/worldedit-*.jar >/dev/null || { echo "::error::WorldGuard/WorldEdit jars missing"; exit 2; }

# A shaded jar holds knk-core; the plain `jar` output (same file name) does not and fails to load.
if ! unzip -Z1 "$PLUGIN_JAR" 'net/knightsandkings/knk/core/*' >/dev/null 2>&1; then
  echo "::error::$PLUGIN_JAR has no knk-core classes: build it with :knk-paper:shadowJar"
  exit 2
fi

echo "eula=true" > eula.txt
# Offline mode, localhost only, a flat world with structures off and short view distances: fast to generate.
cat > server.properties <<'EOF'
online-mode=false
server-ip=127.0.0.1
server-port=25565
level-type=minecraft\:flat
generator-settings={"layers":[{"block":"minecraft:bedrock","height":1},{"block":"minecraft:stone","height":3},{"block":"minecraft:grass_block","height":1}],"biome":"minecraft:plains"}
generate-structures=false
view-distance=2
simulation-distance=2
spawn-protection=0
max-players=5
enable-rcon=false
enable-query=false
EOF

cp "$PLUGIN_JAR" plugins/KnightsAndKings.jar
mkdir -p plugins/KnightsAndKings
unzip -p plugins/KnightsAndKings.jar config.yml > plugins/KnightsAndKings/config.yml
grep -q '^  base-url: "http://localhost:5294/api"$' plugins/KnightsAndKings/config.yml \
  || { echo "::error::config.yml's api.base-url changed; update UNREACHABLE_API in boot-test.sh"; exit 2; }
sed -i 's/^    api-key: ""$/    api-key: "ci-boot-test-dummy-key"/' plugins/KnightsAndKings/config.yml
grep -q '^    api-key: "ci-boot-test-dummy-key"$' plugins/KnightsAndKings/config.yml \
  || { echo "::error::Could not set api.auth.api-key in config.yml (its layout changed)"; exit 2; }

rm -f logs/latest.log console.in
mkdir -p logs
mkfifo console.in
# Keep the console pipe open for writing, so the server doesn't read end-of-input.
exec 3<>console.in
"$JAVA_BIN" -Xms512M -Xmx2G -jar paper.jar --nogui <&3 > server-stdout.log 2>&1 &
SERVER_PID=$!
echo "Server started (pid $SERVER_PID)"

stop_server() {
  if kill -0 "$SERVER_PID" 2>/dev/null; then
    echo "stop" >&3 || true
    for _ in $(seq 1 90); do
      kill -0 "$SERVER_PID" 2>/dev/null || break
      sleep 1
    done
    if kill -0 "$SERVER_PID" 2>/dev/null; then
      echo "::error::Server did not stop within 90 s; killing it"
      kill -9 "$SERVER_PID" || true
      return 1
    fi
  fi
  return 0
}
trap 'kill -9 "$SERVER_PID" 2>/dev/null || true' EXIT

log_has() { [[ -f logs/latest.log ]] && grep -qE "$1" logs/latest.log; }

FAILED=0
started=0
for _ in $(seq 1 "$BOOT_TIMEOUT"); do
  if log_has '\]: Done \([0-9.,]+s\)!'; then started=1; break; fi
  kill -0 "$SERVER_PID" 2>/dev/null || break
  sleep 1
done
if [[ $started -eq 0 ]]; then
  echo "::error::Server did not finish starting within ${BOOT_TIMEOUT} s (or exited)"
  tail -n 40 server-stdout.log || true
  FAILED=1
else
  echo "Server started; settling for ${SETTLE} s"
  sleep "$SETTLE"
  mark=$(wc -l < logs/latest.log)
  for cmd in "${COMMANDS[@]}"; do
    echo "Console: $cmd"
    echo "$cmd" >&3
  done
  if [[ ${#EXPECT_LINES[@]} -gt 0 || ${#FAIL_LINES[@]} -gt 0 ]]; then
    pending=("${EXPECT_LINES[@]}")
    for _ in $(seq 1 "$EXPECT_TIMEOUT"); do
      since=$(tail -n +"$((mark + 1))" logs/latest.log)
      for pattern in "${FAIL_LINES[@]}"; do
        if grep -qE "$pattern" <<<"$since"; then
          echo "::error::Fail line appeared: $(grep -E "$pattern" <<<"$since" | head -n 1)"
          FAILED=1
        fi
      done
      [[ $FAILED -eq 1 ]] && break
      remaining=()
      for pattern in "${pending[@]}"; do
        grep -qE "$pattern" <<<"$since" || remaining+=("$pattern")
      done
      pending=("${remaining[@]}")
      [[ ${#pending[@]} -eq 0 && ${#FAIL_LINES[@]} -eq 0 ]] && break
      sleep 1
    done
    for pattern in "${pending[@]}"; do
      echo "::error::Expected log line not seen within ${EXPECT_TIMEOUT} s: $pattern"
      FAILED=1
    done
  fi
fi

stop_server || FAILED=1
exec 3>&-

check_args=(logs/latest.log --unreachable-api "$UNREACHABLE_API")
[[ -n "$EXPECT_COMMIT" ]] && check_args+=(--expect-commit "$EXPECT_COMMIT")
[[ $started -eq 1 ]] && check_args+=(--expect-shutdown)
python3 -I "$SCRIPT_DIR/check_log.py" "${check_args[@]}" || FAILED=1

if [[ $FAILED -ne 0 ]]; then
  echo "Paper boot test FAILED"
  exit 1
fi
echo "Paper boot test passed"
