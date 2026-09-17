#!/usr/bin/env bash
# Runs the pinned MCP conformance suite against a real daemon, at both protocol revisions tikka speaks.
# Only the scenarios that apply to a tools-only server run; conformance-baseline.yml excuses the few checks that
# need the suite's own diagnostic tools, and every other check is enforced.
# Usage: scripts/conformance.sh <daemon-jar>
set -euo pipefail

jar="$1"
version="${CONFORMANCE_VERSION:-0.2.0-alpha.11}"
port="${TIKKA_CONFORMANCE_PORT:-7299}"
home="$PWD/target/conformance-home"

rm -rf "$home"
mkdir -p "$home"
printf 'port = %s\n' "$port" >"$home/config.toml"

TIKKA_HOME="$home" java --enable-native-access=ALL-UNNAMED -jar "$jar" run >"$home/daemon.log" 2>&1 &
daemon=$!
trap 'kill "$daemon" 2>/dev/null || true' EXIT

for _ in $(seq 1 60); do
  if curl -sf "http://127.0.0.1:$port/api/meta" >/dev/null; then break; fi
  sleep 1
done
curl -sf -X POST -H 'Content-Type: application/json' "http://127.0.0.1:$port/api/projects" \
  -d '{"key":"TIK","name":"Conformance"}' >/dev/null

suite() {
  npx -y "@modelcontextprotocol/conformance@${version}" server --url "http://127.0.0.1:$port/mcp" \
    --expected-failures conformance-baseline.yml "$@"
}

status=0
for scenario in server-initialize server-session-lifecycle ping tools-list dns-rebinding-protection; do
  echo "== 2025-11-25 $scenario"
  suite --spec-version 2025-11-25 --scenario "$scenario" || status=1
done
for scenario in server-stateless tools-list dns-rebinding-protection http-header-validation caching; do
  echo "== 2026-07-28 $scenario"
  suite --spec-version 2026-07-28 --scenario "$scenario" || status=1
done
exit "$status"
