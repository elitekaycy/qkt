#!/usr/bin/env bash
# The derivatives lane runs on its own gateway account: it needs its flags, its cases never overlap (one
# netting account), each case gets its own verdict, and one failed case fails the run.
set -euo pipefail
repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
tmp="$(mktemp -d)"; trap 'rm -rf "$tmp"' EXIT
fake="$tmp/repo"; mkdir -p "$fake/scripts/live-validation" "$fake/attestation/lib" "$fake/bin"
cp "$repo_root/scripts/live-validation/run-attestation-catalog.sh" "$fake/scripts/live-validation/"
printf 'print("ok")\n' > "$fake/attestation/lib/validate.py"
printf '#!/usr/bin/env bash\necho "qkt 0.0.0 (test)"\n' > "$fake/bin/qkt"; chmod +x "$fake/bin/qkt"
for id in perpetual option; do
    mkdir -p "$fake/attestation/cases/derivatives/$id"
    printf 'id: %s\nstatus: ready\n' "$id" > "$fake/attestation/cases/derivatives/$id/case.yaml"
done
# The stub runner: a case fails when its id is in FAIL_CASES, and fails loudly if another case is running.
cat > "$fake/scripts/live-validation/run-derivatives-lane-case.py" <<'STUB'
import json, os, sys, time
args = dict(zip(sys.argv[1::2], sys.argv[2::2]))
case, out = os.path.basename(args["--case"]), args["--out"]
lock = os.path.join(os.path.dirname(out), "busy")
os.makedirs(out)
try:
    os.mkdir(lock)
except FileExistsError:
    json.dump({"status": "failed", "problems": ["overlapped another case"]}, open(f"{out}/result.json", "w")); sys.exit(1)
time.sleep(1); os.rmdir(lock)
failed = case in os.environ.get("FAIL_CASES", "").split(",")
json.dump({"status": "failed" if failed else "passed", "problems": ["venue net differs"] if failed else []},
          open(f"{out}/result.json", "w"))
sys.exit(1 if failed else 0)
STUB

run() {  # out-dir [deriv flags...]
    local out="$1"; shift
    QKT_BROKER_API_KEY=x QKT_LIVE_DEMO_ORDER_APPROVAL=LOCALHOST_DEMO_ONLY bash "$fake/scripts/live-validation/run-attestation-catalog.sh" \
        --out "$out" --gateway-url http://127.0.0.1:5001 --expected-login 1 --expected-server S --magic-base 100 \
        --arm I_UNDERSTAND_DEMO_ORDER_0.01 --lanes derivatives --cli "$fake/bin/qkt" "$@"
}
deriv=(--deriv-gateway-url http://127.0.0.1:8444 --deriv-expected-login g-1)

if run "$tmp/no-flags" 2> "$tmp/no-flags.txt"; then echo "FAIL the lane must refuse to run without its gateway"; exit 1; fi
grep -q 'needs --deriv-gateway-url' "$tmp/no-flags.txt"
echo "ok the derivatives lane needs its gateway flags"

run "$tmp/passing" "${deriv[@]}" > /dev/null
jq -e '.status == "passed" and ([.runs[].name] | sort) == ["derivatives-option", "derivatives-perpetual"]' \
    "$tmp/passing/result.json" > /dev/null || { echo "FAIL verdicts: $(cat "$tmp/passing/result.json")"; exit 1; }
echo "ok each derivatives case runs alone and gets its own verdict"

if FAIL_CASES=option run "$tmp/failing" "${deriv[@]}" > /dev/null; then echo "FAIL a failed case must fail the run"; exit 1; fi
jq -e '.status == "failed" and ([.runs[] | select(.name == "derivatives-option")][0] | .status == "failed"
       and .summary == "venue net differs")
       and ([.runs[] | select(.name == "derivatives-perpetual")][0] | .status == "passed")' \
    "$tmp/failing/result.json" > /dev/null || { echo "FAIL verdicts: $(cat "$tmp/failing/result.json")"; exit 1; }
echo "ok one failed derivatives case fails the run"
