#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"

usage() {
    cat <<'EOF'
Usage: prepare-readonly-catalog.sh --output DIR --id ID --gateway-url URL \
  --expected-login N --expected-server NAME --expected-balance DECIMAL \
  --expected-leverage N [--cli PATH]

Prepares four financially read-only live catalog cases: numeric/candle functions,
cross-symbol plus multi-timeframe, session/history, and volume-capability-negative.
No gateway request is made and no credential is accepted or retained.
EOF
}

fail() {
    printf 'prepare-readonly-catalog: %s\n' "$1" >&2
    exit 1
}

output=""
suite_id=""
gateway_url=""
expected_login=""
expected_server=""
expected_balance=""
expected_leverage=""
cli="$repo_root/build/install/qkt/bin/qkt"

while [ "$#" -gt 0 ]; do
    case "$1" in
        --output) output="${2:-}"; shift 2 ;;
        --id) suite_id="${2:-}"; shift 2 ;;
        --gateway-url) gateway_url="${2:-}"; shift 2 ;;
        --expected-login) expected_login="${2:-}"; shift 2 ;;
        --expected-server) expected_server="${2:-}"; shift 2 ;;
        --expected-balance) expected_balance="${2:-}"; shift 2 ;;
        --expected-leverage) expected_leverage="${2:-}"; shift 2 ;;
        --cli) cli="${2:-}"; shift 2 ;;
        --help|-h) usage; exit 0 ;;
        *) fail "unknown argument: $1" ;;
    esac
done

[ -n "$output" ] || fail "--output is required"
[[ "$suite_id" =~ ^[a-z][a-z0-9_]{2,47}$ ]] || fail "--id must be a lowercase DSL identifier"
[[ "$gateway_url" =~ ^http://127\.0\.0\.1:[0-9]{1,5}/?$ ]] ||
    fail "--gateway-url must be an explicit http://127.0.0.1:PORT endpoint"
gateway_url="${gateway_url%/}"
[[ "$expected_login" =~ ^[1-9][0-9]*$ ]] || fail "--expected-login must be a positive integer"
[[ "$expected_server" =~ ^[A-Za-z0-9._-]+$ ]] || fail "--expected-server contains unsupported characters"
[[ "$expected_balance" =~ ^[0-9]+([.][0-9]+)?$ ]] || fail "--expected-balance must be a decimal"
[[ "$expected_leverage" =~ ^[1-9][0-9]*$ ]] || fail "--expected-leverage must be a positive integer"
[ -x "$cli" ] || fail "QKT CLI is not executable: $cli"
[ ! -e "$output" ] || fail "output already exists: $output"

output="$(realpath -m "$output")"
git_sha="$(git -C "$repo_root" rev-parse HEAD)"
mkdir -m 700 -p "$output/cases"

write_config() {
    local case_dir="$1"
    local strategy="$2"
    local magic="$3"
    mkdir -m 700 -p "$case_dir/strategies/control" "$case_dir/strategies/negative" \
        "$case_dir/state" "$case_dir/data" "$case_dir/logs" "$case_dir/evidence"
    cat > "$case_dir/qkt.config.yaml" <<EOF
source: local
data_root: "/work/data"
starting_balance: "$expected_balance"
log_level: info
runtime:
  mode: dev
account:
  currency: USD
brokers:
  exness:
    type: mt5
    extends: exness
    gateway_url: $gateway_url
    api_key: \${QKT_BROKER_API_KEY}
    magic: $magic
    server_time_zone: Etc/UTC
    expected_account_login: $expected_login
    expected_account_server: $expected_server
    expected_trade_mode: demo
    expected_account_currency: USD
    tick_poll_interval_ms: 500
    poll_interval_ms: 5000
    http_timeout_ms: 5000
    retry_attempts: 3
risk:
  max_daily_loss: "1"
  max_order_qty: "0.01"
  max_order_notional: "1"
  price_collar_pct: "0.01"
  margin_floor_pct: "1000"
  measured_usage_hours: "720"
  measured_usage_max_qty: "0.01"
  max_round_trips_10m: 1
  max_broker_rejections_1m: 1
  max_drawdown_pct: "0.01"
  max_daily_drawdown_pct: "0.01"
  live_equity_basis: venue
  per_strategy:
    $strategy:
      max_daily_loss: "1"
      max_position_size: "0.01"
      max_open_positions: 1
      max_trades_per_day: 1
book_risk:
  capital: "$expected_balance"
  limits:
    max_gross_exposure: "0.01"
    max_net_exposure: "0.01"
    max_symbol_concentration: "0.01"
  allocation:
    method: FIXED
    max_leverage: "1"
state:
  enabled: true
  async: true
insights:
  enabled: false
EOF
}

numeric_id="${suite_id}_numeric_candle"
numeric_dir="$output/cases/numeric-candle"
write_config "$numeric_dir" "$numeric_id" 918101
cat > "$numeric_dir/strategies/control/$numeric_id.qkt" <<EOF
STRATEGY $numeric_id VERSION 1

SYMBOLS
    eur1 = EXNESS:EURUSD EVERY 1m WARMUP 40 BARS,
    eur5 = EXNESS:EURUSD EVERY 5m WARMUP 40 BARS

LET ema3 = ema(eur1.close, 3), sma3 = sma(eur1.close, 3), wma3 = wma(eur1.close, 3),
    dema3 = dema(eur1.close, 3), tema3 = tema(eur1.close, 3), hma4 = hma(eur1.close, 4),
    rsi3 = rsi(eur1.close, 3), sd3 = stddev(eur1.close, 3), var3 = variance(eur1.close, 3),
    vr = variance_ratio(eur1.close, 2, 6), z = zscore(eur1.close, 3),
    slope = regression_slope(eur1.close, 3), percentile_v = percentile_rank(eur1.close, 3),
    skew4 = skew(eur1.close, 4), efficiency = er(eur1.close, 3), prior = lag(eur1.close, 1),
    run_length_v = runlength(eur1.close), up_run_length_v = runlength_where(eur1.close > prior),
    atr3 = atr(eur5.candle, 3), willr = williams_r(eur5.candle, 3), cci3 = cci(eur5.candle, 3),
    stochk = stoch_k(eur5.candle, 3, 2), stochd = stoch_d(eur5.candle, 3, 2),
    kupper = keltner_upper(eur5.candle, 3, 2), kmiddle = keltner_middle(eur5.candle, 3, 2),
    klower = keltner_lower(eur5.candle, 3, 2), plusdi = plus_di(eur5.candle, 3),
    minusdi = minus_di(eur5.candle, 3), adx3 = adx(eur5.candle, 3),
    macdline = macd(eur1.close, 2, 4, 2), macdsignal = macd_signal(eur1.close, 2, 4, 2),
    macdhist = macd_hist(eur1.close, 2, 4, 2), bbu = bollinger_upper(eur1.close, 3, 2),
    bbm = bollinger_middle(eur1.close, 3, 2), bbl = bollinger_lower(eur1.close, 3, 2),
    priorhigh = highest(eur1.close, 3), priorlow = lowest(eur1.close, 3)

RULES
    WHEN macdhist IS NOT NULL AND vr IS NOT NULL AND skew4 IS NOT NULL
    THEN LOG "catalog vector case=numeric-candle group=numeric ema={ema3} sma={sma3} wma={wma3} dema={dema3} tema={tema3} hma={hma4} rsi={rsi3} stddev={sd3} variance={var3} variance_ratio={vr} zscore={z} regression_slope={slope} percentile_rank={percentile_value} skew={skew4} er={efficiency} lag={prior} runlength={run_length} runlength_where={up_run_length} macd={macdline} macd_signal={macdsignal} macd_hist={macdhist} bollinger_upper={bbu} bollinger_middle={bbm} bollinger_lower={bbl} highest={priorhigh} lowest={priorlow}"
         ema3=ema3 sma3=sma3 wma3=wma3 dema3=dema3 tema3=tema3 hma4=hma4 rsi3=rsi3 sd3=sd3 var3=var3 vr=vr z=z slope=slope percentile_value=percentile_v skew4=skew4 efficiency=efficiency prior=prior run_length=run_length_v up_run_length=up_run_length_v macdline=macdline macdsignal=macdsignal macdhist=macdhist bbu=bbu bbm=bbm bbl=bbl priorhigh=priorhigh priorlow=priorlow

    WHEN adx3 IS NOT NULL AND stochd IS NOT NULL
    THEN LOG "catalog vector case=numeric-candle group=candle atr={atr3} williams_r={willr} cci={cci3} stoch_k={stochk} stoch_d={stochd} keltner_upper={kupper} keltner_middle={kmiddle} keltner_lower={klower} plus_di={plusdi} minus_di={minusdi} adx={adx3}"
         atr3=atr3 willr=willr cci3=cci3 stochk=stochk stochd=stochd kupper=kupper kmiddle=kmiddle klower=klower plusdi=plusdi minusdi=minusdi adx3=adx3

    WHEN eur1.close IS NOT NULL
    THEN LOG "catalog vector case=numeric-candle group=math abs={abs_v} sqrt={sqrt_v} log={log_v} exp={exp_v} pow={pow_v} floor={floor_v} ceil={ceil_v} round={round_v} mod={mod_v} round_to={round_to_v} min={min_v} max={max_v} case_value={case_value}"
         abs_v=abs(-eur1.close) sqrt_v=sqrt(eur1.close) log_v=log(eur1.close) exp_v=exp(1) pow_v=pow(eur1.close,2) floor_v=floor(eur1.close) ceil_v=ceil(eur1.close) round_v=round(eur1.close) mod_v=mod(eur1.close,0.005) round_to_v=round_to(eur1.close,0.005) min_v=min(eur1.open,eur1.close) max_v=max(eur1.open,eur1.close) case_value=CASE WHEN eur1.close >= eur1.open THEN 1 ELSE -1 END

EOF

cross_id="${suite_id}_cross_multi_tf"
cross_dir="$output/cases/cross-multi-tf"
write_config "$cross_dir" "$cross_id" 918102
cat > "$cross_dir/strategies/control/$cross_id.qkt" <<EOF
STRATEGY $cross_id VERSION 1

SYMBOLS
    eur1 = EXNESS:EURUSD EVERY 1m WARMUP 40 BARS,
    eur5 = EXNESS:EURUSD EVERY 5m WARMUP 40 BARS,
    gbp1 = EXNESS:GBPUSD EVERY 1m WARMUP 40 BARS,
    gbp5 = EXNESS:GBPUSD EVERY 5m WARMUP 40 BARS

LET corr1 = correlation(eur1.close, gbp1.close, 5), beta1 = beta(eur1.close, gbp1.close, 5),
    corr5 = correlation(eur5.close, gbp5.close, 5), beta5 = beta(eur5.close, gbp5.close, 5),
    residual = resid(eur1.close, gbp1.close, 8),
    confirmations = confirm_ratio(eur1.close, gbp1.close, -eur5.close, -gbp5.close, 3),
    rank = rank_of(eur1.close, gbp1.close), normalized = normalize(eur1.close, eur1.close, gbp1.close),
    softmax_v = softmax(eur1.close, gbp1.close)

RULES
    WHEN corr1 IS NOT NULL AND corr5 IS NOT NULL AND residual IS NOT NULL AND confirmations IS NOT NULL
    THEN LOG "catalog vector case=cross-multi-tf group=cross correlation_m1={corr1} beta_m1={beta1} correlation_m5={corr5} beta_m5={beta5} resid={residual} confirm_ratio={confirmations} rank_of={rank} normalize={normalized} softmax={softmax_value} eur1={eur1_close} eur5={eur5_close} gbp1={gbp1_close} gbp5={gbp5_close}"
         corr1=corr1 beta1=beta1 corr5=corr5 beta5=beta5 residual=residual confirmations=confirmations rank=rank normalized=normalized softmax_value=softmax_v eur1_close=eur1.close eur5_close=eur5.close gbp1_close=gbp1.close gbp5_close=gbp5.close
EOF

session_id="${suite_id}_session_history"
session_dir="$output/cases/session-history"
write_config "$session_dir" "$session_id" 918103
cat > "$session_dir/strategies/control/$session_id.qkt" <<EOF
STRATEGY $session_id VERSION 1

SYMBOLS
    eur = EXNESS:EURUSD EVERY 1m WARMUP 5000 BARS

LET session_high = session_range_high(eur.candle, 0, 0, 1, 0),
    session_low = session_range_low(eur.candle, 0, 0, 1, 0),
    pivot = pivot_p(eur.candle), pivot_r = pivot_r1(eur.candle), pivot_s = pivot_s1(eur.candle),
    seasonal = seasonal_range(eur.candle, 2), seasonal_sd = seasonal_range_stdev(eur.candle, 2),
    momentum = session_momentum(eur.candle, 0, 1, 1), anchored = anchored_return(eur.candle, 30),
    gap = reopen_gap(eur.candle, 1), gap_origin = reopen_gap_origin(eur.candle, 1),
    gap_fill = gap_fill_fraction(eur.candle, 1), failed_high = failed_break_high(eur.candle, 5, 2, 3),
    failed_low = failed_break_low(eur.candle, 5, 2, 3), ib_high = ib_defended_high(eur.candle, 0, 60),
    ib_low = ib_defended_low(eur.candle, 0, 60)

RULES
    WHEN session_high IS NOT NULL AND pivot IS NOT NULL AND seasonal IS NOT NULL AND momentum IS NOT NULL
    THEN LOG "catalog vector case=session-history group=history session_range_high={session_high} session_range_low={session_low} pivot_p={pivot} pivot_r1={pivot_r} pivot_s1={pivot_s} seasonal_range={seasonal} seasonal_range_stdev={seasonal_sd} session_momentum={momentum}"
         session_high=session_high session_low=session_low pivot=pivot pivot_r=pivot_r pivot_s=pivot_s seasonal=seasonal seasonal_sd=seasonal_sd momentum=momentum

    WHEN anchored IS NOT NULL
    THEN LOG "catalog vector case=session-history group=stateful anchored_return={anchored} reopen_gap={gap} reopen_gap_origin={gap_origin} gap_fill_fraction={gap_fill} failed_break_high={failed_high} failed_break_low={failed_low} ib_defended_high={ib_high} ib_defended_low={ib_low}"
         anchored=anchored gap=gap gap_origin=gap_origin gap_fill=gap_fill failed_high=failed_high failed_low=failed_low ib_high=ib_high ib_low=ib_low

    WHEN eur.close IS NOT NULL
    THEN LOG "catalog vector case=session-history group=clock session_window={session_open} calendar_window={calendar_open} hour_utc={hour_utc} minute_utc={minute_utc} day={current_day} days_in_month={month_days}"
         session_open=session_window(0,0,23,59) calendar_open=calendar_window(1,1,12,31) hour_utc=NOW.hour_utc minute_utc=NOW.minute_utc current_day=NOW.day month_days=NOW.days_in_month
EOF

volume_id="${suite_id}_volume_control"
volume_dir="$output/cases/volume-negative"
write_config "$volume_dir" "$volume_id" 918104
cat > "$volume_dir/strategies/control/$volume_id.qkt" <<EOF
STRATEGY $volume_id VERSION 1

SYMBOLS
    eur = EXNESS:EURUSD EVERY 1m WARMUP 10 BARS

LET baseline = ema(eur.close, 3)

RULES
    WHEN baseline IS NOT NULL
    THEN LOG "catalog vector case=volume-negative group=control ema={baseline} close={closing}"
         baseline=baseline closing=eur.close
EOF

negative_id="${suite_id}_volume_requires_data"
cat > "$volume_dir/strategies/negative/$negative_id.qkt" <<EOF
STRATEGY $negative_id VERSION 1

SYMBOLS
    eur = EXNESS:EURUSD EVERY 1m WARMUP 10 BARS

LET tick_vwap = vwap(eur.tick, 3), candle_obv = obv(eur.candle),
    session_vwap = vwap_session(eur.candle, 0), session_vwap_sd = vwap_session_stdev(eur.candle, 0)

RULES
    WHEN tick_vwap IS NOT NULL AND candle_obv IS NOT NULL AND session_vwap IS NOT NULL AND session_vwap_sd IS NOT NULL
    THEN LOG "unexpected volume vector vwap={tick_vwap} obv={candle_obv} session_vwap={session_vwap} session_vwap_stdev={session_vwap_sd}"
         tick_vwap=tick_vwap candle_obv=candle_obv session_vwap=session_vwap session_vwap_sd=session_vwap_sd
EOF

for strategy_file in "$output"/cases/*/strategies/*/*.qkt; do
    [ "$("$cli" parse "$strategy_file" 2>&1)" = "ok" ] || fail "generated strategy does not parse: $strategy_file"
done

jq -n \
    --arg suiteId "$suite_id" \
    --arg createdAt "$(date -u +%Y-%m-%dT%H:%M:%SZ)" \
    --arg qktCommit "$git_sha" \
    --arg gatewayUrl "$gateway_url" \
    --argjson login "$expected_login" \
    --arg server "$expected_server" \
    --arg balance "$expected_balance" \
    --argjson leverage "$expected_leverage" '
    {
      schema:"qkt-live-readonly-catalog-suite-v1",suiteId:$suiteId,createdAt:$createdAt,
      qktCommit:$qktCommit,gatewayUrl:$gatewayUrl,credentialsStored:false,
      account:{login:$login,server:$server,tradeMode:"demo",currency:"USD",balance:$balance,leverage:$leverage},
      contract:{containers:4,parallel:true,financiallyReadOnly:true,requiredGatewayMutations:0,
        requiredOrderEvents:0,requiredFills:0,barsFirstClass:true,
        polling:{tickPollIntervalMs:500,brokerPollIntervalMs:5000,parallelTickSymbols:5}},
      cases:[
        {id:"numeric-candle",strategy:($suiteId+"_numeric_candle"),magic:918101,
          symbols:["EXNESS:EURUSD"],streams:[{alias:"eur1",symbol:"EXNESS:EURUSD",timeframe:"1m",warmupBars:40},{alias:"eur5",symbol:"EXNESS:EURUSD",timeframe:"5m",warmupBars:40}],
          vectors:["group=numeric","group=candle","group=math"],expectedDeployment:"running"},
        {id:"cross-multi-tf",strategy:($suiteId+"_cross_multi_tf"),magic:918102,
          symbols:["EXNESS:EURUSD","EXNESS:GBPUSD"],streams:[{alias:"eur1",symbol:"EXNESS:EURUSD",timeframe:"1m",warmupBars:40},{alias:"eur5",symbol:"EXNESS:EURUSD",timeframe:"5m",warmupBars:40},{alias:"gbp1",symbol:"EXNESS:GBPUSD",timeframe:"1m",warmupBars:40},{alias:"gbp5",symbol:"EXNESS:GBPUSD",timeframe:"5m",warmupBars:40}],
          vectors:["group=cross"],expectedDeployment:"running"},
        {id:"session-history",strategy:($suiteId+"_session_history"),magic:918103,
          symbols:["EXNESS:EURUSD"],streams:[{alias:"eur",symbol:"EXNESS:EURUSD",timeframe:"1m",warmupBars:5000}],
          vectors:["group=history","group=stateful","group=clock"],expectedDeployment:"running"},
        {id:"volume-negative",strategy:($suiteId+"_volume_control"),negativeStrategy:($suiteId+"_volume_requires_data"),magic:918104,
          symbols:["EXNESS:EURUSD"],streams:[{alias:"eur",symbol:"EXNESS:EURUSD",timeframe:"1m",warmupBars:10}],
          vectors:["group=control"],expectedDeployment:"running",negativeDeployment:"volume-capability-rejected"}
      ]
    }
' > "$output/suite.json"

(
    cd "$output"
    find . -type f ! -path './SHA256SUMS' -print0 | sort -z | xargs -0 sha256sum > SHA256SUMS
    sha256sum --check SHA256SUMS >/dev/null
)

printf '%s\n' "$output"
