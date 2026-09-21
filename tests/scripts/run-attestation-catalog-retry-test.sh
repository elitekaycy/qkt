#!/usr/bin/env bash
# A case that fails beside the others gets one attempt alone: it passes only if that attempt
# passes, the first failure stays in the result, and a case that fails twice fails the run.
set -euo pipefail
repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
tmp="$(mktemp -d)"; trap 'rm -rf "$tmp"' EXIT
fake="$tmp/repo"; mkdir -p "$fake/scripts/live-validation" "$fake/attestation/lib" "$fake/bin"
cp "$repo_root/scripts/live-validation/run-attestation-catalog.sh" "$fake/scripts/live-validation/"
printf 'print("ok")\n' > "$fake/attestation/lib/validate.py"
printf '#!/usr/bin/env bash\necho "qkt 0.0.0 (test)"\n' > "$fake/bin/qkt"; chmod +x "$fake/bin/qkt"
for id in steady contended broken; do
    mkdir -p "$fake/attestation/cases/orders/$id"
    printf 'id: %s\nstatus: ready\n' "$id" > "$fake/attestation/cases/orders/$id/case.yaml"
done
# The stub runner: `steady` passes, `contended` fails once and then passes, `broken` always fails.
cat > "$fake/scripts/live-validation/run-order-lane-case.sh" <<'STUB'
#!/usr/bin/env bash
while [ "$#" -gt 0 ]; do case "$1" in --case) c="$(basename "$2")"; shift 2 ;; --out) o="$2"; shift 2 ;; *) shift ;; esac; done
mkdir -p "$o"
case "$c" in
    steady) echo "passed steady" ;;
    contended) [[ "$o" == *-retry ]] && echo "passed contended" || { echo "failed contended stale quote"; exit 1; } ;;
    broken) echo "failed broken for real"; exit 1 ;;
esac
STUB

run() {  # out-dir lanes-present
    QKT_BROKER_API_KEY=x QKT_LIVE_DEMO_ORDER_APPROVAL=LOCALHOST_DEMO_ONLY bash "$fake/scripts/live-validation/run-attestation-catalog.sh" \
        --out "$1" --gateway-url http://127.0.0.1:5001 --expected-login 1 --expected-server S --magic-base 100 \
        --arm I_UNDERSTAND_DEMO_ORDER_0.01 --lanes orders --cli "$fake/bin/qkt"
}

if run "$tmp/with-broken" > "$tmp/with-broken.txt"; then echo "FAIL a case that fails twice must fail the run"; exit 1; fi
jq -e '.status == "failed" and .retriedAlone == ["orders-broken", "orders-contended"]
       and ([.runs[] | select(.name == "orders-broken")][0] | .status == "failed" and .retriedAlone == true)
       and ([.runs[] | select(.name == "orders-contended")][0] | .status == "passed" and .firstAttempt == "failed contended stale quote")
       and ([.runs[] | select(.name == "orders-steady")][0] | .status == "passed" and (has("retriedAlone") | not))' \
    "$tmp/with-broken/result.json" > /dev/null || { echo "FAIL verdicts: $(cat "$tmp/with-broken/result.json")"; exit 1; }
grep -q 'RETRIED ALONE orders-contended: first attempt: failed contended stale quote' "$tmp/with-broken.txt"
echo "ok a case that fails twice fails the run, and the first failure is kept"

rm -rf "$fake/attestation/cases/orders/broken"
run "$tmp/recovered" > /dev/null
jq -e '.status == "passed" and .retriedAlone == ["orders-contended"]' "$tmp/recovered/result.json" > /dev/null
echo "ok a case that passes alone passes the run and is named in retriedAlone"
