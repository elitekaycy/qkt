#!/usr/bin/env bash
# run-attestation.sh must refuse to start, before any gateway or build work, when it is not
# given everything an unattended run needs.
set -euo pipefail
repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
script="$repo_root/scripts/live-validation/run-attestation.sh"
tmp="$(mktemp -d)"; trap 'rm -rf "$tmp"' EXIT

expect_refusal() {  # name expected-text command...
    local name="$1" text="$2"; shift 2
    if out="$("$@" 2>&1)"; then echo "FAIL $name: exited 0"; exit 1; fi
    grep -qF -- "$text" <<<"$out" || { echo "FAIL $name: missing '$text' in: $out"; exit 1; }
    echo "ok $name"
}

expect_refusal "no profile" "Usage: run-attestation.sh" bash "$script"
expect_refusal "unknown flag" "unknown argument: --nope" bash "$script" --nope

printf 'gateway_url=http://127.0.0.1:1\n' > "$tmp/partial"
expect_refusal "incomplete profile" "profile does not set expected_login" bash "$script" --profile "$tmp/partial"

cp "$repo_root/scripts/live-validation/attestation-profile.example" "$tmp/full"
expect_refusal "no broker key" "QKT_BROKER_API_KEY must be set" \
    env -u QKT_BROKER_API_KEY bash "$script" --profile "$tmp/full"
expect_refusal "no demo approval" "QKT_LIVE_DEMO_ORDER_APPROVAL must be set" \
    env -u QKT_LIVE_DEMO_ORDER_APPROVAL QKT_BROKER_API_KEY=k bash "$script" --profile "$tmp/full"

python3 -m py_compile "$repo_root/scripts/live-validation/assemble-attestation.py"
echo "ok assembler compiles"
