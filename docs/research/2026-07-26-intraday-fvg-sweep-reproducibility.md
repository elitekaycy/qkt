# Intraday FVG/Sweep Research Reproducibility Manifest

Date: 2026-07-26

Primary decision note:

- `docs/research/2026-07-26-intraday-fvg-sweep-edge-study.md`

Follow-up platform spec:

- `docs/superpowers/specs/2026-07-26-forge-fvg-sweep-faithful-gateability-design.md`

Local research scripts:

- `scripts/research_forge_intraday_edge.py`
- `scripts/research_forge_market_executable_edge.py`
- `scripts/audit_forge_bar_data_integrity.py`

Remote qkt-forge root:

- `bot2:/root/projects/qkt-forge`

Remote data roots:

- bars: `/root/projects/qkt-forge/run/data/_bars`
- ticks: `/root/projects/qkt-forge/run/data/symbols/<SYMBOL>/*.csv.gz`

Remote qkt-forge commit observed during the final artifact hash pass:

- `543d4484eb2c27787f09c5f556c5aa3dcb2333ec`

Important caveat:

- `bot2:/root/projects/qkt-forge` had a dirty worktree during the final hash
  pass, including modified config and gate files. The CSV hashes below identify
  the observed artifacts, but they are not a substitute for immutable qkt-forge
  evidence bundles.

## Decision

Do not seed any strategy from this research into qkt-forge gates.

Reason:

- original passive FVG/sweep retest rules are negative after spread/cost;
- 1m does not rescue the hour/session thesis;
- qkt-executable approximations fail qkt smoke;
- faithful original rules require qkt capabilities not currently available;
- sampled bar caches match raw ticks, but qkt reports still mark the dataset as
  mutable/local with `allow-incomplete`.

## Artifact Hashes

Local files, SHA-256:

| file | sha256 |
|---|---|
| `scripts/research_forge_intraday_edge.py` | `f98a06081915e0b9d372da757fe2fdb0f521e873f6edf623fc2cbbf835026394` |
| `scripts/research_forge_market_executable_edge.py` | `d6def1a033187cd460c3b248c6c70de9d43378ce87b8fc1e5f3d2f31c398f887` |
| `scripts/audit_forge_bar_data_integrity.py` | `701cefd705143a0645b24ddcf3e130bd40815ba2b3119614c2ad6e8ca47b8690` |
| `docs/research/2026-07-26-intraday-fvg-sweep-edge-study.md` | `450ec0f0518d51d0c2058ce41de3c238260356a03e484c3e78a40db4f2bb0e7d` |
| `docs/superpowers/specs/2026-07-26-forge-fvg-sweep-faithful-gateability-design.md` | `eb2f2010d735449fcadbde3e38c65e3d6caff17793ed026ba81b16b509c1f784` |

The reproducibility manifest's own hash is omitted because embedding it would
make the file self-referential.

Key bot2 CSV result artifacts, SHA-256:

| artifact | sha256 |
|---|---|
| `run/research/intraday-edge-5m-eurusd/intraday_edge_summary.csv` | `ef7176cc9b5e02af273eb42b52a26bfb6b06c7ebd295e179ee23a448f889a78e` |
| `run/research/intraday-edge-5m-XAUUSD/intraday_edge_summary.csv` | `e25a154cb284ba0cbb72dd2ac93f6413fb688b06b2838b8e00d1cfa6f9b1480a` |
| `run/research/intraday-edge-5m-XAGUSD/intraday_edge_summary.csv` | `e654f495ee25a8994ce59b77e1cef2c12a6a3dcf73d3f399383777ac177d219d` |
| `run/research/intraday-edge-5m-COPPERCMDUSD/intraday_edge_summary.csv` | `7ed6080473e9e06192a4d451b3c61ce954dbe09967e781bbf6918375c34da78d` |
| `run/research/intraday-edge-5m-LIGHTCMDUSD/intraday_edge_summary.csv` | `c0c8ac0a1c3714098ed201644eebe6331fb035685f9426e918ab36d78fbb085f` |
| `run/research/intraday-edge-1m-2018-EURUSD/intraday_edge_summary.csv` | `ac1eb552116a37199a7f7da505588c88e798828f0ae1cd64976ce83bf93fce69` |
| `run/research/intraday-edge-1m-2018-XAUUSD/intraday_edge_summary.csv` | `861b3dfa07653f256d438e9fae4cc8037b2b77e461c1982d4fc3de274bafda08` |
| `run/research/intraday-edge-1m-others-2018-structural/intraday_edge_summary.csv` | `f3a8646cf9bed5eca75e66eb2bd9707c4ea9a03ddbb64a54ddc3d4a45269714a` |
| `run/research/intraday-edge-1m-others-2018-structural-costfilter/intraday_edge_summary.csv` | `8f13bd66a6fe8318ccd4211bb35e95c08f4c12d4fb1fb8c40039721bd19f49f6` |
| `run/research/market-executable-1m-eurusd-xau-2018/market_executable_summary.csv` | `5377fc8913adffbb21caecc0bd8d75d02f31e13e78f02f1a65b7d1941c1de3ed` |
| `run/research/market-executable-1m-eurusd-xau-2019/market_executable_summary.csv` | `906cd050f7e4f4783288c73c8f55671da743d05841cbaa4d8c996fab8628ad80` |
| `run/research/market-executable-1m-eurusd-xau-2020/market_executable_summary.csv` | `0cbd56042971664d8b843a873dc8a721dcb143e35f8de24089cf2f59ceaa9a4c` |
| `run/research/market-executable-1m-eurusd-xau-2021h1/market_executable_summary.csv` | `f4c78e2a965e610b18152a461c4333ff78ea9073818d62590ce0e639f283eb5b` |
| `run/research/market-executable-5m-all-symbols/market_executable_summary.csv` | `e951d8a8877b326142736ce37290b1b1b87368d63c00d2549e4314d2196dbc0c` |
| `run/research/market-executable-5m-htf-all-symbols/market_executable_summary.csv` | `016ea262a46ea1a401559bb2ccfd89787c50456cca1eedaf6a3b1b7747e4f020` |
| `run/research/market-executable-5m-rolling-htf-all-symbols/market_executable_summary.csv` | `636fdb54dbf411499c3d7fd1b5b6e9e55c41ebcbd554f5ede77a15746aacc783` |

## Key Bot2 Artifacts

Original passive retest harness:

- `run/research/intraday-edge-5m-eurusd/intraday_edge_summary.csv`
- `run/research/intraday-edge-5m-XAUUSD/intraday_edge_summary.csv`
- `run/research/intraday-edge-5m-XAGUSD/intraday_edge_summary.csv`
- `run/research/intraday-edge-5m-COPPERCMDUSD/intraday_edge_summary.csv`
- `run/research/intraday-edge-5m-LIGHTCMDUSD/intraday_edge_summary.csv`
- `run/research/intraday-edge-5m-structural-costfilter/intraday_edge_summary.csv`
- `run/research/intraday-edge-1m-2018-structural-costfilter/intraday_edge_summary.csv`
- `run/research/intraday-edge-1m-others-2018-structural/intraday_edge_summary.csv`
- `run/research/intraday-edge-1m-others-2018-structural-costfilter/intraday_edge_summary.csv`
- `run/research/intraday-edge-aggregate/eurusd_xau_1m_costfilter_key_summary.csv`
- `run/research/intraday-edge-correlations/window_correlations.csv`

Market-executable variants:

- `run/research/market-executable-1m-eurusd-xau-2018/market_executable_summary.csv`
- `run/research/market-executable-1m-eurusd-xau-2019/market_executable_summary.csv`
- `run/research/market-executable-1m-eurusd-xau-2020/market_executable_summary.csv`
- `run/research/market-executable-1m-eurusd-xau-2021h1/market_executable_summary.csv`
- `run/research/market-executable-aggregate/eurusd_xau_1m_key_summary.csv`
- `run/research/market-executable-5m-all-symbols/market_executable_summary.csv`

Higher-timeframe level variants:

- `run/research/market-executable-aggregate/eurusd_xau_1m_htf_key_summary.csv`
- `run/research/market-executable-5m-htf-all-symbols/market_executable_summary.csv`
- `run/research/market-executable-5m-rolling-htf-all-symbols/market_executable_summary.csv`

qkt smoke candidate files:

- `strategies/research/eurusd_5m_session_fvg_short_market_retest_v5.qkt`
- `strategies/research/eurusd_1m_hour_edge_previous_week_sweep_short_v6.qkt`
- `strategies/research/eurusd_5m_session_rolling_week_sweep_long_v8.qkt`

## Reproduction Commands

Run from `bot2:/root/projects/qkt-forge`.

Passive retest baseline:

```bash
./.venv/bin/python scripts/research_forge_intraday_edge.py \
  --timeframe 5m \
  --date-from 2018-01-01 \
  --date-to 2021-07-02 \
  --symbols EURUSD XAUUSD XAGUSD COPPERCMDUSD LIGHTCMDUSD \
  --out-dir run/research/intraday-edge-5m-baseline-rerun
```

Structural/ATR stops with spread/R stand-down:

```bash
./.venv/bin/python scripts/research_forge_intraday_edge.py \
  --timeframe 5m \
  --date-from 2018-01-01 \
  --date-to 2021-07-02 \
  --symbols EURUSD XAUUSD XAGUSD COPPERCMDUSD LIGHTCMDUSD \
  --stop-models atr structure \
  --max-entry-cost-r 0.10 \
  --out-dir run/research/intraday-edge-5m-structural-costfilter-rerun
```

1m passive retest checks for non-EUR/non-XAU symbols:

```bash
./.venv/bin/python scripts/research_forge_intraday_edge.py \
  --timeframe 1m \
  --date-from 2018-01-01 \
  --date-to 2018-12-31 \
  --symbols XAGUSD COPPERCMDUSD LIGHTCMDUSD \
  --stop-models atr structure \
  --rr 1 2 3 5 10 20 50 \
  --out-dir run/research/intraday-edge-1m-others-2018-structural-rerun

./.venv/bin/python scripts/research_forge_intraday_edge.py \
  --timeframe 1m \
  --date-from 2018-01-01 \
  --date-to 2018-12-31 \
  --symbols XAGUSD COPPERCMDUSD LIGHTCMDUSD \
  --stop-models atr structure \
  --max-entry-cost-r 0.10 \
  --rr 1 2 3 5 10 20 50 \
  --out-dir run/research/intraday-edge-1m-others-2018-structural-costfilter-rerun
```

1m market-executable yearly chunks:

```bash
for spec in \
  2018:2018-01-01:2018-12-31 \
  2019:2019-01-01:2019-12-31 \
  2020:2020-01-01:2020-12-31 \
  2021h1:2021-01-01:2021-07-02
do
  IFS=: read label from to <<EOF
$spec
EOF
  ./.venv/bin/python scripts/research_forge_market_executable_edge.py \
    --timeframe 1m \
    --date-from "$from" \
    --date-to "$to" \
    --symbols EURUSD XAUUSD \
    --out-dir "run/research/market-executable-1m-eurusd-xau-$label-rerun"
done
```

5m rolling HTF qkt-faithful screen:

```bash
./.venv/bin/python scripts/research_forge_market_executable_edge.py \
  --timeframe 5m \
  --date-from 2018-01-01 \
  --date-to 2021-07-02 \
  --symbols EURUSD XAUUSD XAGUSD COPPERCMDUSD LIGHTCMDUSD \
  --patterns rolling_day_sweep_reclaim rolling_week_sweep_reclaim \
  --out-dir run/research/market-executable-5m-rolling-htf-all-symbols-rerun
```

Bar cache integrity sample:

```bash
./.venv/bin/python scripts/audit_forge_bar_data_integrity.py \
  --timeframe 5m \
  --date-from 2018-02-01 \
  --date-to 2018-02-28 \
  --symbols EURUSD XAUUSD XAGUSD COPPERCMDUSD LIGHTCMDUSD

./.venv/bin/python scripts/audit_forge_bar_data_integrity.py \
  --timeframe 1m \
  --date-from 2019-06-01 \
  --date-to 2019-06-07 \
  --symbols EURUSD XAUUSD XAGUSD COPPERCMDUSD LIGHTCMDUSD
```

qkt smoke for rejected rolling-week candidate:

```bash
./.venv/bin/python - <<'PY'
from pathlib import Path
from qkt_forge.config import load_config
from qkt_forge.qkt.runner import QktRunner

cfg = load_config(Path("config"))
r = QktRunner(cfg.qkt, "/root/projects/qkt-forge")
p = "strategies/research/eurusd_5m_session_rolling_week_sweep_long_v8.qkt"

for rr in ["2.0", "3.0", "5.0", "10.0"]:
    res = r.backtest(
        strategy_path=p,
        date_from="2018-01-01",
        date_to="2021-07-02",
        screen=True,
        params={"rrMult": rr},
    )
    print(rr, res.trade_count, res.total_pnl, res.profit_factor, res.sharpe_ratio)
PY
```

## Minimum Standard For Any Future Gate Candidate

A future candidate from this family should not enter qkt-forge gates unless all
of these are true:

1. qkt parse passes.
2. qkt screen trade count is close to the harness event/trade count.
3. qkt screen PnL, profit factor, and Sharpe are positive on development and
   yearly slices.
4. the exact same executable logic can be represented without dynamic-stop,
   spread-access, mixed-timeframe, or pending-order-de-duplication gaps.
5. G8 tick/mt5-realistic replay remains profitable.
6. qkt report evidence includes the exact command, strategy hash, config hash,
   report files, and no major rejection/fill mismatch.
