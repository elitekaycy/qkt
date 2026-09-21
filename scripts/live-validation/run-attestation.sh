#!/usr/bin/env bash
# Runs the whole live-parity attestation for one testing commit without a prompt:
# clean build -> parity wave -> insights attribution -> bundle -> verify -> optional dispatch.
set -euo pipefail
repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"

usage() {
    cat <<'USAGE'
Usage: run-attestation.sh --profile FILE [--dispatch] [--attempts N]

FILE is a shell fragment that sets:
  gateway_url  expected_login  expected_server  expected_leverage
  wave_root    bundle_root     image_repository insights_image   magic_base

Secrets come from the environment only: QKT_BROKER_API_KEY (a long random value on a
no-auth local gateway) and QKT_LIVE_DEMO_ORDER_APPROVAL=LOCALHOST_DEMO_ONLY.

Must run from a clean checkout of the testing commit. Progress is written to
<wave_root>/attestation-run.json after every stage. Exit 0 only when the assembled bundle
passes verify-paper-soak-attestation.py. --dispatch then starts paper-soak.yml on testing.
USAGE
}
fail() { stage_write failed "$1"; printf 'run-attestation: %s\n' "$1" >&2; exit 1; }
log() { printf '%s %s\n' "$(date -u +%T)" "$*"; }

profile=""; dispatch=false; attempts=3
while [ "$#" -gt 0 ]; do
    case "$1" in
        --profile) profile="${2:-}"; shift 2 ;;
        --dispatch) dispatch=true; shift ;;
        --attempts) attempts="${2:-}"; shift 2 ;;
        --help|-h) usage; exit 0 ;;
        *) printf 'run-attestation: unknown argument: %s\n' "$1" >&2; exit 2 ;;
    esac
done
[ -f "$profile" ] || { usage >&2; exit 2; }
# shellcheck disable=SC1090
. "$profile"
for v in gateway_url expected_login expected_server expected_leverage wave_root bundle_root image_repository insights_image magic_base; do
    [ -n "${!v:-}" ] || { printf 'run-attestation: profile does not set %s\n' "$v" >&2; exit 2; }
done
: "${QKT_BROKER_API_KEY:?QKT_BROKER_API_KEY must be set}"
: "${QKT_LIVE_DEMO_ORDER_APPROVAL:?QKT_LIVE_DEMO_ORDER_APPROVAL must be set}"
for tool in jq gh curl python3 unzip; do command -v "$tool" >/dev/null || { echo "run-attestation: $tool is required" >&2; exit 2; }; done

cd "$repo_root"
sha="$(git rev-parse HEAD)"; short="${sha:0:8}"
status_file="$wave_root/attestation-run.json"; mkdir -p "$wave_root" "$bundle_root"
started="$(date -u +%FT%TZ)"
stage_write() {  # stage detail
    jq -n --arg sha "$sha" --arg stage "$1" --arg detail "${2:-}" --arg started "$started" --arg at "$(date -u +%FT%TZ)" \
        '{schema:"qkt-attestation-run-v1",testingSha:$sha,stage:$stage,detail:$detail,startedAtUtc:$started,updatedAtUtc:$at}' > "$status_file"
}
balance() { curl -fsS -m 10 "$gateway_url/account" | jq -er '(.data // .).balance'; }

stage_write preflight
[ -z "$(git status --porcelain)" ] || fail "working tree is not clean; the binary would not correspond to $short"
[ "$(curl -fsS -m 10 "$gateway_url/get_positions" | jq -er '(.data // .) | length')" = 0 ] || fail "account is not flat"

stage_write build
./gradlew -q clean installDist -PqktGitSha="$sha" > "$wave_root/build-$short.log" 2>&1 || fail "build failed"
cli_root="$wave_root/cli-$short"; rm -rf "$cli_root"; cp -r build/install/qkt "$cli_root"
./gradlew --stop >/dev/null 2>&1 || true
cli="$cli_root/bin/qkt"; log "cli $("$cli" --version | head -n 1)"

# An earlier attestation that died badly may have left an order or a position under one of its magics;
# the wave would refuse to start on it. Clear this attestation's own magic range, and only that:
# anything else on the account fails the run here, with the tickets named.
stage_write sweep "clearing leftovers under magics $magic_base..$((magic_base + 1000))"
swept="$(python3 scripts/live-validation/sweep-attestation-leftovers.py --gateway-url "$gateway_url" \
    --expected-login "$expected_login" --expected-server "$expected_server" \
    --magic-from "$magic_base" --magic-to "$((magic_base + 1000))")" || fail "account is not clean and the sweep could not make it so: $swept"
log "sweep $swept"

wave="$wave_root/attest-$short"
for attempt in $(seq 1 "$attempts"); do
    stage_write wave "attempt $attempt"
    if [ -e "$wave" ]; then mv "$wave" "$wave.failed-$attempt"; mv "$wave.wave.log" "$wave.failed-$attempt.log" 2>/dev/null || true; fi
    # run-market-bracket.sh takes the account lock itself; wrapping this in flock deadlocks it.
    bash scripts/live-validation/run-parity-suite.sh --output "$wave" --id "wave_$short" --gateway-url "$gateway_url" \
        --expected-login "$expected_login" --expected-server "$expected_server" --expected-balance "$(balance)" \
        --expected-leverage "$expected_leverage" --magic-base "$((magic_base + attempt * 10))" --cli "$cli" \
        --run-live --parallel --arm I_UNDERSTAND_DEMO_ORDER_0.01 > "$wave.wave.log" 2>&1 < /dev/null || true
    results="$(find "$wave/cases" -maxdepth 4 -name result.json 2>/dev/null | wc -l)"
    log "wave attempt $attempt results=$results"
    [ "$results" = 12 ] && [ "$(tail -n 1 "$wave.wave.log")" = "$wave" ] && break
    [ "$attempt" = "$attempts" ] && fail "parity wave failed $attempts times; see $wave.wave.log"
    sleep 60
done

# Every other ready case in the catalog - orders, risk, book, engine, daemon, stress - all at once,
# each under its own magic. The shadow lane already ran inside the wave. Any failed case fails the
# attestation: promotion to main means the whole catalog passed on this exact build.
stage_write catalog
catalog="$wave_root/attest-$short-catalog"; rm -rf "$catalog"
bash scripts/live-validation/run-attestation-catalog.sh --out "$catalog" --gateway-url "$gateway_url" \
    --expected-login "$expected_login" --expected-server "$expected_server" --magic-base "$((magic_base + 100))" \
    --lanes orders,risk,book,engine,daemon,stress --arm I_UNDERSTAND_DEMO_ORDER_0.01 --cli "$cli" \
    > "$catalog.log" 2>&1 || fail "attestation catalog failed: $(grep -m3 FAILED "$catalog.log" | tr '\n' ' ' | cut -c1-300)"
log "catalog $(tail -n 1 "$catalog.log" | cut -c1-160)"

stage_write insights
ins="$wave_root/attest-$short-insights"; rm -rf "$ins"
export QKT_BROKER_EXNESS_EXPECTED_ACCOUNT_LOGIN="$expected_login" QKT_BROKER_EXNESS_EXPECTED_ACCOUNT_SERVER="$expected_server"
bash scripts/live-validation/prepare-scenario.sh --output "$ins" --id "ins_$short" --gateway-url "$gateway_url" \
    --runtime-account-identity --expected-balance "$(balance)" --expected-leverage "$expected_leverage" \
    --magic "$((magic_base + 60))" > "$ins.prepare.log" 2>&1 || fail "insights scenario did not prepare"
bash scripts/live-validation/run-insights-attribution.sh --scenario "$ins" --insights-image "$insights_image" \
    --cli "$cli" --arm I_UNDERSTAND_DEMO_ORDER_0.01 > "$ins.log" 2>&1 || fail "insights attribution failed: $(tail -n 1 "$ins.log")"

stage_write bundle
# The token may lack read:packages, so the digest comes from the docker workflow log of this commit.
docker_run="$(gh run list --workflow docker.yml --branch testing --limit 10 --json databaseId,headSha \
    --jq ".[] | select(.headSha==\"$sha\") | .databaseId" | head -n 1)"
[ -n "$docker_run" ] || fail "no docker workflow run for $short on testing"
digest="$(gh run view "$docker_run" --log 2>/dev/null | sed 's/\x1b\[[0-9;]*m//g' |
    grep "pushing manifest for $image_repository:edge@" | grep -o 'sha256:[0-9a-f]\{64\}' | head -n 1)"
[ -n "$digest" ] || fail "could not resolve the :edge digest for $short"
bundle="$bundle_root/build-$short"; rm -rf "$bundle"
python3 scripts/live-validation/assemble-attestation.py "$wave" "$ins" "$bundle" "$image_repository@$digest" "$sha" \
    > "$wave_root/assemble-$short.log" 2>&1 || fail "bundle assembly failed; see assemble-$short.log"
python3 scripts/verify-paper-soak-attestation.py "$bundle/attestation.json" --expected-git-sha "$sha" \
    --expected-image-repository "$image_repository" || fail "assembled bundle does not verify"

if [ "$dispatch" = true ]; then
    stage_write dispatch
    runner_online="$(gh api "repos/{owner}/{repo}/actions/runners" --jq '[.runners[] | select(.status == "online")] | length' 2>/dev/null || echo 0)"
    [ "$runner_online" -gt 0 ] || fail "no self-hosted runner is online to verify the attestation; start it and re-run with the same bundle"
    gh workflow run paper-soak.yml --ref testing -f attestation_path="$bundle/attestation.json" >/dev/null
fi
stage_write attested "$bundle/attestation.json"
log "attested $bundle/attestation.json"
