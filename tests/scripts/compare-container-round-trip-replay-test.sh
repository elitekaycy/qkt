#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "$0")/../.." && pwd)"
runner="$repo_root/scripts/live-validation/compare-container-round-trip-replay.sh"
tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT

bash -n "$runner"
bash "$runner" --help | grep -F 'full-ticks-paper, full-ticks-mt5, and bars-paper' >/dev/null
bash "$runner" --help | grep -F 'gateway is never contacted and no live credential is used' >/dev/null

grep -F 'qkt-live-container-round-trip-case-v1' "$runner" >/dev/null
grep -F '.strategyOwnedClose == true' "$runner" >/dev/null
grep -F '.finalPositions == 0 and .finalOrders == 0' "$runner" >/dev/null
grep -F '.audit.accepted == 2 and .audit.filled == 2 and .audit.accounted == 2' "$runner" >/dev/null
grep -F '.audit.rejected == 0' "$runner" >/dev/null
grep -F '.golden.fills == 2 and .golden.linkedPlacements == 1 and .golden.mutations == 3' "$runner" >/dev/null
grep -F 'QKT_BROKER_API_KEY=offline-replay-not-used' "$runner" >/dev/null
grep -F -- '--data-root "$output/data" --no-fetch --allow-incomplete' "$runner" >/dev/null
grep -F 'run_replay full-ticks-paper paper' "$runner" >/dev/null
grep -F 'run_replay full-ticks-mt5 mt5-sim' "$runner" >/dev/null
grep -F 'run_replay bars-paper paper --bars --bar-tf 1m' "$runner" >/dev/null
grep -F 'full-ticks paper and MT5 order journals are not byte-identical' "$runner" >/dev/null
grep -F 'timestamp-normalized bars-paper orders differ from full-tick orders' "$runner" >/dev/null
grep -F '.inputSummary.liveTicks == $source.ticks' "$runner" >/dev/null
grep -F '([.inputSummary.streamCandles[]] | add) == $source.streamCandles' "$runner" >/dev/null
grep -F '([.inputSummary.strategyCandleEvaluations[]] | add) == $source.strategyCandleEvaluations' "$runner" >/dev/null
grep -F 'indicator entry values differ from live' "$runner" >/dev/null
grep -F 'indicator exit quantity or close differs from live' "$runner" >/dev/null
grep -F '$2 + 0 >= 1' "$runner" >/dev/null
grep -F 'def norm: ((tonumber * 100000000) | round);' "$runner" >/dev/null
grep -F 'live and full-ticks-mt5 fills or PnL differ after numeric normalization' "$runner" >/dev/null
grep -F 'live request, protection, or fills differ from MT5 simulation' "$runner" >/dev/null
grep -F 'liveCanonicalEntryIntentExact:true' "$runner" >/dev/null
grep -F 'paperModelDifferences' "$runner" >/dev/null
grep -F 'barsPaperModelDifferences' "$runner" >/dev/null
grep -F 'Paper fills at the tracked price without bid/ask spread' "$runner" >/dev/null
grep -F 'sha256sum --check SHA256SUMS' "$runner" >/dev/null

[ "$(grep -c '^run_replay full-' "$runner")" -eq 2 ]
[ "$(grep -c '^run_replay bars-paper' "$runner")" -eq 1 ]
if rg --quiet 'run_replay .*tick-resolved|run_replay .*bars-mt5' "$runner"; then
    echo 'comparator invokes an unsupported replay mode' >&2
    exit 1
fi
if rg --quiet -- 'curl|wget|docker|qkt (bot|daemon|deploy|fetch)|--fetch' "$runner"; then
    echo 'comparator contains a network or live-operation command' >&2
    exit 1
fi
if rg --quiet -- '-Xmx|-Xms|MaxRAMPercentage|org.gradle.jvmargs' "$runner"; then
    echo 'comparator restricts the JVM' >&2
    exit 1
fi

scenario="$tmp/scenario"
mkdir -p "$scenario/evidence" "$scenario/logs" "$scenario/strategies/armed" "$tmp/bundle"
printf 'strategy fixture\n' >"$scenario/strategies/armed/fixture.qkt"
printf 'api_key: ${QKT_BROKER_API_KEY}\n' >"$scenario/qkt.config.yaml"
printf '{"account":{"startingBalance":"100000.00"}}\n' >"$scenario/expected.json"
printf '{"qktDirty":false,"credentialsStored":false}\n' >"$scenario/scenario.json"
printf '%s\n' \
    'bounded indicator entry side=BUY score=0.1 m1_fast=1 m1_slow=1 m5_fast=1 m5_slow=1 close=1' \
    'bounded indicator exit signed_qty=0.01 holding_seconds=60 close=1' \
    >"$scenario/logs/container-daemon.log"

cat >"$tmp/bundle/manifest.json" <<'JSON'
{
  "captureMode":"TRADING",
  "captureGitSha":"fixture",
  "counts":{
    "ticks":2,"warmupTicks":2,"candles":2,"streamCandles":2,
    "strategyCandleEvaluations":2,"fills":2,"linkedPlacements":1,"mutations":3
  },
  "entries":[]
}
JSON
(cd "$tmp/bundle" && zip -q "$scenario/evidence/golden.zip" manifest.json)
cp "$tmp/bundle/manifest.json" "$scenario/evidence/golden-manifest.json"
bundle_sha="$(sha256sum "$scenario/evidence/golden.zip" | awk '{print $1}')"
jq -n --arg sha "$bundle_sha" '
  {
    schema:"qkt-live-container-round-trip-case-v1",status:"passed",
    strategy:"fixture",strategyOwnedClose:true,finalPositions:0,finalOrders:0,
    audit:{ruleDecisions:2,decisionOrderLinks:2,accepted:2,filled:2,accounted:2,rejected:0},
    transport:{orderPosts:1,protectionPosts:1,closePosts:1,mutations:3},
    golden:{fills:2,linkedPlacements:1,mutations:3,sha256:$sha},
    timeframeEvidence:{m1StreamAndEvaluation:true,m5StreamAndEvaluation:true},
    traces:{indicatorEntry:true,indicatorExit:true}
  }
' >"$scenario/evidence/result.json"
(
    cd "$scenario"
    find . -type f ! -path './RUN-SHA256SUMS' -print0 | sort -z | xargs -0 sha256sum >RUN-SHA256SUMS
)

fake_cli="$tmp/qkt"
printf '#!/bin/sh\nexit 0\n' >"$fake_cli"
chmod +x "$fake_cli"
bash "$runner" --scenario "$scenario" --out "$tmp/out" --cli "$fake_cli" --verify-only >"$tmp/verified.out"
grep -F "verified $scenario" "$tmp/verified.out" >/dev/null

printf '# tampered\n' >>"$scenario/strategies/armed/fixture.qkt"
if bash "$runner" --scenario "$scenario" --out "$tmp/tampered-out" --cli "$fake_cli" --verify-only \
    >"$tmp/tampered.out" 2>&1; then
    echo 'expected fixture checksum rejection' >&2
    exit 1
fi
grep -F 'live-run checksum verification failed' "$tmp/tampered.out" >/dev/null
