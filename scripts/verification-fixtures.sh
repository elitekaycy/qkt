#!/usr/bin/env bash
# Regenerate the verification oracles' checked-in output, in a throwaway venv with the pinned oracle libraries:
#   - verification/oracle/indicators/fixtures from the checked-in series (TA-Lib / numpy / pandas / scipy);
#   - verification/oracle/engine/scenarios and expected from cases.py (standard library only).
# Pass --refresh-series to rebuild the indicator series from the local qkt bar store first (needs ~/.qkt/data, or
# QKT_STORE). Review the resulting diff like code: nothing here runs qkt.
#
#   scripts/verification-fixtures.sh
#   scripts/verification-fixtures.sh --refresh-series
#   scripts/verification-fixtures.sh --check        # only verify the engine expectations are current
set -euo pipefail

repo="$(cd "$(dirname "$0")/.." && pwd)"
indicators="$repo/verification/oracle/indicators"
engine="$repo/verification/oracle/engine"
venv="${QKT_ORACLE_VENV:-${TMPDIR:-/tmp}/qkt-oracle-venv}"

if [ ! -x "$venv/bin/python" ]; then
    python3 -m venv "$venv"
fi

if [ "${1:-}" = "--check" ]; then
    "$venv/bin/python" -I "$engine/gen.py" --check
    exit $?
fi

"$venv/bin/pip" install --quiet --requirement "$indicators/requirements.txt"

args=()
for a in "$@"; do args+=("$a"); done
if [ -n "${QKT_STORE:-}" ]; then args+=(--store "$QKT_STORE"); fi
"$venv/bin/python" -I "$indicators/gen.py" "${args[@]}"

"$venv/bin/python" -I "$engine/author.py"
"$venv/bin/python" -I "$engine/gen.py"
