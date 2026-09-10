# Intraday FVG And Liquidity-Sweep Edge Study

Date: 2026-07-26

Reproducibility manifest:

- `docs/research/2026-07-26-intraday-fvg-sweep-reproducibility.md`

Follow-up platform spec:

- `docs/superpowers/specs/2026-07-26-forge-fvg-sweep-faithful-gateability-design.md`

## Question

Test whether the last/first 10 minutes of each hour and major session handoffs
produce a repeatable, tradeable edge from:

- liquidity sweeps that reject back inside the prior local range;
- three-candle fair-value gaps with a 50% retest entry;
- high reward/risk targets including 1:5, 1:20, and 1:50;
- lower-timeframe execution, especially 1m, with higher-timeframe context left
  for later strategy authoring.

## Data Used

Authoritative data was read on `bot2` from qkt-forge:

- tick cache: `/root/projects/qkt-forge/run/data/symbols/<SYMBOL>/*.csv.gz`
- bar cache: `/root/projects/qkt-forge/run/data/_bars`

Measured symbols:

- `EURUSD`
- `XAUUSD`
- `XAGUSD`
- `COPPERCMDUSD`
- `LIGHTCMDUSD`

5m OHLC coverage used:

- `2018-01-01..2021-07-02` for all five symbols.

1m OHLC was derived from cached bid/ask ticks:

- `EURUSD`, `2018-01-01..2018-12-31`, 373,448 one-minute rows.
- `XAUUSD`, `2018-01-01..2018-12-31`, 353,926 one-minute rows.
- Additional 1m structural/ATR + low-cost screens were run for `EURUSD` and
  `XAUUSD` in yearly chunks for `2019`, `2020`, and `2021-01-01..2021-07-02`.

## Measurement Rules

Script:

- local: `scripts/research_forge_intraday_edge.py`
- copied to bot2: `/root/projects/qkt-forge/scripts/research_forge_intraday_edge.py`

Windows:

- `hour_edge`: minutes `00,05,50,55`.
- `session_edge`: the same first/last 10-minute pattern around common UTC
  session handoff hours.
- `liquid_control`: non-hour-edge bars during liquid weekday overlap hours.

Patterns:

- `sweep_fade`: current bar breaks the prior 12-bar high/low and closes back
  inside; direction fades the sweep.
- `fvg_retest_cont`: three-candle imbalance with displacement; direction follows
  the gap after a 50% retest.

Execution model:

- wait up to 6 bars for retest entry;
- hold up to 36 bars after entry;
- test RR values `1, 2, 3, 5, 10, 20, 50`;
- stop/target same-bar ambiguity is pessimistic: stop wins first;
- round-trip cost is approximated from observed entry and exit bar spreads,
  expressed as R.

Stop models:

- `micro`: initial tight wick/FVG stop.
- `atr`: entry ± 1.5 ATR.
- `structure`: outside event candle/range structure with a 0.5 ATR buffer.

Cost filter:

- `--max-entry-cost-r 0.10` stands down when estimated entry-time round-trip
  spread exceeds 0.10R.

## Evidence

Bot2 artifacts:

- `run/research/intraday-edge-5m-eurusd/intraday_edge_summary.csv`
- `run/research/intraday-edge-5m-XAUUSD/intraday_edge_summary.csv`
- `run/research/intraday-edge-5m-XAGUSD/intraday_edge_summary.csv`
- `run/research/intraday-edge-5m-COPPERCMDUSD/intraday_edge_summary.csv`
- `run/research/intraday-edge-5m-LIGHTCMDUSD/intraday_edge_summary.csv`
- `run/research/intraday-edge-1m-2018-EURUSD/intraday_edge_summary.csv`
- `run/research/intraday-edge-1m-2018-XAUUSD/intraday_edge_summary.csv`
- `run/research/intraday-edge-5m-structural/intraday_edge_summary.csv`
- `run/research/intraday-edge-1m-2018-structural/intraday_edge_summary.csv`
- `run/research/intraday-edge-5m-structural-costfilter/intraday_edge_summary.csv`
- `run/research/intraday-edge-1m-2018-structural-costfilter/intraday_edge_summary.csv`
- `run/research/intraday-edge-1m-eurusd-costfilter-2019-01-01-2019-12-31/intraday_edge_summary.csv`
- `run/research/intraday-edge-1m-eurusd-costfilter-2020-01-01-2020-12-31/intraday_edge_summary.csv`
- `run/research/intraday-edge-1m-eurusd-costfilter-2021-01-01-2021-07-02/intraday_edge_summary.csv`
- `run/research/intraday-edge-1m-eurusd-xau-costfilter-2019/intraday_edge_summary.csv`
- `run/research/intraday-edge-1m-eurusd-xau-costfilter-2020/intraday_edge_summary.csv`
- `run/research/intraday-edge-1m-eurusd-xau-costfilter-2021h1/intraday_edge_summary.csv`
- `run/research/intraday-edge-1m-others-2018-structural/intraday_edge_summary.csv`
- `run/research/intraday-edge-1m-others-2018-structural-costfilter/intraday_edge_summary.csv`
- `run/research/intraday-edge-aggregate/eurusd_1m_costfilter_yearly_combined.csv`
- `run/research/intraday-edge-aggregate/eurusd_1m_costfilter_positive_key_summary.csv`
- `run/research/intraday-edge-correlations/window_correlations.csv`

Across 728 tested symbol/pattern/window/side/RR rows with at least 30 entries,
there were no positive-expectancy rows after cost.

Best observed rows:

| timeframe | symbol | pattern | window | side | RR | entries | expectancy R | win rate | PF | median cost R |
|---|---:|---|---|---|---:|---:|---:|---:|---:|---:|
| 5m | EURUSD | sweep fade | liquid control | short | 50 | 4038 | -0.279 | 0.075 | 0.797 | 0.421 |
| 5m | XAUUSD | FVG retest | liquid control | short | 2 | 1318 | -0.968 | 0.405 | 0.250 | 1.248 |
| 5m | XAGUSD | FVG retest | session edge | short | 1 | 927 | -1.505 | 0.025 | 0.003 | 1.726 |
| 5m | COPPERCMDUSD | FVG retest | liquid control | long | 1 | 1088 | -1.531 | 0.017 | 0.001 | 1.721 |
| 5m | LIGHTCMDUSD | FVG retest | liquid control | long | 1 | 1261 | -1.212 | 0.066 | 0.011 | 1.527 |
| 1m 2018 | EURUSD | sweep fade | liquid control | short | 50 | 6731 | -0.663 | 0.076 | 0.617 | 0.811 |
| 1m 2018 | XAUUSD | FVG retest | liquid control | short | 1 | 2353 | -1.368 | 0.044 | 0.005 | 1.609 |

The hour/session edge windows did not beat the control windows in a way that
would support real-money strategy authoring. The least-negative rows generally
came from `liquid_control`, not from the hour/session windows under test.

## Wider Stops And Spread/R Stand-Down

The initial tight-stop result could have been an execution-geometry failure
rather than a signal failure. Wider stops and a spread/R stand-down were tested.

5m structural/ATR stops without spread/R filtering:

- Only two positive rows with at least 30 entries appeared.
- Both were `EURUSD` FVG continuation in `liquid_control`, not the hour/session
  thesis:
  - ATR stop, long, RR 1: expectancy `+0.0267R`, PF `1.056`, 1,136 entries,
    positive in 3 of 4 years.
  - ATR stop, short, RR 1: expectancy `+0.0121R`, PF `1.025`, 1,163 entries,
    positive in 3 of 4 years.
- Best hour/session rows remained negative. Example: `EURUSD` FVG long,
  session edge, ATR stop, RR 1: `-0.0679R`, PF `0.869`.

1m 2018 structural/ATR stops without spread/R filtering:

- No positive rows with at least 30 entries.
- Best EURUSD row was sweep fade short, `liquid_control`, ATR stop, RR 20:
  `-0.0966R`, PF `0.885`.
- Best XAUUSD row remained deeply negative: FVG short, `liquid_control`,
  structural stop, RR 1: `-1.000R`, PF `0.071`.
- Additional 1m checks for `XAGUSD`, `COPPERCMDUSD`, and `LIGHTCMDUSD` also
  found no positive rows across 672 rows with at least 30 entries. The best
  overall row was `LIGHTCMDUSD`, FVG retest, structural stop, `liquid_control`,
  short, RR 20/50: `-1.520R`, PF `0.177`, with median cost `1.667R`. The best
  target-window row was also negative: `LIGHTCMDUSD` sweep-fade,
  structural stop, `session_edge`, short, RR 5: `-1.602R`, PF `0.166`,
  median cost `1.731R`.

5m structural/ATR stops with `max_entry_cost_r <= 0.10`:

| timeframe | symbol | pattern | stop | window | side | RR | entries | expectancy R | PF | positive years |
|---|---|---|---|---|---|---:|---:|---:|---:|---:|
| 5m | EURUSD | sweep fade | structure | session edge | long | 1 | 120 | +0.131 | 1.309 | 3/4 |
| 5m | EURUSD | sweep fade | structure | hour edge | long | 1 | 189 | +0.099 | 1.223 | 3/4 |
| 5m | EURUSD | sweep fade | structure | liquid control | long | 1 | 325 | +0.090 | 1.200 | 4/4 |
| 5m | EURUSD | sweep fade | structure | session edge | short | 1 | 92 | +0.070 | 1.153 | 3/4 |
| 5m | EURUSD | FVG retest | ATR | session edge | short | 1 | 342 | +0.057 | 1.123 | 2/4 |

These are not gate-ready. They are weak but worth a second-pass hypothesis
because the hour/session window finally appears after filtering, especially
EURUSD sweep-fade with structural stop and RR 1. The problem is that 1m does
not yet confirm it at sufficient sample size.

1m EURUSD yearly chunks with `max_entry_cost_r <= 0.10`:

- No hour/session row with at least 30 entries was positive in at least two
  yearly chunks.
- Best target-window rows were isolated and weak:
  - 2018: best hour/session row was `EURUSD` FVG retest, structural stop,
    hour edge, short, RR 1: `-0.0837R`, PF `0.832`, 33 entries.
  - 2020: best hour/session row was `EURUSD` sweep fade, ATR stop, session
    edge, short, RR 5: `+0.0145R`, PF `1.019`, 34 entries.
  - 2019 and 2021-H1 had no hour/session rows with at least 30 entries under
    the low-cost filter.
- The most repeatable positive key was:
  - `EURUSD`, FVG retest, structural stop, `liquid_control`, short, RR 3.
  - Positive in 3 of 4 yearly chunks.
  - Combined entries across eligible chunks: 917.
  - Mean expectancy across chunks: `+0.029R`.
  - Worst chunk expectancy: `-0.142R`.
  - Mean PF: `1.072`.
- This is not the requested hour/session edge. It suggests a possible EURUSD
  low-cost FVG-short research branch, but only as a raw candidate requiring
  combined-year out-of-sample validation and qkt tick-fill verification.
- Hour/session-positive 1m rows were not repeatable across yearly chunks and
  are not reliable enough to author as strategies.

1m `XAGUSD`, `COPPERCMDUSD`, and `LIGHTCMDUSD` with
`max_entry_cost_r <= 0.10`:

- No rows had even one qualifying entry in the 2018 structural/ATR pass.
- This means the realistic entry-cost stand-down excludes those 1m setups
  entirely under the tested wick/FVG retest geometry. The unfiltered run above
  confirms why: median cost was typically greater than `1.6R`, so the setup is
  structurally too expensive before direction quality is considered.

## Cross-Symbol Correlations

5m return correlations over aligned `2018-01-01..2021-07-02` bars:

| window | bars | XAU/XAG | XAU/EURUSD | XAU/Copper | XAU/Oil | EURUSD→next XAU | XAG→next XAU |
|---|---:|---:|---:|---:|---:|---:|---:|
| all | 245,238 | 0.710 | 0.354 | 0.191 | 0.046 | -0.002 | -0.018 |
| hour edge | 81,731 | 0.707 | 0.353 | 0.160 | 0.030 | 0.005 | -0.007 |
| session edge | 42,487 | 0.706 | 0.344 | 0.137 | 0.012 | 0.014 | 0.001 |
| liquid control | 72,287 | 0.733 | 0.362 | 0.221 | 0.064 | -0.014 | -0.031 |

Interpretation:

- XAU/XAG are strongly contemporaneously related.
- EURUSD has moderate same-bar relation to XAUUSD.
- Simple one-bar lead from EURUSD or XAGUSD into XAUUSD is near zero. Any
  cross-symbol gate must be more specific than naive lead/lag correlation.

## qkt Encoding And Gate-Readiness Check

Candidate strategies were drafted on `bot2` under
`/root/projects/qkt-forge/strategies/research/` to test whether the surviving
research rows could be honestly sent through qkt-forge gates.

Faithful candidate encodings:

- `eurusd_low_cost_hour_session_sweep_fade_v1.qkt`
- `eurusd_low_cost_fvg_short_liquid_control_v1.qkt`
- `eurusd_atr_proxy_hour_session_sweep_fade_v2.qkt`
- `eurusd_atr_proxy_fvg_short_liquid_control_v2.qkt`

These are not gate-ready:

- qkt bar screens do not expose `stream.spread`; spread/R stand-down conditions
  using `eur.spread` parse but never fire as intended.
- qkt can backtest dynamic `LIMIT AT` entry prices, including high/low and
  indicator-derived levels.
- qkt cannot currently backtest dynamic bracket exit trigger expressions:
  `STOP_LOSS AT eur.low...`, `STOP_LOSS AT eur.close...`, and
  `STOP_LOSS BY atr(...)` all fail at runtime with `unsupported trigger
  expression`.
- Because the only weak positive rows depended on ATR/structure stops and
  spread/R filtering, the faithful strategy cannot currently be measured
  through qkt-forge gates without changing either the DSL/runtime capability or
  the trading rule.

Static-stop pending-order proxies:

- `eurusd_static_stop_hour_session_sweep_fade_v3.qkt`
- `eurusd_static_stop_fvg_short_liquid_control_v3.qkt`

These are also not valid gate evidence:

- The static-stop session sweep proxy ran, but produced 2,398 trades over
  `2018-01-01..2021-07-02`, while the research harness measured only 120 long
  and 92 short qualifying entries for the comparable 5m session-edge screen.
- A Q1 2018 check produced 338 qkt trades versus 16 long and 17 short harness
  entries. `COOLDOWN.remaining_s = 0` did not prevent repeated pending-order
  stacking.
- The full 1m static-stop pending-order FVG proxy was stopped after several
  minutes because it was CPU-bound and still producing repeated pending-order
  churn rather than comparable event-entry behavior.

Market-retest proxies:

- `eurusd_market_retest_hour_session_sweep_fade_v4.qkt`
- `eurusd_market_retest_fvg_short_liquid_control_v4.qkt`

These avoid pending-order stacking by waiting for the retest to print and then
entering at market. They are explicitly not faithful to the passive 50% retest
fill, but they test whether the directional tell survives in a qkt-executable
form.

| proxy | window | period | trades | PnL | win rate | PF | Sharpe | max DD |
|---|---|---:|---:|---:|---:|---:|---:|---:|
| sweep fade v4 | session edge | 2018-01-01..2021-07-02 | 946 | -6055.49 | 0.467 | 0.302 | -3.662 | 5079.57 |
| FVG short v4 | liquid control | 2018 | 284 | -1343.12 | 0.275 | 0.683 | -1.454 | 1238.30 |
| FVG short v4 | liquid control | 2019 | 56 | -256.30 | 0.286 | 0.718 | -0.606 | 419.66 |
| FVG short v4 | liquid control | 2020 | 338 | -1770.16 | 0.266 | 0.597 | -1.861 | 1556.12 |
| FVG short v4 | liquid control | 2021-H1 | 94 | -408.13 | 0.277 | 0.729 | -1.116 | 617.75 |

Gate decision:

- Do not seed these candidates into qkt-forge gates.
- The faithful rule cannot yet be represented with accurate dynamic stops and
  spread/R gating.
- The qkt-executable approximations are negative or structurally incomparable
  to the research harness.
- Running G1-G8 on these candidates would create false confidence, not
  real-money-trustworthy evidence.

## qkt-Executable Market-Confirmation Variants

Because passive 50% pending-limit retests and dynamic ATR/structure exits were
not faithfully gate-testable, a separate harness tested variants that are closer
to qkt's current executable surface:

- market entry only after the sweep/retest/reclaim has printed;
- fixed price-distance stops;
- RR exits;
- observed spread cost as R;
- no dynamic stop expressions and no pending-order stacking.

Script:

- local: `scripts/research_forge_market_executable_edge.py`
- bot2: `/root/projects/qkt-forge/scripts/research_forge_market_executable_edge.py`

Patterns:

- `rolling_sweep_reclaim`: sweep and close-back-inside of a rolling local
  high/low.
- `previous_hour_sweep_reclaim`: sweep and close-back-inside of the previous
  hour high/low.
- `asian_range_sweep_reclaim`: sweep and close-back-inside of the completed
  `00:00..05:59 UTC` range.
- `previous_day_sweep_reclaim`: sweep and close-back-inside of the previous
  UTC day high/low.
- `previous_week_sweep_reclaim`: sweep and close-back-inside of the previous
  week high/low.
- `rolling_day_sweep_reclaim`: sweep and close-back-inside of the prior
  24-hour same-stream rolling high/low.
- `rolling_week_sweep_reclaim`: sweep and close-back-inside of the prior
  5-day same-stream rolling high/low.
- `fvg_market_retest_reject`: previous-bar FVG, current bar retests the FVG
  midpoint and closes back in the continuation direction.

Bot2 artifacts:

- `run/research/market-executable-1m-eurusd-xau-2018/market_executable_summary.csv`
- `run/research/market-executable-1m-eurusd-xau-2019/market_executable_summary.csv`
- `run/research/market-executable-1m-eurusd-xau-2020/market_executable_summary.csv`
- `run/research/market-executable-1m-eurusd-xau-2021h1/market_executable_summary.csv`
- `run/research/market-executable-aggregate/eurusd_xau_1m_yearly_combined.csv`
- `run/research/market-executable-aggregate/eurusd_xau_1m_key_summary.csv`
- `run/research/market-executable-5m-all-symbols/market_executable_summary.csv`
- `run/research/market-executable-1m-htf-eurusd-xau-2018/market_executable_summary.csv`
- `run/research/market-executable-1m-htf-eurusd-xau-2019/market_executable_summary.csv`
- `run/research/market-executable-1m-htf-eurusd-xau-2020/market_executable_summary.csv`
- `run/research/market-executable-1m-htf-eurusd-xau-2021h1/market_executable_summary.csv`
- `run/research/market-executable-aggregate/eurusd_xau_1m_htf_yearly_combined.csv`
- `run/research/market-executable-aggregate/eurusd_xau_1m_htf_key_summary.csv`
- `run/research/market-executable-5m-htf-all-symbols/market_executable_summary.csv`
- `run/research/market-executable-5m-rolling-htf-all-symbols/market_executable_summary.csv`

1m EURUSD/XAUUSD yearly result:

- No exact key was positive in 3+ yearly periods.
- No hour/session key was positive in 3+ yearly periods.
- Best hour/session keys positive in 2 yearly periods still had negative mean
  expectancy. Example: `EURUSD`, previous-hour sweep reclaim, session edge,
  long, 20-pip stop, RR 2 had 1,335 trades across 4 chunks, but mean expectancy
  was `-0.012R`, worst chunk `-0.038R`, mean PF `0.904`.
- Isolated yearly positives existed, especially EURUSD Asian-range sweep/reclaim
  during session edges in 2019, but the same exact keys did not remain positive
  in 2020 and 2021-H1.

5m all-symbol market-executable result:

- 2,485 rows had at least 30 trades; 98 were positive in aggregate.
- The best target-window rows were not stable enough for real-money gating:

| symbol | pattern | window | side | stop | RR | trades | expectancy R | PF | positive years |
|---|---|---|---|---:|---:|---:|---:|---:|---:|
| XAUUSD | previous-hour sweep reclaim | session edge | short | 4.0 | 2 | 41 | +0.218 | 1.744 | 2/3 |
| EURUSD | FVG market retest reject | hour edge | short | 0.0005 | 10 | 37 | +0.179 | 1.249 | 3/4 |
| EURUSD | FVG market retest reject | session edge | short | 0.0010 | 10 | 186 | +0.052 | 1.094 | 3/4 |
| EURUSD | FVG market retest reject | session edge | short | 0.0010 | 5 | 186 | +0.047 | 1.085 | 3/4 |

The XAUUSD row is too small and concentrated: 39 of 41 trades occurred in 2018,
with only one trade in 2019 and one in 2020. The EURUSD FVG session-edge row is
the only candidate with enough trades to deserve a qkt smoke check, but its
2021-H1 chunk was negative: `-0.222R`, PF `0.656`, 33 trades.

qkt smoke:

- Strategy: `/root/projects/qkt-forge/strategies/research/eurusd_5m_session_fvg_short_market_retest_v5.qkt`
- qkt parse: passed.
- qkt bar-screen results over `2018-01-01..2021-07-02`:

| RR | trades | PnL | win rate | PF | Sharpe | max DD |
|---:|---:|---:|---:|---:|---:|---:|
| 5 | 78 | -94.76 | 0.231 | 0.978 | -0.026 | 418.21 |
| 10 | 78 | -372.02 | 0.103 | 0.765 | -0.249 | 600.25 |

This rejects the best qkt-executable target-window candidate before G1. It
should not be seeded into the gate funnel.

Higher-timeframe level sweep/reclaim screens:

- 1m EURUSD/XAUUSD previous-day and previous-week sweeps found one exact
  target-window key positive in 3 of 4 chunks:
  - `EURUSD`, previous-week sweep reclaim, hour edge, short, 10-pip stop, RR 3.
  - 159 trades across eligible chunks.
  - Mean expectancy `+0.039R`, worst chunk `-0.0129R`, mean PF `1.168`.
- This is weak and small, but it was the first 1m target-window key to survive
  a 3-of-4 yearly check.
- A qkt smoke attempt exposed another engine limitation:
  - qkt does not support `EVERY 1w`.
  - qkt does not allow synchronizing `5m` and `1d` streams in the current
    bar-screen path: `SyncGroupKey members must share the same timeframe`.
  - Therefore previous-week/day level strategies cannot currently be encoded
    faithfully as qkt multi-timeframe strategies.
- A non-faithful qkt attempt using a same-day/session proxy produced 590 trades,
  PF `0.633`, Sharpe `-1.43`, and is rejected.

5m all-symbol previous-day/week sweep screens:

- The strongest target-window row was `LIGHTCMDUSD`, previous-week sweep
  reclaim, session edge, long, stop `1`, RR 3:
  - 30 trades, expectancy `+0.283R`, PF `3.30`, positive 3/4 years.
  - However, only 1 trade occurred in 2018, 1 in 2019, 24 in 2020, and 4 in
    2021-H1. This is too concentrated and too small for real-money trust.
- The strongest repeatable non-target row was `EURUSD`, previous-week sweep
  reclaim, `liquid_control`, short, stop `0.0015`, RR 2:
  - 461 trades, expectancy `+0.108R`, PF `1.247`, positive 4/4 years.
  - This is outside the requested hour/session edge and cannot currently be
    faithfully encoded in qkt because of the previous-week/multi-timeframe
    limitation.

qkt-faithful rolling HTF workaround:

- To avoid qkt's missing weekly bars and mixed-timeframe sync limitation, a
  same-stream rolling level variant was tested using prior `N` bars:
  - 5m rolling day: prior 288 bars.
  - 5m rolling week: prior 1,440 bars.
- Best 5m target-window harness row:
  - `EURUSD`, rolling-week sweep reclaim, session edge, long, stop `0.0015`,
    RR 2.
  - 281 trades, expectancy `+0.133R`, PF `1.379`, positive 3/4 years.
  - Year split: 2018 `+0.286R`, 2019 `-0.107R`, 2020 `+0.282R`,
    2021-H1 `+0.066R`.
- qkt strategy:
  - `/root/projects/qkt-forge/strategies/research/eurusd_5m_session_rolling_week_sweep_long_v8.qkt`
  - Uses `lag(lowest(eur.low, 1440), 1)` to express the same-stream prior
    rolling-week low.
  - qkt parse passed.
- qkt bar-screen over `2018-01-01..2021-07-02` rejected the candidate:

| RR | trades | PnL | win rate | PF | Sharpe | max DD |
|---:|---:|---:|---:|---:|---:|---:|
| 2 | 400 | -1069.01 | 0.355 | 0.781 | -0.641 | 887.10 |
| 3 | 397 | -1065.25 | 0.273 | 0.808 | -0.534 | 938.88 |
| 5 | 389 | -925.04 | 0.191 | 0.856 | -0.363 | 865.17 |
| 10 | 377 | -1086.22 | 0.101 | 0.832 | -0.346 | 1305.38 |

The qkt trade count is also higher than the harness count, so the strategy is
not just negative; the screen/runtime behavior does not match the research
simulation closely enough to trust gate promotion.

## qkt-forge Gate/Data Trust Audit

The qkt-forge gate stack was inspected on `bot2` in:

- `src/qkt_forge/gates/smoke.py`
- `src/qkt_forge/gates/grid.py`
- `src/qkt_forge/gates/validation.py`
- `src/qkt_forge/gates/walkforward.py`
- `src/qkt_forge/gates/significance.py`
- `src/qkt_forge/gates/robustness.py`
- `src/qkt_forge/gates/portfolio.py`
- `src/qkt_forge/gates/verify.py`
- `src/qkt_forge/qkt/runner.py`
- `src/qkt_forge/costs.py`
- `config/gates.yaml`
- `config/qkt.yaml`
- `config/instruments.yaml`

Gate execution model:

- G1-G7 use qkt-forge's fast research screen by default:
  - qkt runs `--bars --broker paper`.
  - qkt-forge then reprices the paper fill tape with spread, commission, and
    slippage from `config/instruments.yaml` plus the spread model in
    `src/qkt_forge/costs.py`.
- G8 is the final tick-truth validator:
  - qkt runs with `tick_fills=True` and `execution_preset: mt5-realistic`.
  - This is the only gate intended to confirm the bar-screen edge survives
    realistic tick fills before promotion.
- G6 stress-tests costs using multipliers `[1.0, 1.5, 2.0]` and requires
  profitability at `1.5x` costs.
- G5 applies deflated Sharpe, DSR probability, Monte-Carlo resampling, PBO, and
  family pooling when the strategy is portable across the configured family.
- G7 checks portfolio admission and lockbox behavior before G8.

Trust assessment for this research class:

- The gate stack is conceptually strong for qkt-executable strategies with
  ordinary bar-close/market/constant-risk behavior.
- It is not currently sufficient for the original colleague-style rule because
  the faithful rule needs capabilities the current screen/runtime cannot express
  or does not expose:
  - dynamic ATR/structure bracket stops;
  - direct spread/R stand-down in qkt strategy conditions;
  - faithful previous-week/day HTF levels without mixed-timeframe sync;
  - pending-order de-duplication for passive retest limits.
- For this strategy class, G1-G7 can reject weak ideas, but a pass would still
  need extra manual skepticism unless G8 tick-fills confirms the exact same
  executable logic and trade count is comparable.
- Any future candidate from this family should be considered gate-eligible only
  if:
  1. qkt parse passes;
  2. qkt screen trade count is close to the research harness event/trade count;
  3. qkt screen PnL/PF/Sharpe are positive on dev and yearly slices;
  4. G8 exact tick/mt5-realistic replay remains profitable;
  5. the report evidence shows no large fill/rejection mismatch.

Diagnostic exact-vs-screen check:

- Strategy:
  `/root/projects/qkt-forge/strategies/research/eurusd_5m_session_rolling_week_sweep_long_v8.qkt`
- Window: `2018-01-01..2018-03-31`
- RR: `2`

| mode | trades | PnL | win rate | PF | Sharpe |
|---|---:|---:|---:|---:|---:|
| screen, paper bars + forge cost adjustment | 28 | -214.31 | 0.214 | 0.351 | -2.321 |
| exact mt5-realistic tick replay | 28 | -143.84 | 0.214 | 0.533 | -2.256 |

Both tiers rejected the candidate. Trade counts matched on this short window,
which is useful, but the candidate was already negative. Metric scale differs
for some fields such as drawdown between report modes, so PnL, PF, trade count,
and report fill tape should be preferred for audit comparisons.

Current gate decision:

- Do not seed any strategy from this research into qkt-forge gates.
- The gates can be trusted to reject the qkt-executable variants tested here.
- The gates cannot yet be trusted to prove the original passive FVG/liquidity
  sweep thesis because the faithful thesis is not representable in the current
  qkt runtime and screen surface.

## Bar Cache Integrity Audit

The qkt-forge bar cache was checked against independently rebuilt bars from the
cached bid/ask tick files.

Script:

- local: `scripts/audit_forge_bar_data_integrity.py`
- bot2: `/root/projects/qkt-forge/scripts/audit_forge_bar_data_integrity.py`

Method:

- rebuild OHLC and average spread from raw cached bid/ask ticks;
- compare by timestamp against qkt-forge cached OHLC/spread bars;
- report row coverage and absolute drift in open/high/low/close/spread.

Representative checks:

| symbol | timeframe | period | cached rows | rebuilt rows | matched rows | cache-only | rebuilt-only | max OHLC drift |
|---|---|---:|---:|---:|---:|---:|---:|---:|
| EURUSD | 5m | 2018-02-01..2018-02-28 | 5,760 | 5,760 | 5,760 | 0 | 0 | `2.22e-16` |
| XAUUSD | 5m | 2018-02-01..2018-02-28 | 5,476 | 5,476 | 5,476 | 0 | 0 | `2.27e-13` |
| XAGUSD | 5m | 2018-02-01..2018-02-28 | 5,472 | 5,472 | 5,472 | 0 | 0 | `1.78e-15` |
| COPPERCMDUSD | 5m | 2018-02-01..2018-02-28 | 5,384 | 5,384 | 5,384 | 0 | 0 | `4.44e-16` |
| LIGHTCMDUSD | 5m | 2018-02-01..2018-02-28 | 5,426 | 5,426 | 5,426 | 0 | 0 | `7.11e-15` |
| EURUSD | 1m | 2019-06-01..2019-06-07 | 7,197 | 7,197 | 7,197 | 0 | 0 | `2.22e-16` |
| XAUUSD | 1m | 2019-06-01..2019-06-07 | 6,885 | 6,885 | 6,885 | 0 | 0 | `2.27e-13` |
| XAGUSD | 1m | 2019-06-01..2019-06-07 | 6,191 | 6,191 | 6,191 | 0 | 0 | `1.78e-15` |
| COPPERCMDUSD | 1m | 2019-06-01..2019-06-07 | 6,085 | 6,085 | 6,085 | 0 | 0 | `4.44e-16` |
| LIGHTCMDUSD | 1m | 2019-06-01..2019-06-07 | 6,608 | 6,608 | 6,608 | 0 | 0 | `7.11e-15` |

Interpretation:

- The tested qkt-forge OHLC/spread bar caches are internally consistent with
  the cached bid/ask ticks.
- This supports the research harness' bar inputs for sampled FX, metals, copper,
  and oil periods on both 5m and 1m.
- It does not make the full dataset immutable. qkt report metadata still marks
  the dataset as a mutable local store with `allow-incomplete`, so real-money
  promotion still requires saved dataset hashes/snapshots or qkt evidence
  bundles for the exact gate run.

## Completion Audit Against Original Objective

| requirement | evidence | status |
|---|---|---|
| Research hour-end/hour-open and session handoff windows | `hour_edge` and `session_edge` windows tested on 5m and 1m data with passive retest and market-confirmation harnesses | done; no repeatable tradeable edge found |
| Test FVG and liquidity-sweep mechanics | `fvg_retest_cont`, `sweep_fade`, and qkt-executable reclaim/retest variants tested | done; original mechanics rejected after costs |
| Include 1m measurement | EURUSD/XAUUSD yearly 1m chunks plus 2018 1m XAG/Copper/Oil extension | done; 1m did not confirm the thesis |
| Check high RR claims `1:5`, `1:20`, `1:50` | RR grid includes `5`, `20`, and `50` in passive harness; qkt smoke checked RR `5`/`10` on best executable FVG candidate | done; hit rates did not support large targets after spread |
| Include EURUSD, XAUUSD, and others | EURUSD, XAUUSD, XAGUSD, COPPERCMDUSD, LIGHTCMDUSD measured | done for the selected liquid qkt-forge set |
| Study symbol correlations | 5m return correlations and one-bar lead checks across EURUSD/XAU/XAG/Copper/Oil | done; no simple cross-symbol lead edge |
| Create strategies if edge exists | Multiple qkt research candidates v1-v8 drafted and smoked where executable | attempted; no gate-worthy candidate survived |
| Run through qkt-forge gates | Not run beyond smoke/exact diagnostics because no candidate met pre-gate standard | intentionally not promoted; gate run would be misleading |
| Confirm gate/data trust for real money | Gate code inspected; G8 tick/mt5-realistic role identified; bar caches sampled against raw ticks | partially proven: sampled data is internally consistent, but real-money trust still requires immutable dataset snapshots and faithful strategy expressibility |

## Interpretation

The tested version of the colleague hypothesis is not currently tradeable:

- Tight wick/FVG stops make observed spread cost consume too much of the risk.
- 1m does not rescue the pattern; it exposes the cost problem more clearly.
- On non-EURUSD 1m commodity/metal symbols, the cost-aware setup had zero
  qualifying trades in the 2018 structural/ATR pass, and the raw unfiltered
  version was strongly negative.
- The large target claims are not supported by hit rates. At 1:20 or 1:50,
  win rate is far below what is needed after spread.
- For gold, silver, copper, and oil, median spread cost often exceeds 1R under
  this entry/stop geometry, making the setup structurally unsuitable for real
  money unless the stop definition is widened materially.

This does not disprove every possible liquidity-sweep/FVG strategy. It rejects
the current mechanical interpretation: first/last 10-minute windows plus
50%-retest wick/FVG entries with tight local stops.

## Next Research Required

Before authoring qkt strategies for gates, the research harness should test:

1. Wider structural stops: prior session high/low, Asian range midpoint/edge,
   or ATR-scaled stops instead of wick/gap micro-stops.
2. Direction rules separated from entry mechanics:
   - sweep fade only in range/low-variance regimes;
   - FVG continuation only with cross-symbol confirmation;
   - stand down when spread/R exceeds a threshold.
3. 1m chunk expansion for any new variant, with every surviving row required to
   be positive in multiple years and compared against 5m and tick-fill
   backtests.
4. qkt-forge gate handoff only after raw expectancy survives costs and the
   hour/session window beats control.

Current conclusion: do not promote or trade the original tight-stop shapes with
real money. The only second-pass candidates are:

1. `EURUSD` low-cost sweep-fade at hour/session edges with structural stops,
   RR 1, because it passed a 5m multi-year screen after spread/R filtering.
2. `EURUSD` low-cost FVG short in liquid-control windows, because it repeated
   in multiple 1m yearly chunks.

Neither is ready for qkt-forge G1. The attempted qkt encodings showed that the
faithful rule cannot currently be represented with accurate dynamic exits and
spread/R gating, while qkt-executable proxies are negative or incomparable.
The qkt-executable market-confirmation variants also failed to produce a
repeatable 1m edge, and the best 5m target-window candidate failed qkt smoke.
The later same-stream rolling HTF workaround also failed qkt smoke.
The next step is not gate promotion; it is either:

1. add/verify qkt runtime support for dynamic bracket stops, pending-order
   de-duplication, and bar/tick spread access; or
2. research a materially different, qkt-executable rule that shows edge before
   any gate run.

The qkt runtime/gateability work is specified in
`docs/superpowers/specs/2026-07-26-forge-fvg-sweep-faithful-gateability-design.md`.
