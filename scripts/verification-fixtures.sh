#!/usr/bin/env bash
# Regenerate the indicator-oracle fixtures (verification/oracle/indicators/fixtures) from the checked-in series,
# in a throwaway venv with the pinned oracle libraries. Pass --refresh-series to rebuild the series from the
# local qkt bar store first (needs ~/.qkt/data, or QKT_STORE). Review the resulting fixture diff like code.
#
#   scripts/verification-fixtures.sh
#   scripts/verification-fixtures.sh --refresh-series
set -euo pipefail

repo="$(cd "$(dirname "$0")/.." && pwd)"
oracle="$repo/verification/oracle/indicators"
venv="${QKT_ORACLE_VENV:-${TMPDIR:-/tmp}/qkt-oracle-venv}"

if [ ! -x "$venv/bin/python" ]; then
    python3 -m venv "$venv"
fi
"$venv/bin/pip" install --quiet --requirement "$oracle/requirements.txt"

args=()
for a in "$@"; do args+=("$a"); done
if [ -n "${QKT_STORE:-}" ]; then args+=(--store "$QKT_STORE"); fi
"$venv/bin/python" -I "$oracle/gen.py" "${args[@]}"
