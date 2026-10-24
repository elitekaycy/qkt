# One calendar rule for backtest and live

Status: implemented (2026-10-24). Supersedes the first-symbol limitation recorded as divergence row A9 in
`docs/parity/backtest-vs-live.md`.

## Problem

A trading calendar answers per-symbol questions (is the market open, when does the trading day end, which days
must hold data) and one whole-run question (how many equity samples a year, which turns per-sample returns into an
annual Sharpe, Sortino and book volatility). Today:

1. **The NYSE calendar is unfinished.** `NyseCalendar` never implemented `tradingPeriodsPerYear`, so every
   SPX/NDX/DJI/RUT backtest with a timeframe stops with `tradingPeriodsPerYear not implemented for nyse`, and a live
   portfolio whose first stream is on NYSE fails at deploy. It also has no 13:00 early closes and no one-off
   closures (national days of mourning, Hurricane Sandy).
2. **Backtest and live pick a symbol's calendar differently.** Live asks the account serving the stream
   (`brokers.<name>.calendars`, the built-in venue presets, the venue default). A backtest never reads the broker:
   it uses a hard-coded name list (`BTC*`/`ETH*`/`*USDT` crypto, `SPX`/`NDX`/`DJI`/`RUT` NYSE, everything else FX).
   The5ers BTC is on FX live and crypto in a backtest; a futures contract is on its root's calendar for the
   strategy but on the gateway's calendar for the live market-data gate.
3. **One calendar per strategy, chosen by stream order.** `strategyCalendar` takes the first symbol's calendar
   for the whole strategy. A DAY order on the second symbol expires at the first symbol's session end, and the
   annualization uses the first symbol's hours whatever the others are.
4. **The annualization does not match how equity is sampled.** Measured in the code:
   - The run's equity curve (`EquityCurveCollector`) samples a candle boundary only once **every** traded symbol
     has closed a bar at it. EURUSD + BTCUSD samples on FX hours only, so the right count is the **overlap** of
     the symbols' sessions. Today it is the first symbol's, which overstates Sharpe by about 18% (sqrt(7/5)) when
     BTC is listed first, e.g. the hg20k book.
   - The book samplers (`BookRiskMonitor`, `BookReturnCollector`, the live `PortfolioSupervisor` feed into
     `BookRiskController`) sample on **every** `CandleEvent`, one per symbol per boundary. With N symbols closing
     together they take N samples, N-1 of them a zero return, which dilutes the return variance about N times:
     book volatility reads about sqrt(N) too low and an `ALLOCATE ... TARGET VOL` book levers up about sqrt(N)
     too far; `REBALANCE EVERY k` counts symbol-candles, not bars. (To be confirmed by a failing test first.)

## Rule after the fix

A symbol's calendar is resolved by one function, in backtest and live alike:

1. **Futures root `calendar:`** in the instrument catalog, when the symbol is a futures contract or stream.
2. **The venue's calendars**: the account the stream's prefix names, i.e. `brokers.<name>.calendars` layered over
   the built-in venue preset and the venue default (MT5 `fx`, gateway `crypto`), exactly as live builds them.
   - Live: the account serving the prefix (unchanged).
   - Backtest: `--venue <name>` when given; otherwise the broker the stream's prefix names when qkt knows it (from
     `qkt.config.yaml` or the built-in MT5 presets); otherwise step 3. `BACKTEST:` names no venue.
3. **No venue known**: the built-in name rules (unchanged).

Per-symbol questions use that symbol's calendar everywhere (a strategy's calendar dispatches by symbol). The
annualization is computed from the calendars of exactly the symbols the sampler waits for:

- run and per-strategy Sharpe/Sortino: **overlap** of the candle symbols' sessions;
- book volatility, ERC/inverse-vol/vol-target and rebalance cadence: **union** of the book symbols' sessions, with
  one sample per candle boundary.

When the combined session equals one member's session (FX + crypto overlap = FX; FX + NYSE union = FX), that
member's own count is used, so a single-market run's numbers do not change by a digit. Otherwise the count is
measured: candle-grid bars touched by the combined session over 2021-01-01..2025-01-01 (1,461 days, exactly four
365.25-day years), divided by four.

## Decisions made while implementing

- **No spot `calendar:` in `instruments.yaml`.** Live reads instrument specs from the venue, not from
  `instruments.yaml`, so a spot calendar there would apply to backtests only. A venue's hours (stocks included) are
  declared on its broker entry, which both modes read.
- **Backtests read broker calendars through the connector** (`Connector.tradingHours`, config only, no secrets, no
  network), the code that builds the live account's calendars, plus each connector's built-in profiles
  (`Connector.builtInTradingHours`). A broken `calendars:` rule or `extends:` chain fails only a run whose streams or `--venue` name a configured broker, as the daemon would refuse the config.
- **The live market-data gate and MT5 pollers keep reading the venue's calendars directly.** For every non-futures
  symbol that is exactly what the resolver returns; rewiring the live gate would add risk for no change.
- **Data coverage keeps the data's own calendar** (an import's recorded `calendar`, else the built-in rules): which
  days a store must hold is a property of the data source, and `qkt data verify` must keep agreeing with a backtest.
- **Book risk sampling** (parity row A65, #1490): fixed in a follow-up. A book with a controller samples live on the
  children's symbols at the first child's timeframe, as its backtest does.

## What does not change

- Bars stay on the UTC epoch grid; `vwap_session`, `session_range_*` and pivots keep their fixed UTC hours.
- Live per-symbol resolution for every current deployment: forward (exness presets) and hg20k (explicit rules
  including `*`) resolve every traded symbol exactly as before. Pinned by a test over both configs' rule shapes.
- The data-coverage rule (a manifest's imported `calendar` first) stays; its fallback becomes the shared resolver.

## Tasks

Each task is test-first: a failing test reproducing the defect, then the fix, then the targeted suite.

1. **NYSE complete** (`common`)
   - Early closes at 13:00 New York: Jul 3 when Mon-Thu, the Friday after Thanksgiving, Dec 24 when Mon-Thu.
   - Special full closures: 2001-09-11..14, 2004-06-11, 2007-01-02, 2012-10-29, 2012-10-30, 2018-12-05, 2025-01-09.
   - `tradingPeriodsPerYear`: measured candle-grid bars over the reference span (daily = trading days a year).
   - A completeness test: every built-in calendar answers every question for 1m..1d windows.
2. **`TradingPeriods`** (`common`): overlap/union count with the member shortcut and the measured fallback;
   empty overlap falls back to the smallest member's own count. Cached per calendar.
3. **Run annualization** (`research`): `ReplayResultBuilder` uses the overlap over the candle symbols.
4. **Book sampling** (`backtest`, `risk`, cli daemon): failing test for the duplicate samples; one sample per
   boundary in `BookRiskMonitor`, `BookReturnCollector` and the live aggregator; union annualization in
   `ReplayRisk`, `PortfolioDeployer.bookAnnualization` and the book volatility report.
5. **Per-symbol strategy calendar** (`common`, cli): `strategyCalendar` returns a calendar that dispatches the
   symbol-taking questions to each symbol's own calendar (DAY-order expiry, option expiry, session indicators in
   the Kotlin samples via `forSymbol`). Anchor questions without a symbol keep the first symbol's calendar.
6. **One resolver** (cli): `CalendarResolver` implements the rule above; `liveCalendarFor`, the daemon,
   portfolio deploy, bot sessions, backtest, research replay, coverage fallbacks, `qkt parse` and `preflight` all
   call it. A test fails the build if any production code calls `BacktestContext.defaultCalendars()` outside the
   resolver.
7. **Visibility** (cli): `qkt calendars <file> [--venue <name>] [--json]` prints each symbol's calendar and the
   step that chose it; the daemon logs the same table at strategy start; a backtest with no venue says so.
8. **Parity proof**: the same mixed strategy and config resolve identical calendars and identical annualization
   through the backtest and live wiring; an attestation case for a mixed FX + BTC book.
9. **Docs**: config schema (`--venue`, resolution order), the calendar concept, CLI reference, parity row A9,
   changelog.

## Out of scope

- Extended-hours stock sessions (`nyse_extended`), the planned 23x5 schedule and session-aligned bars.
- The FX and CME counts for windows longer than an hour ([#1491](https://github.com/elitekaycy/qkt/issues/1491)):
  fixed in a follow-up by counting the UTC-grid bars the session touches.
- Venue preset changes for symbols they do not name today.
