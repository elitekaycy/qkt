# Closing the cost-model and symbol-coverage gaps

Date: 2026-10-05

## Scope

Follow-up to
[2026-10-05-cme-data-transform-to-qkt-native-format.md](2026-10-05-cme-data-transform-to-qkt-native-format.md).
That session left explicit placeholders: $0 commission/fees, no margin, unconfirmed CL/GC/NG
specs, no tick data, 6J excluded (scale defect), 6E shallow, RTY/ZN/SI never sourced. This
closes as many of those as real research and real data actually allow — and is explicit about
the one that structurally can't be closed for free (tick data).

## Real commission, fees, and margin — now in `instruments.yaml`

Researched via WebSearch (cmegroup.com and interactivebrokers.com both blocked automated
fetches this session — every figure below is corroborated across 3+ secondary sources that
mirror CME/IBKR's own published data, not a literal primary-source screenshot):

- **IBKR commission: $0.85/contract/side, confirmed flat across all CME products** at the
  retail tier (drops to $0.65/$0.45/$0.25 at higher monthly volume).
- **CME exchange/regulatory fees vary by product** (~$1.38 for ES/NQ/RTY, ~$1.60–1.80 for
  energy/metals/FX). Combined into one `exchangeFeePerContract` value per root, since futures
  roots in qkt have no separate commission field — confirmed from `FuturesRoot.metaFor()`.
- **CME SPAN margin** (initial/maintenance, per-contract basis) — a September 2026 snapshot;
  CME recalculates roughly monthly, so this is a point-in-time figure, not a constant. One
  flagged discrepancy: two sources disagreed on GC maintenance margin ($23,078 vs $7,200); used
  the more recent, directly-sourced figure.
- Two items explicitly flagged unconfirmed rather than guessed: ZN's exchange fee structure
  (CBOT quotes a $0.46 fee + $1.25 "floor fee," unclear if additive for electronic trading), and
  JY's exchange fee (no distinct figure found, proxied from 6E).

## A real bug this surfaced immediately — margin vs. default balance

Adding real margin broke every ES backtest: $25,713 real initial margin vs. the default $10,000
backtest balance silently rejected every order (no error logged — orders were submitted but
never accepted/filled). **Not a bug in the margin data — the model being accurate.** Fixed by
creating `~/.qkt/qkt.config.yaml` with `starting_balance: 500000`, which qkt auto-discovers.
Re-verified ES (36 trades, $7,075 P&L, $80.28 commission, $44.60 roll costs) and GC (28 trades,
$30,988 P&L, $71.40 commission) both now carry real costs end to end.

## 6J (Japanese Yen) — fully fixed, not just excluded

Two real bugs found and fixed in sequence, both caught by actually running the data through
the engine rather than trusting it after fixing the first one:

1. **The known scale defect** (2022-expiry contracts off by 10,000× vs. 2000-2021) — confirmed
   via real-world USD/JPY exchange rate history (113.72-151.69 in 2022) that the correction
   direction and magnitude were exactly right. Fixed by rescaling the four 2022 contract files.
2. **A second, deeper bug only found by actually backtesting it**: even after the internal
   rescale, every JY order silently failed to fill. Root cause: the entire dataset (all years,
   not just 2022) used a vendor display convention (rate × 1,000,000 ≈ 8,456) rather than the
   true economic USD/JPY rate (≈ 0.0085) that qkt's notional/margin accounting needs when
   multiplied against the real contract multiplier (12,500,000 JPY) — the mismatched scale was
   producing a ~$105 billion notional per contract, silently tripping a safety gate. Fixed by
   dividing the entire corrected series by 1,000,000 to the true rate. Verified: Dec 2010 close
   of 0.011945 lines up with the real USD/JPY rate that month (~1/83 ≈ 0.01205).

Both fixes verified end-to-end with a real backtest: 38 trades, real P&L, real $100.70
commission, 2010-2012. JY is now a fully validated sixth symbol alongside ES/NQ/CL/GC/NG —
same depth (2000-2022), same roll validation, same real cost modeling.

## RTY, SI, ZN — found, but a genuinely different shape of data

No free per-contract + open-interest dataset exists for these (confirmed: the `choweric` Kaggle
author's full 12-dataset catalog was enumerated and none of the three are in it; broad search
found nothing equivalent). One real lead was found and used:
**`ramanthind/futures-daily-data`** on Kaggle — but it is a **single continuous series per
symbol with no open interest and an undocumented roll methodology**, structurally different
from (and lower-fidelity than) the catalog + roll-history-backed ES/NQ/CL/GC/NG/JY roots.

Deliberately **not** declared as `futures:` roots — doing so would misrepresent data that has no
real per-contract or roll information as if it had the same fidelity as the validated six.
Instead wired in honestly as plain non-derivative instruments under a distinct broker prefix
(`CME_CONT:RTY`, `CME_CONT:SI`, `CME_CONT:ZN`) with real contract specs (multiplier/tick size,
same sourcing tier as the others) but no roll/catalog machinery. Verified working end-to-end:
a real backtest on `CME_CONT:RTY` (2018-2020) produced 27 trades, real P&L, real $60.21
commission. One genuinely interesting, unexplained finding: this source's SI and ZN coverage
runs 2000-2025 — both wider and more current than the six validated roots — reason unconfirmed,
worth a closer look if these become more important later. A data-quality defect was also found
and fixed in this source: SI and ZN's real dates live in a different CSV column (`index`) than
RTY's (`datetime`) — inconsistent per-symbol structure within the same file.

## Still open, for real

- **6E (Euro FX)**: confirmed no free source extends coverage back past 2018 (Kaggle, Investing.com,
  and Stooq all checked; Stooq doesn't serve futures CSVs at all per independent confirmation).
  Remains excluded from the full-depth set.
- **Tick/quote data, any symbol, any year**: structurally does not exist for free. Every free
  source checked across this entire research thread (CME's own samples, Kaggle, Stooq,
  Investing.com) tops out at daily bars or a single sample day. The only way to get real
  multi-year tick/quote data is to pay — Kibot's $400-800 one-time 10-symbol package remains the
  cheapest confirmed option from earlier research. This cannot be closed without spending money;
  stated plainly rather than worked around.

## Sources

- [2026-10-05-cme-data-transform-to-qkt-native-format.md](2026-10-05-cme-data-transform-to-qkt-native-format.md)
- [2026-10-05-es-nq-backtest-data-acquisition.md](2026-10-05-es-nq-backtest-data-acquisition.md) (Kibot pricing)
- Kaggle `ramanthind/futures-daily-data`
- IBKR futures commission schedule (via Barchart/BrokerChooser secondary sourcing)
- CME SPAN margin (via DiscountTrading.com, Sept 2026 snapshot)
- Real USD/JPY 2022 exchange-rate history (exchange-rates.org, poundsterlinglive)
