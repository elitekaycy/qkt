# Macro Series Data Path (daily yields / real rates) — Design

> Scoping spec. Motivated by cross-market gold research in qkt-forge: gold's dominant
> drivers are the US dollar and **real yields**, but yields are not tradeable tick
> instruments — they are daily macro series published by a data authority. This spec
> defines how qkt ingests such series into the backtester (and live), preserving
> backtest=live parity. Tracked as **#440**; implementation plan at
> `docs/superpowers/plans/2026-06-14-macro-series-data-path.md` — review before building begins.

## 1. Purpose

Let a strategy reference **daily macro time series** — nominal Treasury yields (2y/10y),
the 10y real yield (TIPS), and similar — as first-class inputs, so conditions like
"go long gold while the 10y real yield is falling" are expressible and backtestable.

Today qkt can only ingest **traded instruments** with tick/bar data (FX, metals, and —
via #438/#439 (merged) — DXY and equity indices, all served as dukascopy bi5 ticks). A
constant-maturity yield (e.g. FRED `DGS10`) or a real yield (`DFII10`) is **not traded**
and has **no tick stream**; it is one value per business day from FRED/Treasury. That is a
fundamentally different data shape and needs its own source + feed, not another allowlist
entry.

## 2. Why this is NOT the dukascopy-index approach (#438)

#438 adds DXY/SPX/etc. by adding allowlist entries to `DukascopyInstrument.TABLE` — because
dukascopy already serves them as free bi5 **tick** data and qkt's decoder is divisor-driven.
"The change is data, not pipeline."

Yields are the opposite: **no tick feed exists**. So this needs a genuinely new, small
pipeline — a daily-cadence source, a daily-series store, and a slow feed that the engine
merges alongside tick/bar feeds. The two efforts are complementary, not overlapping:

| | #438 indices (DXY, SPX…) | This spec (yields, real rates) |
|---|---|---|
| Source | dukascopy bi5 ticks | FRED (or Treasury) daily CSV/API |
| Cadence | tick / intraday | one value per US business day |
| Pipeline change | allowlist entry only | new source + daily store + slow feed |
| Tradeable? | yes (it's a CFD/index quote) | no (a published statistic) |

## 3. The hard problem: point-in-time, no look-ahead

This is the load-bearing correctness requirement and the reason the design needs care.

A daily series value "for date D" is **not known during day D** — FRED publishes most series
the next business morning. A backtest standing at intraday timestamp T must see only the
value that was actually published as of T. Using `DGS10[D]` while simulating any time on day
D is look-ahead and silently inflates results.

Two fidelity levels:

- **(a) Fixed-lag (recommended first):** treat the value dated D as **available at
  D + publication_lag** (e.g. next business day 13:00 UTC, the conservative FRED release
  window). Simple, deterministic, conservative.
- **(b) Vintage-exact (deferred):** use FRED's ALFRED real-time vintages — "what was known
  as of date X" — to reproduce revisions exactly. Higher fidelity, much more storage/complexity.

Start with (a); make the lag explicit and per-series. Document it loudly.

## 4. Architecture overview

The change is additive and mirrors existing market-data patterns (`seamless-backtest-data`,
the dukascopy store):

```
FRED API  ──fetch──>  Daily-series store  ──replay──>  MacroSeriesFeed (1/day, point-in-time)
(DGS2, DGS10,         data-root/macro/                  └─ merges into the existing
 DFII10, …)           {SERIES}/{year}.csv                  MergingTickFeed alongside symbols
```

- **Source:** `FredSeriesFetcher` (OkHttp; FRED `series/observations` JSON; free, API key via
  env, optional for low volume). Mirrors `DukascopyTickFetcher` in shape.
- **Store:** `data-root/macro/{SERIES}/{year}.csv` rows `date,value` (one per business day).
  Idempotent, resumable, auto-provisioned on demand — same ergonomics as the seamless tick
  fetch (so a backtest referencing `DGS10` just works).
- **Feed:** `MacroSeriesFeed` emits one update per business day **at the lagged release time**,
  carrying the last-published value (a step function that holds between releases). It plugs
  into the existing multi-feed merge (`MergingTickFeed`) so a strategy can hold gold ticks +
  a daily yield series in one backtest with deterministic ordering.

## 5. DSL exposure

Reuse the `SYMBOLS` block with a new source prefix — no new top-level block — so it reads
like any other bound series:

```
SYMBOLS
    gold      = BACKTEST:XAUUSD EVERY 5m WARMUP 50 BARS
    real10y   = MACRO:DFII10    EVERY 1d WARMUP 20 BARS

RULES
    # Gold tends to rise when real yields fall (lower opportunity cost of holding gold).
    WHEN gold.close CROSSES ABOVE ema(gold.close, 50)
     AND real10y.value < ema(real10y.value, 20)
     AND POSITION.gold = 0
    THEN BUY gold SIZING 0.01

    WHEN POSITION.gold > 0 AND real10y.value > ema(real10y.value, 20)
    THEN CLOSE gold
```

Open question: field name. A traded symbol exposes OHLC (`.close`). A macro series has a
single value per day — expose `.value` (and let `.close` alias it for indicator reuse so
`ema(real10y.value, 20)` and the existing indicator machinery work unchanged). Decide one.

Macro series are **read-only**: binding one is legal, ordering on it is a compile error
(there is nothing to trade).

## 6. Determinism & backtest=live parity

- Backtest replays the stored series through `MacroSeriesFeed` with the fixed-lag rule.
- Live fetches the same FRED series on the same cadence and applies the same lag, so the
  value visible to a strategy at wall-clock T is identical in both modes — the qkt parity
  invariant holds because both go through one feed implementation.
- Timezone: FRED dates are US business days; the release time is modeled in UTC. Weekends/
  holidays hold the prior value (the series simply does not update).

## 7. Scope & sequencing

**Phase 1 (this spec):** FRED source, daily-series store + auto-provision, `MacroSeriesFeed`,
`MACRO:` DSL binding (read-only, `.value`), fixed-lag point-in-time, a starter series set:
`DGS2`, `DGS10` (nominal), `DFII10` (10y real). Indicators over the series via the existing
machinery.

**Deferred:** ALFRED vintage-exact mode; intraday macro releases (CPI/NFP surprise timing);
non-FRED providers; any tradeable-instrument treatment of rates (that would be a futures
instrument via the normal tick path, not this).

DXY is intentionally **out of scope** — it arrives tick-native via #438, which is the better
representation for an index that genuinely trades.

## 8. Risks / open questions

- **Point-in-time fidelity vs simplicity.** Fixed lag is an approximation; a series revised
  after first publish will differ from vintage-exact. Acceptable for yields (rarely revised)
  — flag it; revisit with ALFRED if a strategy proves lag-sensitive.
- **Release time-of-day.** Picking a single conservative UTC release time per series is an
  assumption; verify against FRED's actual release calendar before trusting intraday timing.
- **Warmup.** 20 business days of a daily series ≈ a month of calendar data — backtests need
  enough lead-in; the provisioner must fetch warmup history before the test window.
- **`.value` vs `.close` aliasing** — pick one and keep indicators uniform.

## 9. Testing

- Fake FRED fixture (a fixed `date,value` table) → assert the feed emits the **lagged** value
  (a hand-checked look-ahead test: at simulated D 12:00 the strategy must NOT see D's value).
- Multi-feed determinism: gold ticks + a daily series in one backtest produce byte-identical
  output across runs (seeded).
- Parity: the same series drives identical strategy decisions in backtest replay and a
  simulated-live run.

## 10. References

- Motivation: qkt-forge cross-market discovery/authoring (gold edges need the rates dimension).
- Complementary (merged): #438 / PR #439 dukascopy index instruments (DXY/SPX tick-native).
- Existing patterns to mirror: `2026-06-08-seamless-backtest-data-design.md`, the dukascopy
  store/fetcher, `MergingTickFeed`.
