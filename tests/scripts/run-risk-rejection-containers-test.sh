#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "$0")/../.." && pwd)"
tmp="$(mktemp -d)"
trap 'find "$tmp" -depth -delete' EXIT

runner="$repo_root/scripts/live-validation/run-risk-rejection-containers.sh"
prepare="$repo_root/scripts/live-validation/prepare-risk-rejection-matrix.sh"
cli="$repo_root/build/install/qkt/bin/qkt"

bash -n "$runner"
bash "$runner" --help | grep -F 'five isolated QKT containers in parallel' >/dev/null
test -x "$cli"

suite="$tmp/suite"
bash "$prepare" \
    --output "$suite" \
    --id risk_runner_fixture \
    --gateway-url http://127.0.0.1:5001 \
    --expected-login 436804390 \
    --expected-server Exness-MT5Trial9 \
    --expected-balance 100000.22 \
    --expected-leverage 500 \
    --magic-base 918401 >/dev/null

bash "$runner" --suite "$suite" --cli "$cli" --verify-only > "$tmp/verified.out"
grep -F 'verified ' "$tmp/verified.out" >/dev/null

tampered="$tmp/tampered"
cp -a "$suite" "$tampered"
printf '\n# tampered\n' >> "$tampered/cases/max-quantity/qkt.config.yaml"
if bash "$runner" --suite "$tampered" --cli "$cli" --verify-only > "$tmp/tampered.out" 2>&1; then
    echo 'expected checksum rejection' >&2
    exit 1
fi
grep -F 'prepared artifact checksum verification failed' "$tmp/tampered.out" >/dev/null

remote="$tmp/remote"
cp -a "$suite" "$remote"
jq '.gatewayUrl = "https://remote.example"' "$remote/suite.json" > "$remote/suite.json.tmp"
mv "$remote/suite.json.tmp" "$remote/suite.json"
(
    cd "$remote"
    find . -type f ! -name SHA256SUMS -print0 | sort -z | xargs -0 sha256sum > SHA256SUMS
)
if bash "$runner" --suite "$remote" --cli "$cli" --verify-only > "$tmp/remote.out" 2>&1; then
    echo 'expected remote gateway rejection' >&2
    exit 1
fi
grep -F 'suite gateway must be a localhost endpoint' "$tmp/remote.out" >/dev/null

false_stateful="$tmp/false-stateful"
cp -a "$suite" "$false_stateful"
jq '.deferredStateful.status = "passed" | .claims.statefulRiskCasesPassed = true' \
    "$false_stateful/suite.json" > "$false_stateful/suite.json.tmp"
mv "$false_stateful/suite.json.tmp" "$false_stateful/suite.json"
(
    cd "$false_stateful"
    find . -type f ! -name SHA256SUMS -print0 | sort -z | xargs -0 sha256sum > SHA256SUMS
)
if bash "$runner" --suite "$false_stateful" --cli "$cli" --verify-only > "$tmp/false-stateful.out" 2>&1; then
    echo 'expected false stateful-pass rejection' >&2
    exit 1
fi
grep -F 'reviewed five-case rejection matrix' "$tmp/false-stateful.out" >/dev/null

grep -F 'containers:5,parallel:true,financiallyReadOnly:true' "$runner" >/dev/null
grep -F 'fixedIntentQty:"0.01"' "$runner" >/dev/null
grep -F 'statefulRiskCasesPassed == false' "$runner" >/dev/null
grep -F '[ "$((launch_max - launch_min))" -le 1500 ]' "$runner" >/dev/null
grep -F '[ "$((deploy_max - deploy_min))" -le 1500 ]' "$runner" >/dev/null
grep -F 'deploy "/work/strategies/${strategies[$index]}.qkt"' "$runner" >/dev/null
grep -F 'mod(NOW.minute_utc, 2) = 0' "$runner" >/dev/null
grep -F 'qktCommit' "$runner" >/dev/null
grep -F 'repository must be clean' "$runner" >/dev/null
grep -F 'Docker image is not built from' "$runner" >/dev/null
grep -F -- '--network host' "$runner" >/dev/null
grep -F 'QKT_LATENCY_TRACKING=1' "$runner" >/dev/null
grep -F 'must be unset; this run does not restrict or override the JVM' "$runner" >/dev/null
grep -F '.resourceRestrictions.memoryBytes == 0' "$runner" >/dev/null
grep -F 'broker credential was stored in' "$runner" >/dev/null
grep -F '"$cli" halt "${strategies[$halt_index]}"' "$runner" >/dev/null
jq -e '.cases[] | select(.caseId == "operator-halt" and .expectedRule == "RiskEngineHaltGate")' \
    "$suite/suite.json" >/dev/null
grep -F '.eventType == "com.qkt.events.RuleDecisionEvent"' "$runner" >/dev/null
grep -F '.eventType == "com.qkt.events.DecisionOrderLinkedEvent"' "$runner" >/dev/null
grep -F '.eventType == "com.qkt.events.RiskRejectedEvent"' "$runner" >/dev/null
grep -F '$links[0].orderId == $rejects[0].orderId' "$runner" >/dev/null
grep -F '$rejects[0].orderSchemaVersion == 1' "$runner" >/dev/null
grep -F '$rejects[0].order.qty == 0.01' "$runner" >/dev/null
grep -F '$rejects[0].order.orderType == $c.expectedOrderType' "$runner" >/dev/null
grep -F '$rejects[0].reason == $c.expectedReason.value' "$runner" >/dev/null
grep -F '$rejects[0].reason | test($c.expectedReason.value)' "$runner" >/dev/null
grep -F 'test("^(POST|PUT|PATCH|DELETE)$")' "$runner" >/dev/null
grep -F '[ "$mutations" -eq 0 ]' "$runner" >/dev/null
grep -F 'account identity or financial state changed' "$runner" >/dev/null
grep -F 'riskRejections:5' "$runner" >/dev/null
grep -F 'gatewayMutations:0' "$runner" >/dev/null
grep -F 'publicationSafe:false,containsPrivateAccountMetadata:true' "$runner" >/dev/null

if rg --quiet -- '-Xmx|-Xms|MaxRAMPercentage|MaxRAM=|--memory(=|[[:space:]])|--cpus(=|[[:space:]])|--pids-limit|--cpuset-cpus' "$runner"; then
    echo 'risk matrix runner adds a JVM or Docker resource restriction' >&2
    exit 1
fi
if rg --quiet -- '--env[ =]QKT_BROKER_API_KEY|-e[ =]QKT_BROKER_API_KEY' "$runner"; then
    echo 'risk matrix runner stores the broker credential in Docker configuration' >&2
    exit 1
fi
if rg --quiet 'bot (buy|sell|close|cancel)|kill .*--flatten|/order(["[:space:]]|$)|/close_position|/cancel_order|/modify_sl_tp' "$runner"; then
    echo 'risk matrix runner contains a direct venue mutation path' >&2
    exit 1
fi
