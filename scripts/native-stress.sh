#!/usr/bin/env bash
# Hunts TIK-4: runs the Native CLI against a real daemon many times over and reports how each run ended.
#
# The check script asks whether the CLI works; this one asks how it fails. A run that is still going after ten
# seconds is a hang and gets a thread backtrace; a run killed by a signal is an abort and gets a backtrace from its
# core file. Both name the command that produced them, because the fault has only ever appeared while a request is
# in flight.
#
# Usage: scripts/native-stress.sh <tikka-binary> <daemon-jar> [runs-per-shape]
set -uo pipefail # deliberately not -e: a failing run is the measurement, not an error

tikka="$(cd "$(dirname "$1")" && pwd)/$(basename "$1")"
jar="$2"
runs="${3:-300}"
port="${TIKKA_STRESS_PORT:-7299}"
export TIKKA_HOME="$PWD/target/native-stress-home"
repo="$PWD/target/native-stress-repo"
cores="$PWD/target/native-stress-cores"

rm -rf "$TIKKA_HOME" "$repo" "$cores"
mkdir -p "$TIKKA_HOME" "$repo" "$cores"
printf 'port = %s\n' "$port" >"$TIKKA_HOME/config.toml"

# A core file is the only way to see where an abort came from, and the default pattern on CI sends it to a handler.
ulimit -c unlimited 2>/dev/null || true
if [ "$(uname -s)" = "Linux" ] && sudo -n true 2>/dev/null; then
  sudo sysctl -w "kernel.core_pattern=$cores/core.%p" >/dev/null 2>&1 || true
fi

java --enable-native-access=ALL-UNNAMED -jar "$jar" run >"$TIKKA_HOME/daemon.log" 2>&1 &
daemon=$!
trap 'kill "$daemon" 2>/dev/null || true' EXIT
for _ in $(seq 1 60); do
  if "$tikka" daemon status >/dev/null 2>&1; then break; fi
  sleep 1
done

cd "$repo" || exit 1
"$tikka" project new TIK "Native stress" >/dev/null 2>&1
printf 'project = "TIK"\n' >.tikka

hangs=0
aborts=0
errors=0
ok=0

# The first fault of each kind is worth a backtrace; after that they are just counted.
backtraced_hang=0
backtraced_abort=0

report_hang() {
  local pid="$1" label="$2"
  echo "--- hang: $label (pid $pid)"
  echo "daemon /api/meta while hung: $(curl -s -m 3 -o /dev/null -w '%{http_code}' "http://127.0.0.1:$port/api/meta" || echo none)"
  (ss -tan 2>/dev/null || netstat -an 2>/dev/null) | grep ":$port" || true
  if command -v gdb >/dev/null && sudo -n true 2>/dev/null; then
    sudo gdb -batch -p "$pid" -ex "info threads" -ex "thread apply all bt" 2>&1 | head -120
  else
    echo "no gdb available for a live backtrace"
  fi
}

report_abort() {
  local label="$1" status="$2" output="$3"
  echo "--- abort: $label (exit $status, signal $((status - 128)))"
  cat "$output"
  local core
  core="$(ls -t "$cores"/core.* 2>/dev/null | head -1)"
  if [ -n "$core" ] && command -v gdb >/dev/null; then
    gdb -batch "$tikka" "$core" -ex "thread apply all bt" 2>&1 | head -120
  else
    echo "no core file at $cores (pattern: $(cat /proc/sys/kernel/core_pattern 2>/dev/null || echo unknown))"
  fi
}

run_once() {
  local label="$1"
  shift
  local output="$TIKKA_HOME/run.out"
  "$tikka" "$@" >"$output" 2>&1 &
  local pid=$!
  for _ in $(seq 1 100); do
    kill -0 "$pid" 2>/dev/null || break
    sleep 0.1
  done
  if kill -0 "$pid" 2>/dev/null; then
    hangs=$((hangs + 1))
    if [ "$backtraced_hang" -eq 0 ]; then
      backtraced_hang=1
      report_hang "$pid" "$label"
    fi
    kill -9 "$pid" 2>/dev/null
    wait "$pid" 2>/dev/null
    return
  fi
  wait "$pid"
  local status=$?
  if [ "$status" -gt 128 ]; then
    aborts=$((aborts + 1))
    if [ "$backtraced_abort" -eq 0 ]; then
      backtraced_abort=1
      report_abort "$label" "$status" "$output"
    fi
  elif [ "$status" -ne 0 ]; then
    errors=$((errors + 1))
    echo "--- exit $status: $label"
    head -5 "$output"
  else
    ok=$((ok + 1))
  fi
}

echo "stressing $tikka: $runs runs of each shape against a daemon on port $port"
for run in $(seq 1 "$runs"); do
  run_once "daemon status" daemon status
  run_once "search ready" search ready
  run_once "new" new "Stress $run"
done

total=$((ok + hangs + aborts + errors))
echo
echo "runs: $total | ok: $ok | hangs: $hangs | aborts: $aborts | other failures: $errors"
[ $((hangs + aborts + errors)) -eq 0 ]
