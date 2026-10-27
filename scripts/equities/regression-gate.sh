#!/usr/bin/env bash
# regression-gate.sh <corpus-file> <old-dist> <new-dist> <out-dir>
#
# Backtests every strategy in <corpus-file> (one path per line, '#' comments) on two qkt builds and
# fails when any trade list, daily equity or report headline differs. Each strategy runs twice per
# build: on ticks with --broker mt5-sim, and on bars (--bars). Runs in Docker against a data root
# and a hub store, as the research backtests do.
#
# Env: QKT_GATE_DATA (forge run dir with data/ and qkt.config.yaml), QKT_GATE_HUB (hub store),
#      QKT_GATE_FROM / QKT_GATE_TO (window, default 2024-01-01 .. 2024-04-01), QKT_GATE_JOBS (default 4).
set -euo pipefail

corpus=$1 old=$(realpath "$2") new=$(realpath "$3") out=$(realpath -m "$4")
data=${QKT_GATE_DATA:-/root/projects/qkt-forge/run}
hub=${QKT_GATE_HUB:-/root/hub-data}
from=${QKT_GATE_FROM:-2024-01-01}
to=${QKT_GATE_TO:-2024-04-01}
jobs=${QKT_GATE_JOBS:-4}
mkdir -p "$out"

run() {
  local dist=$1 strategy=$2 mode=$3 dir=$4
  local flags=(--broker mt5-sim)
  [[ $mode == bars ]] && flags=(--bars)
  mkdir -p "$dir"
  docker run --rm --user root -v "$data:$data:ro" -v "$hub:$hub:ro" -v "$dist:/opt/qkt:ro" \
    -v "$(dirname "$strategy"):/strategy:ro" -v "$dir:/out" --entrypoint /opt/qkt/bin/qkt eclipse-temurin:21-jre \
    backtest "/strategy/$(basename "$strategy")" --from "$from" --to "$to" --config "$data/qkt.config.yaml" \
    --data-root "$data/data" --hub-root "$hub" --starting-balance 100000 --no-fetch --allow-incomplete \
    --report-dir /out "${flags[@]}" > "$dir/stdout.log" 2>&1 || echo "exit=$?" >> "$dir/stdout.log"
}
export -f run
export data hub from to

mapfile -t strategies < <(grep -v '^\s*#' "$corpus" | grep -v '^\s*$')
for s in "${strategies[@]}"; do
  n=$(basename "$s" .qkt)
  for mode in ticks bars; do
    for side in old new; do
      dist=$old; [[ $side == new ]] && dist=$new
      echo "$dist|$s|$mode|$out/$n/$mode/$side"
    done
  done
done | xargs -P "$jobs" -I{} bash -c 'IFS="|" read -r d s m o <<< "{}"; run "$d" "$s" "$m" "$o"'

# A run the machine killed (out of memory: exit 137) is run again on its own, so a busy host cannot decide the gate.
for s in "${strategies[@]}"; do
  n=$(basename "$s" .qkt)
  for mode in ticks bars; do
    for side in old new; do
      dir=$out/$n/$mode/$side
      if grep -qE '^exit=(1[3-9][0-9]|2[0-9][0-9])$' "$dir/stdout.log" 2>/dev/null; then
        dist=$old; [[ $side == new ]] && dist=$new
        echo "rerun alone (killed): $n $mode $side"; rm -rf "$dir"; run "$dist" "$s" "$mode" "$dir"
      fi
    done
  done
done

# A report line that names the build or the run's own paths is not a trade difference.
headline() { grep -E 'Profit|Round trips|Worst drop|Sharpe|exit=|rror' "$1" 2>/dev/null || true; }

fail=0 compared=0
for s in "${strategies[@]}"; do
  n=$(basename "$s" .qkt)
  for mode in ticks bars; do
    a=$out/$n/$mode/old b=$out/$n/$mode/new
    # A run killed even when run alone proves nothing: inconclusive, not a pass. A run that fails the same way on both
    # builds (a mode the data does not support) is the same result, judged with the report lines below.
    for side in "$a" "$b"; do
        if grep -qE '^exit=(1[3-9][0-9]|2[0-9][0-9])$' "$side/stdout.log" 2>/dev/null; then
            echo "RUN KILLED $n $mode $(basename "$side"): $(grep -m1 '^exit=' "$side/stdout.log")"; fail=1
        fi
    done
    for f in trades.csv equity_daily.csv; do
      if [[ -f $a/$f || -f $b/$f ]]; then
        compared=$((compared + 1))
        if ! cmp -s "$a/$f" "$b/$f"; then echo "DIFF $n $mode $f"; fail=1; fi
      fi
    done
    if [[ "$(headline "$a/stdout.log")" != "$(headline "$b/stdout.log")" ]]; then
      echo "DIFF $n $mode report"; fail=1
    fi
    trips=$(grep -m1 -E 'Round trips' "$a/stdout.log" 2>/dev/null | tr -s ' ' || echo "no report")
    echo "same? $n $mode: $trips"
  done
done
echo "files compared: $compared"
if [[ $fail -ne 0 ]]; then echo "REGRESSION GATE: FAIL"; exit 1; fi
echo "REGRESSION GATE: PASS"
