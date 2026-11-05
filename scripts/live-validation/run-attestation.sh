#!/usr/bin/env bash
# Runs the whole live-parity attestation for one testing commit without a prompt:
# preflight -> clean build -> proofs -> parity wave -> catalog -> insights attribution -> bundle -> verify -> optional dispatch.
# Rerun on the same commit, it keeps what passed on this exact build (lib/attest_resume.py) and repeats the rest.
set -euo pipefail
repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"

usage() {
    cat <<'USAGE'
Usage: run-attestation.sh --profile FILE [--dispatch] [--attempts N]

FILE is a shell fragment that sets:
  gateway_url  expected_login  expected_server  expected_leverage
  wave_root    bundle_root     image_repository insights_image   magic_base
  data_corpus  (a clone of elitekaycy/qkt-data-corpus, for the release proofs)
and may set docs_data_root, the local store the docs examples read (adds them to the proofs).
and may set session_wait_max_h (default 72): how far ahead a session lane's window (stocks, alpaca) may be. A run that
reaches such a lane outside a window it fits in runs every other lane now and waits for the window; only a window
further away than this stops it at preflight. When waiting would age the first evidence past the bundle's 24 h limit,
the whole run is held until shortly before the window. Each wait is logged, written to attestation-run.json (stage
`hold`) and carried in the bundle's catalog evidence (`sessionWaits`).
and may set attestation_symbol=BTCUSD: the wave, the shadow lane and the insights scenario then
run on 24/7 crypto (BTCUSD, with ETHUSD for a second symbol), so an attestation can run while FX
and metals are closed. Unset, they trade EURUSD, GBPUSD and XAUUSD as before.

Secrets come from the environment only: QKT_BROKER_API_KEY (a long random value on a
no-auth local gateway) and QKT_LIVE_DEMO_ORDER_APPROVAL=LOCALHOST_DEMO_ONLY.

Must run from a clean checkout of the testing commit. Progress is written to
<wave_root>/attestation-run.json after every stage. Exit 0 only when the assembled bundle
passes verify-paper-soak-attestation.py. --dispatch then starts paper-soak.yml on testing.

Run again after a stop: evidence bound to this commit, this profile file, this account and this built CLI, and younger
than 24 hours, is kept. Preflight, the sweep, every case that did not pass, the bundle and the verify always run again.
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
for v in gateway_url expected_login expected_server expected_leverage wave_root bundle_root image_repository insights_image magic_base data_corpus; do
    [ -n "${!v:-}" ] || { printf 'run-attestation: profile does not set %s\n' "$v" >&2; exit 2; }
done
: "${QKT_BROKER_API_KEY:?QKT_BROKER_API_KEY must be set}"
: "${QKT_LIVE_DEMO_ORDER_APPROVAL:?QKT_LIVE_DEMO_ORDER_APPROVAL must be set}"
for tool in jq python3 git; do command -v "$tool" >/dev/null || { echo "run-attestation: $tool is required" >&2; exit 2; }; done

cd "$repo_root"
sha="$(git rev-parse HEAD)"; short="${sha:0:8}"
status_file="$wave_root/attestation-run.json"; mkdir -p "$wave_root" "$bundle_root"
started="$(date -u +%FT%TZ)"
stage_write() {  # stage detail
    jq -n --arg sha "$sha" --arg stage "$1" --arg detail "${2:-}" --arg started "$started" --arg at "$(date -u +%FT%TZ)" \
        '{schema:"qkt-attestation-run-v1",testingSha:$sha,stage:$stage,detail:$detail,startedAtUtc:$started,updatedAtUtc:$at}' > "$status_file"
}
balance() { curl -fsS -m 10 "$gateway_url/account" | jq -er '(.data // .).balance'; }

# A stop with no reason cost two 95-minute runs: whatever ends the script, its status says where.
trap 'fail "stopped at line $LINENO of run-attestation.sh during stage $(jq -r .stage "$status_file" 2>/dev/null)"' ERR

# What this rerun may keep is decided first; nothing is touched until the preflight has passed.
lib="$repo_root/scripts/live-validation/lib"
ledger="$wave_root/attest-$short.resume.json"
cli_root="$wave_root/cli-$short"; cli="$cli_root/bin/qkt"
proofs="$wave_root/attest-$short-proofs"
wave="$wave_root/attest-$short"
catalog="$wave_root/attest-$short-catalog"; ins="$wave_root/attest-$short-insights"
plan="$wave_root/attest-$short.plan.json"; binding="$wave_root/attest-$short.binding.json"
# Every other ready case in the catalog - orders, risk, book, engine, daemon, stress - all at once, each under its own
# magic. The shadow lane already ran inside the wave. A profile that names a derivatives gateway (deriv_gateway_url,
# deriv_expected_login; key in QKT_DERIV_GATEWAY_KEY) adds the derivatives lane; one that names a Bybit gateway
# (bybit_gateway_url, bybit_expected_login; key in QKT_BYBIT_GATEWAY_KEY) adds the bybit lane; attest_stocks=true adds
# the stocks lane (US share CFDs on the same MT5 account, inside attestation/lanes.yaml's window, with a fresh AAPL quote);
# one that names an Alpaca gateway (alpaca_gateway_url, alpaca_expected_login; key in QKT_ALPACA_GATEWAY_KEY) adds the
# alpaca lane: US shares through the gateway's Alpaca adapter; one that names cross_venues (LABEL=URL@LOGIN, space
# separated; keys in QKT_CROSS_<LABEL>_KEY and QKT_CROSS_<LABEL>_GUARDIAN_KEY) adds the cross lane on the MT5 demo and them.
lanes=orders,risk,book,engine,daemon,stress; deriv=()
if [ -n "${deriv_gateway_url:-}" ]; then
    lanes="$lanes,derivatives"
    deriv=(--deriv-gateway-url "$deriv_gateway_url" --deriv-expected-login "${deriv_expected_login:?profile sets deriv_gateway_url without deriv_expected_login}")
fi
if [ "${attest_stocks:-false}" = true ]; then lanes="$lanes,stocks"; fi
if [ -n "${bybit_gateway_url:-}" ]; then
    lanes="$lanes,bybit"
    deriv+=(--bybit-gateway-url "$bybit_gateway_url" --bybit-expected-login "${bybit_expected_login:?profile sets bybit_gateway_url without bybit_expected_login}")
fi
if [ -n "${alpaca_gateway_url:-}" ]; then
    lanes="$lanes,alpaca"
    deriv+=(--alpaca-gateway-url "$alpaca_gateway_url" --alpaca-expected-login "${alpaca_expected_login:?profile sets alpaca_gateway_url without alpaca_expected_login}")
fi
if [ -n "${cross_venues:-}" ]; then
    lanes="$lanes,cross"
    for venue in $cross_venues; do deriv+=(--cross-venue "$venue"); done
fi

stage_write preflight
problems=()
[ -z "$(git status --porcelain)" ] || problems+=(--also-failed "working tree is not clean; the binary would not correspond to $short")
# The bundle pins the :edge image this commit published. Resolved here, not after the live catalog: a gh that
# cannot read the docker workflow log (gh 2.55 returns an empty one) must stop the run in seconds, with a reason.
# The token may lack read:packages, so the digest comes from the docker workflow log of this commit. Looked up by
# commit: a branch listing is newest-first by creation and can push this commit's run out of its window. `|| true`
# keeps an empty lookup from ending the script under pipefail before the preflight can say why.
docker_run="$(gh run list --workflow docker.yml --branch testing --commit "$sha" --status success --json databaseId \
    --jq ".[0].databaseId" 2>/dev/null || true)"
digest=""
if [ -z "$docker_run" ]; then
    problems+=(--also-failed "no docker workflow run for $short on testing")
else
    digest="$(gh run view "$docker_run" --log 2>/dev/null | sed 's/\x1b\[[0-9;]*m//g' |
        grep "pushing manifest for $image_repository:edge@" | grep -o 'sha256:[0-9a-f]\{64\}' | head -n 1 || true)"
    [ -n "$digest" ] || problems+=(--also-failed "could not resolve the :edge digest for $short from docker run $docker_run ($(gh --version 2>/dev/null | head -n 1))")
fi
# The release the fleet runs now: its store must still read under this build.
previous_tag="$(gh release view --json tagName --jq .tagName 2>/dev/null || true)"
[ -n "$previous_tag" ] || problems+=(--also-failed "cannot find the latest release for the compatibility proof")
cli_version=""; [ ! -x "$cli" ] || cli_version="$("$cli" --version 2>/dev/null | head -n 1 || true)"
# Session lanes are planned, not refused (lib/attest_session.py): the preflight writes when each runs. When waiting for a
# window would age the first evidence past the verifier's limit, the plan holds the whole run until shortly before it;
# the hold is logged, recorded and followed by a fresh plan and preflight, since the account may have changed meanwhile.
schedule="$wave_root/attest-$short.schedule.json"; session_waits="$wave_root/attest-$short.session-waits.jsonl"
dispatch_flag=(); [ "$dispatch" != true ] || dispatch_flag=(--dispatch)
for pass in 1 2 3; do
    rm -f "$schedule"
    python3 "$lib/attest_resume.py" plan --ledger "$ledger" --sha "$sha" --profile "$profile" --login "$expected_login" \
        --server "$expected_server" --cli-root "$cli_root" --cli-version "${cli_version:-none}" --proofs "$proofs" --wave "$wave" \
        --insights "$ins" --catalog "$catalog" --lanes "$lanes" --repo-root "$repo_root" --previous-tag "$previous_tag" \
        --corpus-lock "$(cut -d' ' -f1 "$repo_root/tests/data-e2e/corpus.lock" 2>/dev/null || true)" \
        --image-id "$(docker image inspect --format '{{.Id}}' "$insights_image" 2>/dev/null || true)" --out "$plan" > "$plan.txt"
    log "$(cat "$plan.txt")"
    checked=0
    python3 "$lib/attest_preflight.py" --profile "$profile" --repo-root "$repo_root" "${dispatch_flag[@]}" --plan "$plan" \
        --schedule "$schedule" --report "$wave_root/attest-$short-preflight.json" "${problems[@]}" \
        > "$wave_root/attest-$short-preflight.log" 2>&1 || checked=$?
    if [ "$checked" != 0 ]; then
        cat "$wave_root/attest-$short-preflight.log" >&2
        fail "preflight: $(tail -n 1 "$wave_root/attest-$short-preflight.log" | cut -c1-900)"
    fi
    log "$(tail -n 1 "$wave_root/attest-$short-preflight.log")"
    [ -f "$schedule" ] || break
    log "session plan: $(jq -r .summary "$schedule")"
    hold="$(jq -r '.holdUntilUtc // empty' "$schedule")"
    [ -n "$hold" ] && [ "$pass" != 3 ] || break
    why="waiting in the catalog would age the first evidence past the bundle's limit: $(jq -r .summary "$schedule")"
    stage_write hold "until $hold: $why"
    log "holding the run until $hold: $why"
    python3 "$lib/attest_session.py" wait-until --until "$hold" --lane run --reason "$why" --record "$session_waits"
done
reuse() { [ "$(jq -r ".$1" "$plan")" = true ]; }

if reuse fresh; then
    # Nothing of an earlier run of this commit can stand: set it aside, for the flake report, not for the bundle.
    for old in "$proofs" "$catalog" "$ins"; do
        [ -e "$old" ] || continue
        n=1; while [ -e "$old.stale-$n" ]; do n=$((n + 1)); done
        mv "$old" "$old.stale-$n"
    done
    rm -f "$ledger"
    stage_write build
    ./gradlew -q clean installDist -PqktGitSha="$sha" > "$wave_root/build-$short.log" 2>&1 || fail "build failed"
    rm -rf "$cli_root"; cp -r build/install/qkt "$cli_root"
    ./gradlew --stop >/dev/null 2>&1 || true
    cli_version="$("$cli" --version | head -n 1)"
    python3 "$lib/attest_resume.py" binding --sha "$sha" --profile "$profile" --login "$expected_login" --server "$expected_server" \
        --cli-root "$cli_root" --cli-version "$cli_version" --out "$binding"
    python3 "$lib/attest_resume.py" begin --ledger "$ledger" --binding "$binding"
else
    log "kept the CLI built earlier"
    jq .binding "$plan" > "$binding"
fi
log "cli $cli_version"

# Offline and before the account is touched: every real-data case, qkt against backtesting.py and backtrader,
# and the 48-scenario audit, on the pinned corpus. A build that fails any of them is never promoted.
stage_write proofs
if reuse reuseProofs; then
    log "proofs kept from the earlier run"
else
    rm -rf "$proofs"
    previous_root="$wave_root/release-${previous_tag#v}"
    if [ ! -x "$previous_root/qkt-${previous_tag#v}/bin/qkt" ]; then
        mkdir -p "$previous_root"
        gh release download "$previous_tag" --pattern "qkt-${previous_tag#v}.tar" --dir "$previous_root" --clobber > /dev/null &&
            tar -xf "$previous_root/qkt-${previous_tag#v}.tar" -C "$previous_root" || fail "cannot download release $previous_tag"
    fi
    bash scripts/release-proof/run.sh --cli "$cli" --corpus "$data_corpus" --out "$proofs" \
        --previous "$previous_root/qkt-${previous_tag#v}/bin/qkt" \
        ${docs_data_root:+--docs-data "$docs_data_root"} > "$proofs.log" 2>&1 ||
        fail "release proofs failed: $(grep -m3 '^fail' "$proofs.log" | tr '\n' ' ' | cut -c1-300)"
    log "proofs $(tr '\n' ' ' < "$proofs.log" | cut -c1-200)"
    python3 "$lib/attest_resume.py" stage --ledger "$ledger" --name proofs --evidence "$proofs/proofs.json" \
        --meta "previousTag=$previous_tag" --meta "corpusLock=$(cut -d' ' -f1 "$repo_root/tests/data-e2e/corpus.lock")"
fi

# An earlier attestation that died badly may have left an order or a position under one of its magics;
# the wave would refuse to start on it. Clear this attestation's own magic range, and only that:
# anything else on the account fails the run here, with the tickets named.
stage_write sweep "clearing leftovers under magics $magic_base..$((magic_base + 1000))"
swept="$(python3 scripts/live-validation/sweep-attestation-leftovers.py --gateway-url "$gateway_url" \
    --expected-login "$expected_login" --expected-server "$expected_server" \
    --magic-from "$magic_base" --magic-to "$((magic_base + 1000))")" || fail "account is not clean and the sweep could not make it so: $swept"
log "sweep $swept"

if reuse reuseWave; then
    log "wave kept from the earlier run"
else
    wave_attempts="$wave.attempts.jsonl"; : > "$wave_attempts"
    for attempt in $(seq 1 "$attempts"); do
        stage_write wave "attempt $attempt"
        # A rerun of the same commit finds earlier runs' attempts already rotated: take the next free name, since `mv`
        # onto an existing directory moves into it and the run after that cannot.
        if [ -e "$wave" ]; then
            n=1; while [ -e "$wave.failed-$n" ] || [ -e "$wave.failed-$n.log" ]; do n=$((n + 1)); done
            mv "$wave" "$wave.failed-$n"; mv "$wave.wave.log" "$wave.failed-$n.log" 2>/dev/null || true
        fi
        # run-market-bracket.sh takes the account lock itself; wrapping this in flock deadlocks it.
        bash scripts/live-validation/run-parity-suite.sh --output "$wave" --id "wave_$short" --gateway-url "$gateway_url" \
            --expected-login "$expected_login" --expected-server "$expected_server" --expected-balance "$(balance)" \
            --expected-leverage "$expected_leverage" --magic-base "$((magic_base + attempt * 10))" --cli "$cli" \
            ${attestation_symbol:+--symbol "$attestation_symbol"} \
            --run-live --parallel --arm I_UNDERSTAND_DEMO_ORDER_0.01 > "$wave.wave.log" 2>&1 < /dev/null || true
        results="$( { find "$wave/cases" -maxdepth 4 -name result.json 2>/dev/null || true; } | wc -l)"
        log "wave attempt $attempt results=$results"
        [ "$results" = 12 ] && [ "$(tail -n 1 "$wave.wave.log")" = "$wave" ] && break
        # A wave that recovers on a later attempt still shows, in the bundle, the attempts before it.
        why="$(tail -n 1 "$wave.wave.log" | cut -c1-300)"
        jq -cn --argjson attempt "$attempt" --arg why "$why" \
            --arg class "$(python3 -c 'import sys; sys.path.insert(0, sys.argv[1]); import attest_classify as c; print(c.classify([sys.argv[2]]))' "$lib" "$why")" \
            '{attempt:$attempt, status:"failed", class:$class, summary:$why}' >> "$wave_attempts"
        log "wave attempt $attempt failed: $why"
        [ "$attempt" = "$attempts" ] && fail "parity wave failed $attempts times; see $wave.wave.log: $why"
        sleep 60
    done
    python3 "$lib/attest_resume.py" stage --ledger "$ledger" --name wave --evidence "$wave/SHA256SUMS" --attempts "$wave_attempts"
fi

# Any failed case fails the attestation: promotion to main means the whole catalog passed on this exact build, and
# the bundle's catalog evidence says so case by case.
stage_write catalog "$( [ -f "$schedule" ] && jq -r .summary "$schedule" || true)"
catalog_code=0
bash scripts/live-validation/run-attestation-catalog.sh --out "$catalog" --gateway-url "$gateway_url" \
    --expected-login "$expected_login" --expected-server "$expected_server" --magic-base "$((magic_base + 100))" \
    --lanes "$lanes" "${deriv[@]}" --arm I_UNDERSTAND_DEMO_ORDER_0.01 --cli "$cli" --binding "$binding" \
    --session-wait-max-h "${session_wait_max_h:-72}" \
    ${attestation_symbol:+--crypto-symbols} > "$catalog.log" 2>&1 || catalog_code=$?
if [ "$catalog_code" != 0 ]; then
    if [ -f "$catalog/result.json" ]; then
        fail "attestation catalog: $(python3 "$lib/attest_cases.py" failures --result "$catalog/result.json" | cut -c1-900). Rerun on this commit repeats only what did not pass."
    fi
    fail "attestation catalog did not finish: $(tail -n 1 "$catalog.log" | cut -c1-300)"
fi
log "catalog $(grep -m1 -E '^(passed|failed) runs=' "$catalog.log" | cut -c1-160)"

stage_write insights
if reuse reuseInsights; then
    log "insights kept from the earlier run"
else
    rm -rf "$ins"
    export QKT_BROKER_EXNESS_EXPECTED_ACCOUNT_LOGIN="$expected_login" QKT_BROKER_EXNESS_EXPECTED_ACCOUNT_SERVER="$expected_server"
    bash scripts/live-validation/prepare-scenario.sh --output "$ins" --id "ins_$short" --gateway-url "$gateway_url" \
        --runtime-account-identity --expected-balance "$(balance)" --expected-leverage "$expected_leverage" \
        --magic "$((magic_base + 60))" ${attestation_symbol:+--symbol "$attestation_symbol"} > "$ins.prepare.log" 2>&1 ||
        fail "insights scenario did not prepare"
    bash scripts/live-validation/run-insights-attribution.sh --scenario "$ins" --insights-image "$insights_image" \
        --cli "$cli" --arm I_UNDERSTAND_DEMO_ORDER_0.01 > "$ins.log" 2>&1 || fail "insights attribution failed: $(tail -n 1 "$ins.log")"
    ins_result="$ins/result.json"; [ -f "$ins_result" ] || ins_result="$ins/evidence/result.json"
    python3 "$lib/attest_resume.py" stage --ledger "$ledger" --name insights --evidence "$ins_result" \
        --meta "imageId=$(docker image inspect --format '{{.Id}}' "$insights_image" 2>/dev/null || true)"
fi

stage_write bundle
bundle="$bundle_root/build-$short"; rm -rf "$bundle"
run_id="$(jq -r .execution.runId "$wave/suite.json")"
catalog_evidence="$wave_root/attest-$short.catalog-evidence.json"
python3 "$lib/attest_resume.py" manifest --ledger "$ledger" --catalog "$catalog" --lanes "$lanes" --repo-root "$repo_root" \
    --run-id "$run_id" --waits "$session_waits" --waits "$catalog/session-waits.jsonl" --out "$catalog_evidence" 2> "$wave_root/assemble-$short.log" || fail "bundle refused: $(tail -n 1 "$wave_root/assemble-$short.log")"
python3 scripts/live-validation/assemble-attestation.py "$wave" "$ins" "$bundle" "$image_repository@$digest" "$sha" "$catalog_evidence" \
    >> "$wave_root/assemble-$short.log" 2>&1 || fail "bundle assembly failed; see assemble-$short.log: $(tail -n 1 "$wave_root/assemble-$short.log" | cut -c1-200)"
python3 scripts/verify-paper-soak-attestation.py "$bundle/attestation.json" --expected-git-sha "$sha" \
    --expected-image-repository "$image_repository" --cases-root "$repo_root/attestation/cases" || fail "assembled bundle does not verify"

if [ "$dispatch" = true ]; then
    stage_write dispatch
    runner_online="$(gh api "repos/{owner}/{repo}/actions/runners" --jq '[.runners[] | select(.status == "online")] | length' 2>/dev/null || echo 0)"
    [ "$runner_online" -gt 0 ] || fail "no self-hosted runner is online to verify the attestation; start it and re-run with the same bundle"
    gh workflow run paper-soak.yml --ref testing -f attestation_path="$bundle/attestation.json" >/dev/null
    # paper-soak records this runId as used: a later rerun needs a new wave, whatever else it keeps.
    python3 "$lib/attest_resume.py" spend-wave --ledger "$ledger"
fi
stage_write attested "$bundle/attestation.json"
log "attested $bundle/attestation.json"
