#!/usr/bin/env bash
# Checks the Native CLI against a real daemon: the startup gate on `tikka search`, and `tikka mcp` over stdin.
# Usage: scripts/native-check.sh <tikka-binary> <daemon-jar>
set -euo pipefail

tikka="$(cd "$(dirname "$1")" && pwd)/$(basename "$1")"
jar="$2"
port="${TIKKA_CHECK_PORT:-7298}"
export TIKKA_HOME="$PWD/target/native-check-home"
repo="$PWD/target/native-check-repo"

rm -rf "$TIKKA_HOME" "$repo"
mkdir -p "$TIKKA_HOME" "$repo"
printf 'port = %s\n' "$port" >"$TIKKA_HOME/config.toml"

java --enable-native-access=ALL-UNNAMED -jar "$jar" run >"$TIKKA_HOME/daemon.log" 2>&1 &
daemon=$!
trap 'kill "$daemon" 2>/dev/null || true' EXIT
for _ in $(seq 1 60); do
  if "$tikka" daemon status >/dev/null 2>&1; then break; fi
  sleep 1
done

cd "$repo"
"$tikka" project new TIK "Native check"
printf 'project = "TIK"\n' >.tikka
"$tikka" new "Ready work"
cd - >/dev/null

# Repeated runs of three shapes, to tell where an intermittent hang lives: the runtime alone (--version), one request
# (daemon status), and a command with its concurrent version check (search). A run still going after 10 seconds is
# a hang; the first one gets a thread backtrace where gdb is available.
probe() {
  name="$1"
  shift
  failures=0
  for run in $(seq 1 150); do
    "$tikka" "$@" >/dev/null 2>&1 &
    pid=$!
    for _ in $(seq 1 100); do
      kill -0 "$pid" 2>/dev/null || break
      sleep 0.1
    done
    if kill -0 "$pid" 2>/dev/null; then
      failures=$((failures + 1))
      echo "$name: run $run hung"
      # Ubuntu only lets a process's parent trace it, so the backtrace needs sudo there.
      if [ "$failures" -eq 1 ] && command -v gdb >/dev/null && sudo -n true 2>/dev/null; then
        sudo gdb -batch -p "$pid" -ex "thread apply all bt" 2>&1 | grep -E '^Thread|^#' | head -150
      fi
      kill -9 "$pid" 2>/dev/null || true
      wait "$pid" 2>/dev/null || true
    else
      set +e
      wait "$pid"
      status=$?
      set -e
      if [ "$status" -ne 0 ]; then
        failures=$((failures + 1))
        echo "$name: run $run exited with status $status"
      fi
    fi
  done
  echo "$name: $failures failures in 150 runs"
  total=$((total + failures))
}

total=0
cd "$repo"
probe version --version
probe status daemon status
probe search search ready
cd - >/dev/null
if [ "$total" -ne 0 ]; then
  echo "tikka hung or failed $total times"
  exit 1
fi

# The budget from "Choose the CLI approach": search against a running daemon, under 100 ms at the median.
(cd "$repo" && "$OLDPWD/scripts/startup-gate.sh" 100 "$tikka" search ready)

# Async stdin on Scala Native is the least-proven path, so a real request goes through it.
request='{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"search_issues","arguments":{"query":"ready"},"_meta":{"io.modelcontextprotocol/protocolVersion":"2026-07-28","io.modelcontextprotocol/clientCapabilities":{}}}}'
reply="$(cd "$repo" && printf '%s\n' "$request" | "$tikka" mcp)"
if ! grep -q '"effective_query":"project:TIK ready"' <<<"$reply"; then
  echo "tikka mcp did not return the bound search: $reply"
  exit 1
fi
echo "tikka mcp forwarded a request over stdin"
