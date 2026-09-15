#!/usr/bin/env bash
# Startup gate for the Native CLI: fails when hyperfine's median for the command exceeds the budget.
# Usage: scripts/startup-gate.sh <budget-ms> <command...>
set -euo pipefail

budget_ms="$1"
shift
report="target/startup-gate.json"
mkdir -p target

hyperfine --warmup 5 --runs 100 --shell=none --export-json "$report" "$*"

python3 - "$report" "$budget_ms" <<'PY'
import json, sys
report, budget = sys.argv[1], float(sys.argv[2])
median_ms = json.load(open(report))["results"][0]["median"] * 1000
print(f"median {median_ms:.1f} ms against a {budget:.0f} ms budget")
sys.exit(0 if median_ms <= budget else 1)
PY
