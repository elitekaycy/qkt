#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "$0")/../.." && pwd)"
tmp="$(mktemp -d)"
trap 'find "$tmp" -depth -delete' EXIT

prepare="$repo_root/scripts/live-validation/prepare-stateful-risk-matrix.sh"
cli="$repo_root/build/install/qkt/bin/qkt"

bash -n "$prepare"
bash "$prepare" --help | grep -F 'four deterministic live stateful-risk cases' >/dev/null
test -x "$cli"

suite="$tmp/suite"
bash "$prepare" \
    --output "$suite" \
    --id stateful_fixture \
    --gateway-url http://127.0.0.1:5001 \
    --expected-login 436804390 \
    --expected-server Exness-MT5Trial9 \
    --expected-balance 100000.22 \
    --expected-leverage 500 \
    --magic-base 918601 >/dev/null

(cd "$suite" && sha256sum --check SHA256SUMS >/dev/null)
jq -e '
    .schema == "qkt-live-stateful-risk-matrix-v1" and
    .gatewayUrl == "http://127.0.0.1:5001" and .credentialsStored == false and
    .contract == {
      containers:4,parallel:true,financiallyReadOnly:true,fixedIntentQty:"0.01",
      requiredGatewayMutations:0,requiredFills:0,barsObserved:true,restoredStateTripsLiveHalts:true
    } and
    ([.cases[].caseId] | sort) ==
      ["global-daily-loss","global-drawdown","loss-streak","strategy-daily-loss"] and
    ([.cases[].magic] | sort) == [918601,918602,918603,918604] and
    (.cases[] | select(.caseId == "global-daily-loss") |
      .expectedHalt.rule == "MaxDailyLoss" and .expectedHalt.strategyId == null and
      .expectedHalt.reason == {kind:"exact",value:"daily loss 10 exceeds max 5"} and
      .expectedRejection.reason == {kind:"exact",value:"halted: daily loss 10 exceeds max 5"}) and
    (.cases[] | select(.caseId == "strategy-daily-loss") |
      .expectedHalt.rule == "MaxStrategyDailyLoss" and .expectedHalt.strategyId == "stateful_fixture_strategy_daily_loss") and
    (.cases[] | select(.caseId == "global-drawdown") |
      .expectedHalt.rule == "MaxDrawdown" and .expectedHalt.strategyId == null and
      .expectedHalt.reason.kind == "regex" and .expectedRejection.reason.kind == "regex") and
    (.cases[] | select(.caseId == "loss-streak") |
      .expectedHalt.rule == "LossStreakHalt" and .expectedHalt.strategyId == "stateful_fixture_loss_streak" and
      .expectedHalt.reason == {kind:"exact",value:"LossStreakHalt[stateful_fixture_loss_streak]: 1 consecutive losses, max 1"} and
      .expectedRejection.reason == {kind:"exact",value:"halted: LossStreakHalt[stateful_fixture_loss_streak]: 1 consecutive losses, max 1"}) and
    all(.cases[];
      .fixedIntentQty == "0.01" and
      .required == {
        streamCandlesMin:1,evaluatedCandlesMin:1,haltedEvents:1,ruleDecisions:1,
        decisionOrderLinks:1,riskRejections:1,orderEvents:0,fills:0,gatewayMutations:0
      }
    ) and
    .deferredStateful.status == "deferred-not-passed" and
    ([.deferredStateful.cases[].id] | sort) == ["margin-floor"] and
    .claims.marginFloorPassed == false and .claims.productionReadiness == false
' "$suite/suite.json" >/dev/null

jq -e '
    .schema == "qkt-live-risk-stateful-deferred-v1" and .status == "deferred-not-passed" and
    ([.cases[].id] | sort) == ["margin-floor"] and
    all(.cases[]; (.why | length) > 0 and (.requiredFixture | length) > 0)
' "$suite/stateful-deferred.json" >/dev/null

test "$(find "$suite/cases" -mindepth 1 -maxdepth 1 -type d | wc -l)" -eq 4
test "$(find "$suite/cases" -type f -name '*.qkt' | wc -l)" -eq 4
for strategy in "$suite"/cases/*/strategies/*.qkt; do
    "$cli" parse "$strategy" >/dev/null
    test "$(grep -Fc 'THEN BUY eur SIZING 0.01' "$strategy")" -eq 1
    grep -F 'EVERY 1m WARMUP 2 BARS' "$strategy" >/dev/null
    grep -F 'mod(NOW.minute_utc, 2) = 0' "$strategy" >/dev/null
done

grep -F 'max_daily_loss: "5"' "$suite/cases/global-daily-loss/qkt.config.yaml" >/dev/null
grep -F 'max_daily_loss: "0"' "$suite/cases/strategy-daily-loss/qkt.config.yaml" >/dev/null
grep -F 'max_drawdown_pct: "0.005"' "$suite/cases/global-drawdown/qkt.config.yaml" >/dev/null
grep -F 'loss_streak_halt: "1"' "$suite/cases/loss-streak/qkt.config.yaml" >/dev/null
for config in "$suite"/cases/*/qkt.config.yaml; do
    grep -F 'gateway_url: http://127.0.0.1:5001' "$config" >/dev/null
    grep -F 'api_key: ${QKT_BROKER_API_KEY}' "$config" >/dev/null
    grep -F 'margin_floor_pct: "0"' "$config" >/dev/null
    grep -F 'measured_usage_hours: "0"' "$config" >/dev/null
done

if rg --quiet -- '-Xmx|-Xms|MaxRAMPercentage|MaxRAM=|--memory(=|[[:space:]])|--cpus(=|[[:space:]])|--pids-limit|--cpuset-cpus' "$prepare"; then
    echo 'stateful matrix preparer adds a JVM or Docker resource restriction' >&2
    exit 1
fi
if rg --text --fixed-strings 'fixture-secret' "$suite"; then
    echo 'stateful matrix preparer retained a credential' >&2
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
    --magic-base 918701 > "$tmp/remote.out" 2>&1; then
    echo 'expected remote gateway rejection' >&2
    exit 1
fi
grep -F 'explicit http://127.0.0.1:PORT endpoint' "$tmp/remote.out" >/dev/null
