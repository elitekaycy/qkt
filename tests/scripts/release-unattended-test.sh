#!/usr/bin/env bash
# The release driver must refuse, before touching GitHub or the gateway, when it is not given
# what an unattended release needs.
set -euo pipefail
repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
script="$repo_root/scripts/release-unattended.sh"
tmp="$(mktemp -d)"; trap 'rm -rf "$tmp"' EXIT

expect_refusal() {  # name expected-text command...
    local name="$1" text="$2"; shift 2
    if out="$("$@" 2>&1)"; then echo "FAIL $name: exited 0"; exit 1; fi
    grep -qF -- "$text" <<<"$out" || { echo "FAIL $name: missing '$text' in: $out"; exit 1; }
    echo "ok $name"
}

touch "$tmp/profile"; printf 'k' > "$tmp/key"; chmod 644 "$tmp/key"
expect_refusal "no arguments" "Usage: release-unattended.sh" bash "$script"
expect_refusal "unknown flag" "unknown argument: --nope" bash "$script" --nope
expect_refusal "missing profile" "profile not found" bash "$script" --profile "$tmp/none" --key-file "$tmp/key"
expect_refusal "missing key file" "key file not found" bash "$script" --profile "$tmp/profile" --key-file "$tmp/none"
expect_refusal "readable key file" "key file must be mode 0600" bash "$script" --profile "$tmp/profile" --key-file "$tmp/key"
