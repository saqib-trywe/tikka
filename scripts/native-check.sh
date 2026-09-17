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

# Repeated runs first, each with a timeout, so an intermittent failure reports its exit status: 124 is a hang, and
# 128 plus a signal number is a crash (139 for SIGSEGV, 134 for SIGABRT).
limit=""
if command -v timeout >/dev/null; then limit="timeout 20"; fi
for run in $(seq 1 200); do
  set +e
  (cd "$repo" && $limit "$tikka" search ready >/dev/null 2>"$TIKKA_HOME/search.err")
  status=$?
  set -e
  if [ "$status" -ne 0 ]; then
    echo "run $run of tikka search failed with exit status $status"
    cat "$TIKKA_HOME/search.err"
    exit 1
  fi
done
echo "200 runs of tikka search succeeded"

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
