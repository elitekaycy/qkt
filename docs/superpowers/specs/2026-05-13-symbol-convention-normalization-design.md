# Symbol Convention Normalization — Design Spec

## Phase
Bug-driven refactor. Reveals a design flaw, so per qkt skill §6 it gets a spec.
Targets the same release as the surfacing fix.

## Goal
Make the **qkt symbol** (`BROKER:BARE`, e.g. `EXNESS:XAUUSD`) the single
in-engine identifier. Sources, brokers, and engine internals all speak it.
Wire forms (`XAUUSDm`, etc.) live only at the venue boundary.

## Why now
Local CLI verification on a fresh install fails:

```
$ qkt deploy live-smoke.qkt --as live-smoke
qkt: error: deploy failed (400):
  {"error":"TradingView symbols must match EXCHANGE:SYMBOL form: [XAUUSD]"}
```

Two competing conventions coexist:

- **Bare** (`XAUUSD`): `RunCommand`/`StrategyHandle` extract `it.symbol`,
  `AstCompiler.evaluate` reads it for position lookups, `MT5Broker.getOpenPositions`
  emits it, `Mt5TickFeedSource` emits it.
- **Prefixed** (`EXNESS:XAUUSD`): `MarketSource.supports/liveTicks/bars`
  contracts; `CompositeMarketSource` prefix routing; `BybitBroker.getOpenPositions`
  emits prefixed; `BybitSymbol.kt:3` documents the convention as prefixed.

The two meet at `LiveSession.kt:248` (`source.liveTicks(symbols)`) — engine
passes bare, source requires prefixed. Tests don't catch it because every
test source sets `supports = true` and ignores its `symbols` argument.

The Phase 28 multi-source design baked in prefix-based routing; MT5 broker
emit and the call sites are the laggards. This refactor catches them up.

## Architecture

### Single in-engine identifier
All in-process symbol references are the qkt symbol form: `BROKER:BARE`.

```
DSL              "gold = EXNESS:XAUUSD"
   │
   ▼
Parser           StreamDecl(alias="gold", broker="EXNESS", symbol="XAUUSD")
                                         └─── qktSymbol = "EXNESS:XAUUSD"
   │
   ▼
Engine           positions["EXNESS:XAUUSD"], tick.symbol == "EXNESS:XAUUSD"
   │
   ▼
Source boundary  prefix routing → Mt5MarketSource(profile=exness)
                                  strips "EXNESS:", applies suffix → "XAUUSDm"
   │
   ▼
Wire             "XAUUSDm" / "BTCUSDT" / TV websocket subscription
```

The DSL surface is unchanged: `gold = EXNESS:XAUUSD` and `POSITION.gold` work
identically. Only the engine's internal lookup key changes.

### Sources emit qkt symbols
- `TradingViewMarketSource`: already emits the subscription name, which is
  already the qkt-prefixed form (`OANDA:XAUUSD`). No change.
- `BybitSpotMarketSource` / `BybitLinearMarketSource`: already emit prefixed.
  No change.
- `Mt5MarketSource`: currently emits the wire form (`XAUUSDm`) via
  `Mt5TickFeedSource`. Change to emit qkt-prefixed (`EXNESS:XAUUSD`) by
  threading a `wireSymbol → qktSymbol` map into the feed source.

### Brokers emit qkt symbols
- `BybitBroker.getOpenPositions`: already prefixed. No change.
- `MT5Broker.getOpenPositions` and `MT5PositionPoller`: currently emit bare.
  Change to wrap with `profile.name.uppercase()` prefix.

### Strategy compiler reads positions by qkt symbol
`AstCompiler.evaluate` uses `streams[alias]!!.symbol` (bare) for position
lookups. Switch to `streams[alias]!!.qktSymbol` (prefixed). Add a derived
`qktSymbol` getter on `HubKey` and `StreamDecl` so the join lives in one place.

## Scope

### Files modified
| File | Change |
|---|---|
| `dsl/ast/StrategyAst.kt` | Add `StreamDecl.qktSymbol` derived getter |
| `dsl/compile/HubKey.kt` | Add `HubKey.qktSymbol` derived getter |
| `dsl/compile/AstCompiler.kt` | Use `qktSymbol` for position-tracker key (line 207, 211, sibling sites) |
| `cli/RunCommand.kt` | Pass prefixed symbols as the `symbols` argument to `LiveSession` |
| `cli/daemon/StrategyHandle.kt` | Same |
| `cli/daemon/portfolio/PortfolioDeployer.kt` | Same |
| `marketdata/live/mt5/Mt5MarketSource.kt` | Build `wire→qkt` map, pass to feed source |
| `marketdata/live/mt5/Mt5TickFeedSource.kt` | Accept the map; emit qkt symbol while polling wire |
| `broker/mt5/MT5Broker.kt` | Prefix the keys in `getOpenPositions` |
| `broker/mt5/MT5PositionPoller.kt` | Emit qkt symbol on position events |

### Tests modified / added
| File | Change |
|---|---|
| `marketdata/live/tv/TradingViewMarketSourceTest.kt` | New test: assert `supports("XAUUSD")` is **false** — locks the regex |
| `cli/daemon/StrategyHandleDeployRoutingTest.kt` (new) | Deploy a strategy with `OANDA:XAUUSD` through `StrategyHandle.RealFactory` using **the real `TradingViewMarketSource.supports()` regex** as a route filter — fails today, passes after the fix |
| `parity/MT5DaemonE2ETest.kt` | Update fixture: tick `symbol = "EXNESS:EURUSD"`; assert position keyed under `EXNESS:EURUSD` after fill |
| `broker/mt5/MT5BrokerTest.kt` | Assert `getOpenPositions` keys are prefixed |

The first new test is the **regression-catcher**: the absence of a
real-source test is precisely why this bug shipped.

## Migration
**None.** Per CLAUDE.md "no backwards compatibility cruft." Phase 29 state
files keyed by bare symbol will not reconcile on the next deploy; operators
wipe `state/` before restart. Acceptable: no live production users yet.

## Testing strategy
- **Unit:** the regex-locking test on `TradingViewMarketSource.supports`,
  plus broker emit-form assertions.
- **Integration:** `MT5DaemonE2ETest` exercises the full route with the
  updated symbol form.
- **Manual smoke:** `qkt daemon` + `qkt deploy live-smoke.qkt --as live-smoke`
  using `OANDA:XAUUSD` must reach `running`.

## Out of scope
- Persisted Phase 29 state migration (wipe `state/` between versions).
- DSL surface or syntax changes.
- Renaming `StreamDecl.broker` to anything else.
- Bybit wire-symbol policy changes (already correct).
- Adding a `SymbolRouter` abstraction (rejected: hides the convention).

## Risk
**Low–medium.** Touches the live-deploy path, but the DSL surface is
unchanged and the failure mode (regex rejection at deploy time) is
loud — there's no quiet incorrect-behavior path. Mitigated by the new
regression test against the real source regex.

## References
- Surfacing context: qkt-prod local CLI verification on 2026-05-13.
- Phase 28 spec: `docs/superpowers/specs/2026-05-13-phase28-multi-source-marketdata-design.md`
  established prefix routing.
- `BybitSymbol.kt:3` and `Mt5MarketSource.kt:22-24` already document the
  prefixed convention; this refactor closes the gap on the bare side.
