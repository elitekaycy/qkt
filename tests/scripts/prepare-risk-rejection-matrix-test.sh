#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "$0")/../.." && pwd)"
tmp="$(mktemp -d)"
trap 'find "$tmp" -depth -delete' EXIT

prepare="$repo_root/scripts/live-validation/prepare-risk-rejection-matrix.sh"
cli="$repo_root/build/install/qkt/bin/qkt"

bash -n "$prepare"
bash "$prepare" --help | grep -F 'five zero-mutation live risk-rejection cases' >/dev/null
test -x "$cli"

suite="$tmp/suite"
bash "$prepare" \
    --output "$suite" \
    --id risk_fixture \
    --gateway-url http://127.0.0.1:5001 \
    --expected-login 436804390 \
    --expected-server Exness-MT5Trial9 \
    --expected-balance 100000.22 \
    --expected-leverage 500 \
    --magic-base 918201 >/dev/null

(cd "$suite" && sha256sum --check SHA256SUMS >/dev/null)
jq -e '
    .schema == "qkt-live-risk-rejection-matrix-v1" and
    .gatewayUrl == "http://127.0.0.1:5001" and .credentialsStored == false and
    .contract == {containers:5,parallel:true,financiallyReadOnly:true,fixedIntentQty:"0.01",
      requiredGatewayMutations:0,requiredFills:0} and
    ([.cases[].caseId] | sort) ==
      ["far-price-collar","max-notional","max-quantity","measured-usage","operator-halt"] and
    ([.cases[].magic] | sort) == [918201,918202,918203,918204,918205] and
    all(.cases[];
      .fixedIntentQty == "0.01" and .required.riskRejections == 1 and
      .required.orderEvents == 0 and .required.fills == 0 and .required.gatewayMutations == 0
    ) and
    (.cases[] | select(.caseId == "max-quantity") |
      .expectedRule == "MaxOrderQty" and
      .expectedReason == {kind:"exact",value:"order qty 0.01 exceeds per-order cap 0.005"}) and
    (.cases[] | select(.caseId == "max-notional") | .expectedRule == "MaxOrderNotional") and
    (.cases[] | select(.caseId == "far-price-collar") |
      .expectedRule == "PriceCollar" and .expectedOrderType == "Limit") and
    (.cases[] | select(.caseId == "measured-usage") | .expectedRule == "MeasuredUsage") and
    (.cases[] | select(.caseId == "operator-halt") |
      .expectedRule == "RiskEngineHaltGate" and .operatorHaltBeforeTrigger == true and
      .expectedReason == {kind:"exact",value:"halted: operator"}) and
    .deferredStateful.status == "deferred-not-passed" and
    ([.deferredStateful.cases[].id] | sort) == ["daily-loss","drawdown","loss-streak","margin-floor"] and
    .claims.statefulRiskCasesPassed == false and .claims.productionReadiness == false
' "$suite/suite.json" >/dev/null
jq -e '
    .schema == "qkt-live-risk-stateful-deferred-v1" and .status == "deferred-not-passed" and
    ([.cases[].id] | sort) == ["daily-loss","drawdown","loss-streak","margin-floor"] and
    all(.cases[]; (.why | length) > 0 and (.requiredFixture | length) > 0)
' "$suite/stateful-deferred.json" >/dev/null

max_notional_reason_pattern="$(jq -er '.cases[] | select(.caseId == "max-notional") | .expectedReason.value' \
    "$suite/suite.json")"
jq -n -e --arg pattern "$max_notional_reason_pattern" '
    "order notional 1154.35000000 exceeds cap 1 (qty=0.01 ref=1.15435000 contractSize=100000.0 currency=USD)" |
    test($pattern)
' >/dev/null
jq -n -e --arg pattern "$max_notional_reason_pattern" '
    "order notional 1154.35000000 exceeds cap 1 (qty=0.01 ref=1.15435000 contractSize=100000 currency=USD)" |
    test($pattern)
' >/dev/null
if jq -n -e --arg pattern "$max_notional_reason_pattern" '
    "order notional 1154.35000000 exceeds cap 1 (qty=0.01 ref=1.15435000 contractSize=99999.0 currency=USD)" |
    test($pattern)
' >/dev/null; then
    echo 'max-notional reason contract accepted the wrong instrument contract size' >&2
    exit 1
fi

test "$(find "$suite/cases" -mindepth 1 -maxdepth 1 -type d | wc -l)" -eq 5
test "$(find "$suite/cases" -type f -name '*.qkt' | wc -l)" -eq 5
for strategy in "$suite"/cases/*/strategies/*.qkt; do
    "$cli" parse "$strategy" >/dev/null
    test "$(grep -Fc 'THEN BUY eur SIZING 0.01' "$strategy")" -eq 1
    grep -F 'mod(NOW.minute_utc, 2) = 0' "$strategy" >/dev/null
done
grep -F 'ORDER_TYPE = LIMIT AT 9.00000 TIF GTC' \
    "$suite/cases/far-price-collar/strategies/risk_fixture_far_price_collar.qkt" >/dev/null

grep -F 'max_order_qty: "0.005"' "$suite/cases/max-quantity/qkt.config.yaml" >/dev/null
grep -F 'max_order_notional: "1"' "$suite/cases/max-notional/qkt.config.yaml" >/dev/null
grep -F 'price_collar_pct: "1"' "$suite/cases/far-price-collar/qkt.config.yaml" >/dev/null
grep -F 'measured_usage_hours: "720"' "$suite/cases/measured-usage/qkt.config.yaml" >/dev/null
grep -F 'measured_usage_max_qty: "0.005"' "$suite/cases/measured-usage/qkt.config.yaml" >/dev/null
for config in "$suite"/cases/*/qkt.config.yaml; do
    grep -F 'gateway_url: http://127.0.0.1:5001' "$config" >/dev/null
    grep -F 'api_key: ${QKT_BROKER_API_KEY}' "$config" >/dev/null
    grep -F 'margin_floor_pct: "0"' "$config" >/dev/null
done

if rg --quiet -- '-Xmx|-Xms|MaxRAMPercentage|MaxRAM=|--memory(=|[[:space:]])|--cpus(=|[[:space:]])|--pids-limit|--cpuset-cpus' "$prepare"; then
    echo 'risk matrix preparer adds a JVM or Docker resource restriction' >&2
    exit 1
fi
if rg --text --fixed-strings 'fixture-secret' "$suite"; then
    echo 'risk matrix preparer retained a credential' >&2
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
    --magic-base 918301 > "$tmp/remote.out" 2>&1; then
    echo 'expected remote gateway rejection' >&2
    exit 1
fi
grep -F 'explicit http://127.0.0.1:PORT endpoint' "$tmp/remote.out" >/dev/null
