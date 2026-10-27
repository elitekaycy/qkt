# Equities: stock CFDs on MT5, then cash equities through a venue gateway

Status: in progress (draft PR). Plan: [`../plans/2026-10-27-equities.md`](../plans/2026-10-27-equities.md).

## Goal

qkt trades stocks in two forms, each proven backtest-to-live with attestation evidence, without
changing the trades of anything that runs today:

1. **Stock CFDs on an MT5 broker** (Exness first): the same account, gateway and connector qkt
   already trades FX, metals and crypto through.
2. **Cash equities** through a stock broker (Alpaca first) as a `qkt-venue-gateway` adapter, with
   the stock mechanics qkt does not have yet: corporate actions, extended sessions and auctions,
   buying power, short-sale availability, pattern-day-trader and settlement rules, regulatory fees.

## What the venue says (measured 2026-10-27, Exness demo, local gateway)

Exness lists 355 symbols; about 140 are US stock CFDs under the path `Standard\Stocks\` (`AAPLm`,
`NVDAm`, `METAm`, ...). From `/symbol_info`:

| Field | AAPLm | Meaning for qkt |
|---|---|---|
| `trade_contract_size` | 100 | 1 lot = 100 shares; `volume_min` 0.01 = 1 share, `volume_max` 10 lots |
| `point`, `trade_tick_size`, `digits` | 0.01, 0.01, 2 | cent prices |
| `swap_mode`, `swap_long`, `swap_short` | 1 (points), -6.1, -5.3 | financing charged both sides |
| `swap_rollover3days` | 5 (Friday) | triple swap on Friday, not FX's Wednesday |
| `trade_calc_mode` | 2 (CFD) | |
| `filling_mode` | 3 | FOK and IOC |
| `trade_mode` | 4 full; 3 close-only (`FBm`); 0 disabled (`ATVIm`, `BBBYm`, `BRQSm`, quotes frozen in 2023) | entries must be refused on 0 and 3 |

**Sessions**, measured from Exness's own M1/M5/M15/H1 bars and ticks (2025-01 to 2026-10):

- NYSE trading days only: closed on every NYSE holiday checked (New Year, 2025-01-09 national day of
  mourning, Good Friday, Memorial Day, Juneteenth, Independence Day observed, Thanksgiving, Christmas).
- Regular days: **06:00-15:45 America/New_York** (10:00-19:45 UTC in summer, 11:00-20:45 UTC in
  winter). Pre-market from 06:00; the last quarter hour of the NYSE session is not traded.
- NYSE early-close days (day after Thanksgiving, Christmas Eve): **06:00-13:00 New York**, the NYSE
  early close itself (last M5 bar 17:55 UTC on 2025-11-28 and 2025-12-24).
- DST follows New York (2025-10-31 10-19 UTC, 2025-11-03 11-20 UTC).
- History depth on the gateway: ticks and M1 from about 2026-03, H1 from 2025.

## What qkt does today (code survey, dev 9c307771d)

| Area | Today | Gap |
|---|---|---|
| Calendar | `EXNESS:AAPL` falls to the profile default `NY_CLOSE_FX`, the FX week (`MT5DefaultProfiles.kt`) | wrong all week; no calendar has a 06:00-15:45 session |
| Calendar rules | ticker globs on the bare symbol (`SymbolCalendars.kt`) | no way to say "every symbol under Stocks" |
| Trade mode | not read | entries on disabled/close-only symbols reach the venue and are rejected there |
| DAY orders on MT5 | `TimeInForce.DAY` sent with no expiration (`MT5OrderTranslator.kt:131`); mt5-sim never expires it | DAY rests as GTC (defect, affects FX too) |
| Swaps | backtest `SwapFinancingBook` reads per-instrument points and triple day; rollover a fixed UTC hour | stocks need `swapTripleDay: FRIDAY`; rollover time to be measured live |
| Non-trade deals | deal reader keeps types 0/1 only (`MT5BrokerVenueReads.kt:27-29`) | dividend, charge, correction, tax and balance deals dropped silently |
| Sizing | per-symbol `contractSize` in every sizing form (`SizingCompiler.kt`) | none; covered by tests with contract size 100 |
| Market closed | retcode 10018 is a clean rejection | none |
| Backtest data | no stock source in the forge store | Exness bars/ticks captured through the gateway |

## Design, step 1 (stock CFDs)

1. **Session windows on exchange calendars.** A calendar name may carry a session window:
   `nyse[06:00-15:45]` is the NYSE trading-day set (holidays, special closures, early closes) with
   that New York session; on an early-close day the session ends at the earlier of the window's end
   and the exchange's early close. `nyse` alone keeps 09:30-16:00. Parsed once, at the config
   boundary; existing names are unchanged.
2. **Category rules.** A calendar rule key `category:<glob>` matches the instrument's venue category
   (MT5 `path`, e.g. `Standard\Stocks\AAPLm` has category `Stocks`). Live reads it from the symbol
   cache; backtests from `category:` in `instruments.yaml`, which `qkt instruments pull` writes. The
   Exness profile ships `category:Stocks -> nyse[06:00-15:45]`. Ticker rules still win over category
   rules, so a config can override one stock.
3. **Trade-mode guard.** The venue's trade mode becomes instrument metadata (`tradeMode`: full,
   long-only, short-only, close-only, disabled). Opening orders on close-only or disabled symbols are
   refused before the venue with a named reason, in backtest and live; closing orders pass.
4. **DAY orders expire at the session end.** A DAY order carries `expiresAt` = the end of the current
   session of its symbol's calendar; MT5 receives it as GTD and mt5-sim expires it there.
5. **Non-trade deals are booked.** Dividend, charge, correction, tax and interest deals become venue
   cost entries (new `CostKind.DIVIDEND`), attributed by position where MT5 links them, so live P&L
   and Insights include them. Unknown deal types are logged and counted, never silently dropped.
6. **Stock data.** Exness M1 bars and ticks are captured through the gateway into a qkt data root
   with `qkt data import`, instruments from `qkt instruments pull`.

## Design, step 2 (cash equities): outline

Detailed per-stage in the plan. In short: `EQUITY` product kind in qkt and VGP; an Alpaca adapter in
`qkt-venue-gateway`; corporate actions as a hub dataset applied at one boundary (split restates
quantity and price, dividend books cash; adjusted and unadjusted price views); extended hours,
`OPG`/`CLS` auction orders and halts; buying power, short availability and borrow, PDT and T+1;
SEC/FINRA fees and minimum commissions; survivorship-aware universes.

## Safety: what must not change

Every stage merges only when, on the new build against the previous one:
- the full test suite passes;
- every existing strategy we run (forward book, hg20k book, measured research strategies, attestation
  replays) backtests to **identical trades**;
- the existing attestation catalog still passes on the Exness demo.
New behaviour is reached only through new calendar names, new rule keys, new instrument fields or a
new product kind. The one deliberate change to existing behaviour is the DAY-order defect, which is
listed with its own evidence.
