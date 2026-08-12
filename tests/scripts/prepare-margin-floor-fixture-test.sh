#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "$0")/../.." && pwd)"
tmp="$(mktemp -d)"
trap 'find "$tmp" -depth -delete' EXIT

prepare="$repo_root/scripts/live-validation/prepare-margin-floor-fixture.sh"
cli="$repo_root/build/install/qkt/bin/qkt"

bash -n "$prepare"
bash "$prepare" --help | grep -F 'controlled live margin-floor fixture' >/dev/null
test -x "$cli"

suite="$tmp/suite"
bash "$prepare" \
    --output "$suite" \
    --id marginfloor_fixture \
    --gateway-url http://127.0.0.1:5001 \
    --expected-login 436804390 \
    --expected-server Exness-MT5Trial9 \
    --expected-balance 100000.22 \
    --expected-leverage 500 \
    --magic-base 918901 >/dev/null

(cd "$suite" && sha256sum --check SHA256SUMS >/dev/null)
jq -e '
    .schema == "qkt-live-margin-floor-fixture-v1" and
    .gatewayUrl == "http://127.0.0.1:5001" and
    .credentialsStored == false and
    .contract == {
      openerCreatesLiveExposure:true,
      probeRejectsBeforeTransport:true,
      probeAllowedAfterHeadroomRecovery:true,
      dynamicMarginFloorPct:true,
      fixedIntentQty:"0.01",
      finalVenueFlat:true,
      finalPendingOrders:false
    } and
    .opener.schema == "qkt-live-margin-floor-opener-v1" and
    .opener.strategy == "marginfloor_fixture_margin_floor_opener" and
    .opener.magic == 918901 and
    .opener.required.finalFlat == true and
    .probe.schema == "qkt-live-margin-floor-probe-v1" and
    .probe.strategy == "marginfloor_fixture_margin_floor_probe" and
    .probe.magic == 918902 and
    .probe.expectedRule == "MarginFloor" and
    .probe.expectedReason.kind == "regex" and
    (.probe.expectedReason.value | contains("— no new exposure until headroom recovers")) and
    (.probe.expectedReason.value | contains("\\u2014") | not) and
    .probe.dynamicMarginFloorSelection.floorPct == "ceil(observed_margin_level_pct) + 1" and
    .dynamicFloorSelection == {
      schema:"qkt-live-margin-floor-selection-v1",
      source:"gateway_account.margin_level",
      floorPctFormula:"ceil(observed_margin_level_pct) + 1",
      minObservedMarginLevelPct:"0.00000001",
      openerPositionRequired:true,
      finalMaterializedConfig:"probe/qkt.config.yaml"
    } and
    .claims.marginFloorPassed == false and
    .claims.productionReadiness == false
' "$suite/suite.json" >/dev/null

jq -e '
    .schema == "qkt-live-margin-floor-opener-v1" and
    .required.livePositionsObserved == 1 and
    .required.orderEventsMin == 1 and
    .required.fillsMin == 1 and
    .required.closeMutationsMin == 1 and
    .required.finalFlat == true
' "$suite/opener/expected.json" >/dev/null

jq -e '
    .schema == "qkt-live-margin-floor-probe-v1" and
    .required == {
      streamCandlesMin:1,
      evaluatedCandlesMin:1,
      ruleDecisions:1,
      decisionOrderLinks:1,
      riskRejections:1,
      preRecoveryOrderEvents:0,
      preRecoveryFills:0,
      preRecoveryGatewayMutations:0,
      recoveredOrderEventsMin:1,
      recoveredFillsMin:1,
      recoveredCloseMutationsMin:1,
      finalFlat:true
    } and
    .expectedRule == "MarginFloor" and
    .expectedReason.kind == "regex" and
    (.expectedReason.value | contains("— no new exposure until headroom recovers")) and
    (.expectedReason.value | contains("\\u2014") | not)
' "$suite/probe/expected.json" >/dev/null

test "$(find "$suite/opener" -type f -name '*.qkt' | wc -l)" -eq 1
test "$(find "$suite/probe" -type f -name '*.qkt' | wc -l)" -eq 1
for strategy in "$suite"/opener/strategies/*.qkt "$suite"/probe/strategies/*.qkt; do
    "$cli" parse "$strategy" >/dev/null
    grep -F 'EVERY 1m WARMUP 2 BARS' "$strategy" >/dev/null
    grep -F 'mod(NOW.minute_utc, 2) = 0' "$strategy" >/dev/null
    grep -F 'TRADES.today = 0' "$strategy" >/dev/null
    grep -F 'THEN BUY eur SIZING 0.01' "$strategy" >/dev/null
done

grep -F 'margin_floor_pct: "0"' "$suite/opener/qkt.config.yaml" >/dev/null
grep -F 'measured_usage_hours: "0"' "$suite/opener/qkt.config.yaml" >/dev/null
grep -F 'max_order_qty: "0.01"' "$suite/opener/qkt.config.yaml" >/dev/null
grep -F 'margin_floor_pct: "__QKT_DYNAMIC_MARGIN_FLOOR_PCT__"' "$suite/probe/qkt.config.template.yaml" >/dev/null
grep -F 'api_key: ${QKT_BROKER_API_KEY}' "$suite/opener/qkt.config.yaml" >/dev/null
grep -F 'api_key: ${QKT_BROKER_API_KEY}' "$suite/probe/qkt.config.template.yaml" >/dev/null

if rg --quiet -- '-Xmx|-Xms|MaxRAMPercentage|MaxRAM=|--memory(=|[[:space:]])|--cpus(=|[[:space:]])|--pids-limit|--cpuset-cpus' "$prepare"; then
    echo 'margin-floor fixture preparer adds a JVM or Docker resource restriction' >&2
    exit 1
fi
if rg --text --fixed-strings 'fixture-secret' "$suite"; then
    echo 'margin-floor fixture preparer retained a credential' >&2
    exit 1
fi

remote="$tmp/remote"
if bash "$prepare" \
    --output "$remote" \
    --id remote_fixture \
    --gateway-url https://remote.example \
    --expected-login 1 \
    --expected-server Demo \
    --expected-balance 1 \
    --expected-leverage 1 \
    --magic-base 919001 > "$tmp/remote.out" 2>&1; then
    echo 'expected remote gateway rejection' >&2
    exit 1
fi
grep -F 'explicit http://127.0.0.1:PORT endpoint' "$tmp/remote.out" >/dev/null
