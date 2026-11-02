#!/usr/bin/env bash
# The release proofs for one build, offline and deterministic: every real-data case, qkt against
# backtesting.py and backtrader, and the 48-scenario backtest audit, all on the pinned corpus.
# run-attestation.sh runs this before a testing commit can be promoted to main.
set -euo pipefail
repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"

usage() {
    cat <<'USAGE'
Usage: run.sh --cli QKT --corpus DIR --out DIR [--previous OLD_QKT] [--docs-data STORE]

DIR is a clone of elitekaycy/qkt-data-corpus; it is moved to the commit in tests/data-e2e/corpus.lock and
must hold no local changes. --docs-data adds the docs examples, run on that local store (pinned by the
snapshots in scripts/release-proof/docs); --previous adds store compatibility (a store the previous release
wrote, read by this one). Writes <out>/proofs.json and one log per proof. Exit 0 only when all pass.
USAGE
}
cli=""; corpus=""; out=""; docs_data=""; previous=""
while [ "$#" -gt 0 ]; do
    case "$1" in
        --cli) cli="${2:-}"; shift 2 ;;
        --corpus) corpus="${2:-}"; shift 2 ;;
        --out) out="${2:-}"; shift 2 ;;
        --docs-data) docs_data="${2:-}"; shift 2 ;;
        --previous) previous="${2:-}"; shift 2 ;;
        --help|-h) usage; exit 0 ;;
        *) printf 'release-proof: unknown argument: %s\n' "$1" >&2; exit 2 ;;
    esac
done
[ -x "$cli" ] && [ -d "$corpus/.git" ] && [ -n "$out" ] || { usage >&2; exit 2; }

lock="$(cut -d' ' -f1 "$repo_root/tests/data-e2e/corpus.lock")"
[ -z "$(git -C "$corpus" status --porcelain)" ] || { echo "release-proof: $corpus has local changes" >&2; exit 2; }
git -C "$corpus" cat-file -e "$lock^{commit}" 2>/dev/null || git -C "$corpus" fetch -q origin
git -C "$corpus" checkout -q --detach "$lock"
mkdir -p "$out"

# Offline and on a pinned corpus nothing here writes to, so one proof's time is not another's: after the corpus's own
# check, they run side by side. Measured in series on 2026-10-30: 1295 s, of which docs alone 737 s.
prove() {  # name command...
    local name="$1"; shift
    local started=$SECONDS status
    if "$@" > "$out/$name.log" 2>&1; then status=pass; else status=fail; fi
    printf '%s %s %ss  %s\n' "$status" "$name" "$((SECONDS - started))" "$(tail -n 1 "$out/$name.log")"
    jq -n --arg n "$name" --arg s "$status" --argjson t "$((SECONDS - started))" \
        --arg last "$(tail -n 1 "$out/$name.log")" '{proof:$n,status:$s,seconds:$t,summary:$last}' > "$out/.proof-$name.json"
}
names=(corpus data-e2e references audit)
prove corpus python3 -I "$corpus/tools/provenance.py" --check
prove data-e2e python3 -I "$repo_root/tests/data-e2e/run.py" --tier full --qkt "$cli" --corpus "$corpus" --report "$out/data-e2e.json" &
prove references python3 -I "$repo_root/tests/references/run.py" --qkt "$cli" --refs "$corpus/references" --report "$out/references.json" &
prove audit python3 -I "$repo_root/scripts/release-proof/audit/run.py" --qkt "$cli" --store "$corpus/stores/exness-2026-06" \
    --out "$out/audit" --report "$out/audit.json" &
if [ -n "$previous" ]; then
    names+=(compat)
    prove compat python3 -I "$repo_root/scripts/release-proof/compat/run.py" --qkt "$cli" --previous "$previous" \
        --corpus "$corpus" --out "$out/compat" &
fi
if [ -n "$docs_data" ]; then
    names+=(docs)
    prove docs python3 -I "$repo_root/scripts/release-proof/docs/run.py" --qkt "$cli" --data "$docs_data" \
        --out "$out/docs" --report "$out/docs.json" &
fi
wait
# A proof that left no verdict is a failed proof, in the order the report has always had.
results=()
for name in "${names[@]}"; do
    if [ -f "$out/.proof-$name.json" ]; then results+=("$(cat "$out/.proof-$name.json")"); rm -f "$out/.proof-$name.json"
    else results+=("$(jq -n --arg n "$name" '{proof:$n,status:"fail",seconds:0,summary:"the proof left no verdict"}')"); fi
done
[ -z "$(git -C "$corpus" status --porcelain)" ] || results+=("$(jq -n '{proof:"corpus-unchanged",status:"fail",seconds:0,summary:"a proof changed the corpus"}')")
printf '%s\n' "${results[@]}" | jq -s --arg corpus "$lock" --arg cli "$("$cli" --version | head -n 1)" \
    '{schema:"qkt-release-proofs-v1",corpus:$corpus,cli:$cli,proofs:.,passed:(length > 0 and all(.status == "pass"))}' \
    > "$out/proofs.json"
jq -e '.passed' "$out/proofs.json" > /dev/null
