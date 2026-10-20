#!/usr/bin/env bash
# tests/quickstart-readme.sh
#
# Runs the README quick start exactly as written, on a clean machine: every ```bash block between
# <!-- quickstart:begin --> and <!-- quickstart:end --> runs in a fresh ubuntu:24.04 container with no Java and
# no ~/.qkt, against the self-contained linux-x64 bundle, and the output must contain each line of the README's
# ```text block. A launcher that cannot find Java, a sample missing from the bundle or numbers that drift all fail.
#
# Determinism is checked, not assumed: the whole quick start runs QUICKSTART_RUNS times (default 5), each in its own
# fresh container, and every run must print the same Result block and write byte-identical trades.csv and
# orders.jsonl.
#
# Usage: bash tests/quickstart-readme.sh build/distributions/qkt-<version>-linux-x64.tar.gz
set -euo pipefail

bundle="${1:?usage: quickstart-readme.sh <qkt-*-linux-x64.tar.gz>}"
repo="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd -P)"
work="$(mktemp -d)"
trap 'rm -rf -- "$work"' EXIT

awk '/<!-- quickstart:begin -->/{on=1} /<!-- quickstart:end -->/{on=0} on' "$repo/README.md" >"$work/section.md"
awk '/^```bash$/{b=1; next} /^```$/{b=0} b' "$work/section.md" >"$work/quickstart.sh"
awk '/^```text$/{t=1; next} /^```$/{t=0} t' "$work/section.md" >"$work/expected.txt"
[ -s "$work/quickstart.sh" ] || { echo "no bash blocks between the quickstart markers in README.md" >&2; exit 1; }
[ -s "$work/expected.txt" ] || { echo "no text block of expected output in the README quick start" >&2; exit 1; }
cp "$bundle" "$work/"

runs="${QUICKSTART_RUNS:-5}"
for run in $(seq 1 "$runs"); do
    docker run --rm --cpus 2 -v "$work:/work:ro" ubuntu:24.04 bash -c '
        set -euo pipefail
        mkdir -p /home/newcomer && cd /home/newcomer
        cp /work/qkt-*-linux-x64.tar.gz .
        if command -v java >/dev/null; then echo "the clean machine has java; the check would prove nothing" >&2; exit 1; fi
        if [ -e ~/.qkt ]; then echo "the clean machine already has ~/.qkt" >&2; exit 1; fi
        bash -euo pipefail /work/quickstart.sh 2>&1
        report=$(ls -d ~/.qkt/runs/*-momentum | tail -1)
        echo "fingerprint $(cd "$report" && sha256sum trades.csv orders.jsonl | sha256sum | cut -c1-16)"
    ' >"$work/output-$run.txt"
    sed -n '/^Result/,/^  Costs/p' "$work/output-$run.txt" >"$work/result-$run.txt"
    grep '^fingerprint ' "$work/output-$run.txt" >>"$work/result-$run.txt"
    echo "run $run: $(grep -E '^  (Profit|Round trips)' "$work/output-$run.txt" | tr -s ' ' | tr '\n' ' ')$(grep '^fingerprint ' "$work/output-$run.txt")"
done

missing=0
for run in $(seq 1 "$runs"); do
    while IFS= read -r line; do
        [ -z "$line" ] && continue
        if ! grep -qF -- "$line" "$work/output-$run.txt"; then
            echo "run $run: README promises a line the run did not print: $line" >&2
            missing=1
        fi
    done <"$work/expected.txt"
    if ! cmp -s "$work/result-1.txt" "$work/result-$run.txt"; then
        echo "run $run differs from run 1:" >&2
        diff "$work/result-1.txt" "$work/result-$run.txt" >&2 || true
        missing=1
    fi
done
[ "$missing" -eq 0 ] || { cat "$work/output-1.txt" >&2; exit 1; }
echo "README quick start: $runs clean runs, every promised line printed, Result and trade files identical"
