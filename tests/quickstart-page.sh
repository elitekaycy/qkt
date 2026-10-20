#!/usr/bin/env bash
# tests/quickstart-page.sh
#
# Runs a quick start page exactly as written, on a clean machine: every ```bash block between
# <!-- quickstart:begin --> and <!-- quickstart:end --> runs in a fresh ubuntu:24.04 container with no Java and
# no ~/.qkt, against the self-contained linux-x64 bundle, and the output must contain each line of the page's
# ```text blocks. A page that does not unpack the bundle itself (it builds on the main quick start) gets the two
# install lines first; one that runs python3 gets python3 installed.
#
# Determinism is checked, not assumed: the page runs QUICKSTART_RUNS times (default 5), each in its own fresh
# container, and every run must print the same Result block (when the page prints one) and write byte-identical
# fingerprinted files.
#
# Usage: bash tests/quickstart-page.sh <qkt-*-linux-x64.tar.gz> [page.md] [file glob to fingerprint ...]
#   default page README.md, default files ~/.qkt/runs/*-momentum/trades.csv ~/.qkt/runs/*-momentum/orders.jsonl
set -euo pipefail

bundle="${1:?usage: quickstart-page.sh <qkt-*-linux-x64.tar.gz> [page.md] [file glob ...]}"
repo="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd -P)"
page="${2:-README.md}"
shift $(($# < 2 ? $# : 2))
prints=("$@")
[ ${#prints[@]} -gt 0 ] || prints=('~/.qkt/runs/*-momentum/trades.csv' '~/.qkt/runs/*-momentum/orders.jsonl')
work="$(mktemp -d)"
trap 'rm -rf -- "$work"' EXIT

awk '/<!-- quickstart:begin -->/{on=1} /<!-- quickstart:end -->/{on=0} on' "$repo/$page" >"$work/section.md"
awk '/^```bash$/{b=1; next} /^```$/{b=0} b' "$work/section.md" >"$work/page.sh"
awk '/^```text$/{t=1; next} /^```$/{t=0} t' "$work/section.md" >"$work/expected.txt"
[ -s "$work/page.sh" ] || { echo "no bash blocks between the quickstart markers in $page" >&2; exit 1; }
[ -s "$work/expected.txt" ] || { echo "no text block of expected output in $page's quick start" >&2; exit 1; }
{
    grep -q 'tar xzf qkt-' "$work/page.sh" || printf 'tar xzf qkt-*-linux-x64.tar.gz\nexport PATH="$PWD/qkt/bin:$PATH"\n'
    cat "$work/page.sh"
} >"$work/quickstart.sh"
needs_python=0
grep -q 'python3' "$work/quickstart.sh" && needs_python=1
printf '%s\n' "${prints[@]}" >"$work/prints.txt"
cp "$bundle" "$work/"

runs="${QUICKSTART_RUNS:-5}"
for run in $(seq 1 "$runs"); do
    docker run --rm --cpus 2 -e NEEDS_PYTHON=$needs_python -v "$work:/work:ro" ubuntu:24.04 bash -c '
        set -euo pipefail
        if [ "$NEEDS_PYTHON" = 1 ]; then
            apt-get update -qq >/dev/null && apt-get install -y -qq python3 >/dev/null
        fi
        mkdir -p /home/newcomer && cd /home/newcomer
        cp /work/qkt-*-linux-x64.tar.gz .
        if command -v java >/dev/null; then echo "the clean machine has java; the check would prove nothing" >&2; exit 1; fi
        if [ -e ~/.qkt ]; then echo "the clean machine already has ~/.qkt" >&2; exit 1; fi
        bash -euo pipefail /work/quickstart.sh 2>&1
        files=()
        while IFS= read -r pattern; do
            for f in $(eval echo "$pattern"); do files+=("$f"); done
        done </work/prints.txt
        echo "fingerprint $(sha256sum "${files[@]}" | awk "{print \$1}" | sha256sum | cut -c1-16) (${#files[@]} files)"
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
            echo "run $run: $page promises a line the run did not print: $line" >&2
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
echo "$page: $runs clean runs, every promised line printed, results and fingerprinted files identical"
