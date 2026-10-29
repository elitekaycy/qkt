#!/usr/bin/env bash
# run-attestation.sh resolves the :edge digest of the commit it attests before building or touching the account,
# and stops with a reason (stage `failed`, detail set) when it cannot: a gh whose `run view --log` comes back empty
# once let the run pass its whole live catalog and then end silently in the bundle stage.
set -euo pipefail
repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
tmp="$(mktemp -d)"; trap 'rm -rf "$tmp"' EXIT

# A clean repository holding the script, so its preflight tree check passes without depending on this checkout.
mkdir -p "$tmp/repo/scripts/live-validation" "$tmp/bin" "$tmp/wave" "$tmp/bundle"
cp "$repo_root/scripts/live-validation/run-attestation.sh" "$tmp/repo/scripts/live-validation/"
git -C "$tmp/repo" init -q
git -C "$tmp/repo" -c user.name=t -c user.email=t@t add -A
git -C "$tmp/repo" -c user.name=t -c user.email=t@t commit -qm t
sed -e "s|^wave_root=.*|wave_root=$tmp/wave|" -e "s|^bundle_root=.*|bundle_root=$tmp/bundle|" \
    "$repo_root/scripts/live-validation/attestation-profile.example" > "$tmp/profile"

printf '#!/usr/bin/env bash\necho "{\\"data\\": []}"\n' > "$tmp/bin/curl"
cat > "$tmp/bin/gh" <<'GH'
#!/usr/bin/env bash
case "$1 ${2:-}" in
    "run list") [ "${GH_NO_RUN:-}" = 1 ] || echo 4242 ;;
    "run view") [ "${GH_LOG:-}" = "" ] || printf '%s\n' "$GH_LOG" ;;
    "--version ") echo "gh version 2.55.0 (stub)" ;;
    *) echo "unexpected gh $*" >&2; exit 9 ;;
esac
GH
chmod +x "$tmp/bin/curl" "$tmp/bin/gh"
run() { env PATH="$tmp/bin:$PATH" QKT_BROKER_API_KEY=k QKT_LIVE_DEMO_ORDER_APPROVAL=a "$@" \
    bash "$tmp/repo/scripts/live-validation/run-attestation.sh" --profile "$tmp/profile"; }

expect_stop() {  # name expected-text env...
    local name="$1" text="$2"; shift 2
    if out="$(run "$@" 2>&1)"; then echo "FAIL $name: exited 0"; exit 1; fi
    grep -qF -- "$text" <<<"$out" || { echo "FAIL $name: missing '$text' in: $out"; exit 1; }
    status="$(cat "$tmp/wave/attestation-run.json")"
    [ "$(jq -r .stage <<<"$status")" = failed ] || { echo "FAIL $name: stage not failed: $status"; exit 1; }
    grep -qF -- "$text" <<<"$(jq -r .detail <<<"$status")" || { echo "FAIL $name: no reason recorded: $status"; exit 1; }
    [ ! -e "$tmp/wave/cli-$(git -C "$tmp/repo" rev-parse --short=8 HEAD)" ] || { echo "FAIL $name: it built"; exit 1; }
    echo "ok $name"
}

expect_stop "an empty docker log stops the run before the build" "could not resolve the :edge digest" GH_LOG=
expect_stop "the reason names the gh that could not read it" "gh version 2.55.0" GH_LOG=
expect_stop "a log without this image's manifest stops it too" "could not resolve the :edge digest" \
    GH_LOG="#30 pushing manifest for ghcr.io/someone/else:edge@sha256:$(printf '1%.0s' {1..64})"
expect_stop "no docker run for the commit stops it with that reason" "no docker workflow run for" GH_NO_RUN=1
