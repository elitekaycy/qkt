# MT5 as the universal provider — research

**Date:** 2026-06-08
**Question:** Can we lean on the existing MT5 client to trade *all* security types
(crypto, FX, stocks, futures) — since which asset classes are reachable depends on which
broker the MT5 terminal is connected to — instead of building separate per-asset adapters?
How do brokers differ (symbol naming, suffixes, server-time offsets), does the current design
accommodate them, and can new servers be added purely from config?

**Reframes #75** (which proposed separate futures/equity adapters behind the `Broker` interface).

---

## TL;DR

- **The insight holds and the architecture is already ~80% built for it.** One MT5 client trades
  whatever the connected broker offers; qkt already models brokers as **config-driven profiles**
  with **per-broker symbol mapping** and **per-symbol instrument metadata**, and the `Broker` /
  capability / instrument layers are **asset-class agnostic**. Adding a broker that uses the same
  conventions is already near-pure config (`extends:` + gateway URL + magic).
- **Two concrete things block "any asset class, any server, from config":**
  1. **Trading calendar is hardcoded to FX** (`TradingCalendar.fxDefault()` in `MT5Broker`), and
     it's *global per broker*, not per-symbol. This is the real coupling — crypto (24/7) or
     equities (NYSE hours) traded through an MT5 broker would reconcile wrongly.
  2. **Symbol aliases + instrument overrides + capability restrictions are hardcoded** in
     `MT5DefaultProfiles`, not exposed to YAML — so a *new* broker with novel symbol names or a
     new asset class needs code, not config.
- **Deep asset-class concepts (futures roll/expiry, equity corporate actions/halts, settlement)
  are genuinely absent** — but they're a *separate, narrower* scope. The MT5-universal approach
  cleanly covers order placement + position tracking + sessions for crypto/FX/indices/metals and
  basic stocks/futures; the deep concepts are a later phase (the part of #75 worth keeping).
- **Recommendation:** make the calendar per-symbol + fully config-drive the profiles. That's the
  whole "universal MT5 provider" MVP. Defer the deep asset-class modelling.

---

## What already works (the 80%)

| Layer | File | Status |
|---|---|---|
| **Broker profile model** | `broker/mt5/MT5BrokerProfile.kt` | name, gatewayUrl, `SymbolPolicy`(suffix+aliases), `serverTzOffsetHours`, magic, `instrumentOverrides`, `capabilityRestrictions` |
| **Config-driven loading** | `broker/mt5/MT5BrokerProfileLoader.kt` | YAML `brokers:` + `extends:` inheritance + `QKT_BROKER_*` env overrides + defaults |
| **Default profiles** | `broker/mt5/MT5DefaultProfiles.kt` | Exness (`m`), ICMarkets (`.raw`), FTMO (``), Pepperstone (`.cmd`) — FX/indices/metals |
| **Symbol mapping** | `broker/mt5/MT5Symbol.kt` | bidirectional qkt↔broker via suffix + per-symbol aliases (e.g. `NAS100→USTEC`, then `+m`) |
| **Instrument metadata** | `instrument/InstrumentMeta.kt` + `MT5Broker.kt` | per-symbol contractSize/volumeStep/pointSize/digits/stopsLevel, from `/symbol_info` or overrides — asset-agnostic |
| **Broker abstraction** | `broker/Broker.kt`, `OrderTypeCapability.kt` | asset-class agnostic; `capabilitiesFor(symbol)` enables per-symbol routing |
| **Multi-venue routing** | `broker/CompositeBroker.kt` | routes by `SymbolPattern` — MT5(FX/metals) + Bybit(crypto) already coexist |

**So adding a new MT5 server today** is, for a same-convention broker:
```yaml
brokers:
  mybroker-live:
    type: mt5
    extends: exness        # inherits suffix + aliases + tz
    gateway_url: http://gateway:5001
    magic: 20001
```
Symbol naming differences are modelled (suffix `m`/`.raw`/`.cmd`; renames via aliases); server-time
offset is modelled (`serverTzOffsetHours`, used for GTD expiry); per-symbol precision/lot rules come
from `/symbol_info`. None of that needs code — *for brokers that resemble an existing default*.

## The gaps (the 20%)

### 1. Trading calendar — hardcoded FX, global per broker (the real blocker)
`MT5Broker` wires both pollers to `TradingCalendar.fxDefault()` (`MT5Broker.kt:80–92`); the pollers
**skip reconciliation when out-of-session** (`MT5PositionPoller.kt:110`, `MT5PendingOrderPoller.kt:76`),
and the tick feed checks `symbols.first()` only (`Mt5TickFeedSource`). Consequences:
- **Crypto via MT5**: FX calendar pauses pollers on weekends → weekend fills don't reconcile →
  spurious "position opened externally" on Monday.
- **Equities via MT5**: needs NYSE hours (the `NyseCalendar` exists but isn't wired per-symbol).
- A single broker offering FX **and** crypto **and** stocks needs **different calendars per symbol** —
  today the calendar is one-per-session.

The calendars already exist (`FxCalendar`, `CryptoCalendar`, `NyseCalendar` implement
`TradingCalendar`). The work is **selection**, not new calendars: pick per-symbol (or per-asset-class)
from the profile/config instead of hardcoding `fxDefault()`.

> Inconsistency worth noting: live defaults to `fxDefault()`, backtest defaults to `crypto()`
> (`Backtest.kt:31`). Per-symbol calendars fix both.

### 2. Profiles aren't fully config-driven
`SymbolPolicy.aliases`, `instrumentOverrides`, and `capabilityRestrictions` live in
`MT5DefaultProfiles` (code), not YAML. A new broker with novel symbol renames, or a new asset class,
can't be expressed in config — it needs a code change. To make "integrate a new server from config"
real, these need YAML exposure.

### 3. No crypto/futures/stocks default profiles
Defaults cover FX/indices/metals only. No crypto-capable MT5 broker profile, no per-asset-class
symbol/calendar conventions shipped.

### 4. Deep asset-class concepts absent (separate scope)
No contract expiry/roll (futures), no corporate actions/halts/pre-post-market (equities), no
settlement model. `TimeInForce` has no "contract month / active contract." These are real but
**out of scope for the universal-provider MVP** — they're the part of #75 worth keeping for later.

## How brokers differ (the practical catalogue)

- **Symbol suffix**: Exness `m`, ICMarkets `.raw`, Pepperstone `.cmd`, FTMO none; crypto brokers
  often `.x`/`.c` or none. → `SymbolPolicy.suffix` (modelled).
- **Symbol renames**: indices/commodities renamed (`NAS100→USTEC`, `UKOIL→XBRUSD`). → aliases
  (modelled, but hardcoded — needs config).
- **Server-time offset**: UTC+2 / UTC+3 etc., matters for GTD expiry + session edges. →
  `serverTzOffsetHours` (modelled).
- **Precision / lot rules**: per-symbol digits/point/volumeStep. → `/symbol_info` + overrides
  (modelled).
- **Asset classes offered**: depends entirely on the broker's MT5 server. → not catalogued today.

## Recommendation & proposed issues

The universal-MT5-provider MVP is exactly two pieces of work:

- **Issue C — per-symbol trading calendar (the blocker).** Decouple the calendar from
  `MT5Broker`'s hardcoded `fxDefault()`; select per-symbol (or per-asset-class) from the profile,
  config-driven. Also unifies the live/backtest calendar-default inconsistency. Unblocks
  crypto/stocks through MT5.
- **Issue D — fully config-driven broker profiles + asset-class tagging.** Expose `aliases`,
  `instrumentOverrides`, `capabilityRestrictions`, and a per-symbol asset-class → calendar mapping
  in YAML. Ship a couple more default profiles (incl. a crypto-capable one). Result: "add a new
  server / asset class from config, no code."
- **Reframe #75**: the order-placement/position/session layer is best served by the MT5-universal
  approach above, *not* by separate adapters. Keep #75 scoped down to the genuinely
  asset-class-specific deep concepts (futures roll, equity corporate actions) as a later phase.

Files of record: `broker/mt5/MT5BrokerProfile.kt`, `MT5BrokerProfileLoader.kt`, `MT5DefaultProfiles.kt`,
`MT5Symbol.kt`, `MT5Broker.kt` (calendar wiring 80–92), `common/*Calendar.kt`, `instrument/InstrumentMeta.kt`.
