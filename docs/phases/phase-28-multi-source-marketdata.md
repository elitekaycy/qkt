# Phase 28 — Multi-source market data

**Status:** Shipped on `main`.
**Spec:** [`../superpowers/specs/2026-05-13-phase28-multi-source-marketdata-design.md`](../superpowers/specs/2026-05-13-phase28-multi-source-marketdata-design.md)
**Plan:** [`../superpowers/plans/2026-05-13-phase28-multi-source-marketdata.md`](../superpowers/plans/2026-05-13-phase28-multi-source-marketdata.md)

---

## Summary

Phase 28 lets a single qkt daemon route per-symbol-prefix to multiple market data backends. Before Phase 28, every live strategy read ticks and bars from TradingView regardless of which broker the strategy executed against — a feed/execution mismatch that ~$0.21/oz on XAUUSD (within the broker's bid-ask spread, but with ~89ms latency disadvantage; see [`docs/parity/parity-ticks-xauusd.md`](../parity/parity-ticks-xauusd.md)).

After Phase 28, MT5 broker profiles drive **both** execution and data. Bybit public WebSocket data is wired in for spot and linear (auth not required for public endpoints). TradingView remains the fallback for any symbol prefix not claimed by a registered source.

**Zero DSL change.** A strategy declaring `EXNESS:XAUUSD EVERY 5m` keeps working unchanged; what changes is which feed sits behind that symbol on a live deployment.

---

## What's new

### `MarketSource` implementations

- `com.qkt.marketdata.live.mt5.Mt5MarketSource` — `MarketSource` over the `mt5-gateway` HTTP endpoints. Supports `LIVE_TICKS` (HTTP poller, 50ms default) and `BARS` (range fetch). Reuses an existing `MT5BrokerProfile` for symbol translation (suffix + aliases).
- `com.qkt.marketdata.live.bybit.BybitSpotMarketSource` — Bybit `/v5/public/spot` WebSocket (`tickers.<symbol>` for bid/ask, `publicTrade.<symbol>` for last-price) + REST kline. Public, no auth.
- `com.qkt.marketdata.live.bybit.BybitLinearMarketSource` — same shape, `/v5/public/linear`. For perp futures.

### Internal helpers

- `com.qkt.marketdata.live.mt5.Mt5TickFeedSource` — `LiveTickSource` wrapper that polls each subscribed symbol round-robin, dedupes by `time_msc` per symbol, sleeps `pollIntervalMs` between rounds.
- `com.qkt.marketdata.live.mt5.Mt5BarFetcher` — `TimeWindow` → MT5 timeframe + `TimeRange` → naive ISO start/end. Wraps `Mt5DataClient.fetchBarsByRange`.
- `com.qkt.marketdata.live.bybit.BybitPublicFrame` — sealed class for Bybit public WS messages (`Tickers`, `Trade`, `SubscribeAck`, `Unknown`).
- `com.qkt.marketdata.live.bybit.BybitPublicWsClient` — composes `tickers.<symbol>` (bid/ask state) and `publicTrade.<symbol>` (last-trade prints) into per-symbol `Tick` emissions. Reconnect-safe via the `hasDisconnected` pattern: `onConnected()` only re-sends subscribe commands on actual reconnect, never on the initial WS open.
- `com.qkt.marketdata.live.bybit.BybitPublicWs` — OkHttp WebSocket impl of `BybitPublicWsLike`. Heartbeat via OkHttp `pingInterval(20s)`.
- `com.qkt.marketdata.live.bybit.BybitKlineClient` — REST `/v5/market/kline` with `category` param. Bybit returns newest-first; reversed to ascending. Pagination for ranges > 1000 candles.

### Wiring

- `com.qkt.cli.MarketSourceFactory.composite(mt5Profiles, fallbackProvider)` — builds the production `CompositeMarketSource` shared by `qkt daemon` and `qkt run`. Returns a closure that yields the single composite on every call.
- `DaemonCommand.sourceFactory` and `RunCommand.sourceFactory` are now nullable. `null` (production default) triggers the composite construction; tests pass an explicit factory.

### Symbol routing

| Prefix | Source | Wire symbol |
| --- | --- | --- |
| `EXNESS:` | `Mt5MarketSource(profile=exness)` | strip prefix + apply `symbolSuffix` (e.g. `XAUUSDm`) |
| `LATCH:` | `Mt5MarketSource(profile=latch)` | strip prefix + apply `symbolSuffix` (typically empty) |
| `BYBIT_SPOT:` | `BybitSpotMarketSource` | strip prefix (`BTCUSDT` → `BTCUSDT`) |
| `BYBIT_PERP:` | `BybitLinearMarketSource` | strip prefix |
| anything else | `TradingViewMarketSource` (fallback) | passed through |

`<NAME>:` is `profile.name.uppercase()+:`. Adding a new MT5 profile named `darwinex` to `qkt.config.yaml` automatically registers `DARWINEX:` routing.

---

## Migration from Phase 27

**⚠️ Behavior change for live deployments with an `exness` MT5 profile:**

Before Phase 28, a strategy with `gold = EXNESS:XAUUSD EVERY 5m` **silently read ticks from TradingView** — `EXNESS:` was just a label, no source matched the prefix, so it fell through to TV. After Phase 28, the same strategy on a daemon with `brokers.exness` configured **reads from the MT5 gateway**.

For most operators, this is the intended outcome (execution and data agree). If you specifically want TV-for-XAUUSD on a daemon that also executes on Exness, either:

- Rename the strategy stream: `gold = OANDA:XAUUSD EVERY 5m`. The composite has no `OANDA:` route, so it falls through to TV.
- Or remove the `exness` profile from `qkt.config.yaml` (also disables execution).

**No DSL/config schema migration:** existing `qkt.config.yaml` is unchanged. The `brokers:` block already encodes everything the data side needs.

**No breaking API changes** in production code. The `MarketSource` interface, `CompositeMarketSource`, and `MarketSourceCapability` are unchanged. Tests that construct `DaemonCommand` or `RunCommand` with an explicit `sourceFactory` continue to work; only the type of the parameter changed from `(List<String>) -> MarketSource = ::defaultTradingViewSource` to `((List<String>) -> MarketSource)? = null`. Trailing-lambda call sites are source-compatible.

---

## Usage cookbook

### 1. Strategy on Exness XAUUSD (read MT5 + execute MT5)

`qkt.config.yaml`:
```yaml
brokers:
  exness:
    type: mt5
    gateway_url: ${MT5_HEDGE_URL:-http://localhost:5002}
    symbol_suffix: m
    magic: 123456
```

`strategy.qkt`:
```
STRATEGY hedge_straddle {
  STREAMS {
    gold = EXNESS:XAUUSD EVERY 5m
  }
  RULES {
    WHEN gold.close > NOW.session_open THEN
      BUY gold SIZING 0.20 ORDER_TYPE = STOP AT gold.close + 5
        BRACKET { STOP LOSS BY 18, TAKE PROFIT BY 15 }
  }
}
```

Boot: `qkt daemon --config qkt.config.yaml`. The daemon's composite routes `EXNESS:XAUUSD` to `Mt5MarketSource(exness)`, which polls `http://localhost:5002/symbol_info_tick/XAUUSDm` (note the suffix). Order submission also goes through the Exness MT5 broker.

### 2. Strategy on Bybit spot BTCUSDT (public WS data + Bybit broker execution)

`qkt.config.yaml`:
```yaml
brokers:
  bybit_spot:
    type: bybit
    category: spot
    api_key: ${BYBIT_API_KEY}
    api_secret: ${BYBIT_API_SECRET}
```

`strategy.qkt`:
```
STRATEGY btc_breakout {
  STREAMS {
    btc = BYBIT_SPOT:BTCUSDT EVERY 1m
  }
  RULES {
    WHEN btc.close > NOW.rolling_high(20) THEN
      BUY btc SIZING 0.001 ORDER_TYPE = MARKET
        BRACKET { STOP LOSS BY 50, TAKE PROFIT BY 200 }
  }
}
```

The `BYBIT_SPOT:` prefix is registered unconditionally at boot (public WS, no auth needed for data). The Bybit execution profile is separate and only needed for order submission.

### 3. Strategy on Bybit perp ETHUSDT

`strategy.qkt`:
```
STRATEGY eth_scalper {
  STREAMS {
    eth = BYBIT_PERP:ETHUSDT EVERY 5m
  }
  ...
}
```

Routes to `BybitLinearMarketSource` (`/v5/public/linear`). Same shape as spot but uses Bybit's linear category for both WS and REST.

### 4. Two MT5 accounts in one daemon

```yaml
brokers:
  latch:
    type: mt5
    gateway_url: http://173.249.58.247:5003
    symbol_suffix: ""
    magic: 100
  hedge:
    type: mt5
    gateway_url: http://173.249.58.247:5002
    symbol_suffix: m
    magic: 200
```

Two MT5 sources registered: `LATCH:` and `HEDGE:`. Distinct strategies can address each:
```
STRATEGY latch_ema {
  STREAMS { gold = LATCH:XAUUSD EVERY 5m }
  ...
}

STRATEGY hedge_straddle {
  STREAMS { gold = HEDGE:XAUUSD EVERY 5m }
  ...
}
```

`LATCH:XAUUSD` polls port 5003 with no suffix; `HEDGE:XAUUSD` polls port 5002 with `m` suffix. The two strategies run in the same daemon, share the same composite, but read from independent feeds.

### 5. Mixing prefixes in one portfolio

```
PORTFOLIO multi_asset {
  CHILDREN {
    btc_play = STRATEGY ../strategies/btc_breakout.qkt FROM "btc_breakout.qkt"
    gold_hedge = STRATEGY ../strategies/hedge_straddle.qkt FROM "hedge_straddle.qkt"
  }
}
```

The portfolio's children inherit the daemon's composite. `btc_play` reads from Bybit-WS; `gold_hedge` reads from MT5. Both run concurrently; the `FanInTickFeed` in `CompositeMarketSource` interleaves ticks from both sources without either blocking the other.

### 6. Fallback to TV for symbols outside known prefixes

A strategy with `spy = SPY EVERY 5m` or `ndx = NASDAQ:NDX EVERY 5m` falls through to `TradingViewMarketSource`. No config change needed — TV is the default fallback.

---

## Testing patterns

### Mt5MarketSource — MockWebServer for HTTP

```kotlin
val server = MockWebServer().apply { start() }
server.enqueue(
    MockResponse().setBody(
        """{"bid":4700.0,"ask":4700.3,"last":4700.1,"flags":6,
            "time":1778662794,"time_msc":1778662794911,
            "volume":0,"volume_real":0}""",
    ),
)
val profile = MT5BrokerProfile(
    name = "exness",
    gatewayUrl = server.url("/").toString().trimEnd('/'),
    symbolPolicy = SymbolPolicy(suffix = "m"),
    magic = 123,
    pollIntervalMs = 5L,
)
val source = Mt5MarketSource(profile)
val feed = source.liveTicks(listOf("EXNESS:XAUUSD"))
val tick = feed.next() // blocks until first poll returns
```

### BybitPublicWsClient — FakeBybitWebSocket for frame replay

```kotlin
val ws = FakeBybitWebSocket()
val client = BybitPublicWsClient(ws, clock = FixedClock(0L))
val captured = mutableListOf<Tick>()
client.subscribe(listOf("BTCUSDT"), onTick = { captured.add(it) }, onDisconnect = {})

ws.deliver(
    """{"topic":"tickers.BTCUSDT","type":"snapshot",
        "data":{"symbol":"BTCUSDT","bid1Price":"60000.0","ask1Price":"60000.5","lastPrice":"60000.2"}}""",
)
// captured[0] now has bid/ask/last set.
```

`FakeBybitWebSocket.simulateDisconnect(reason)` and `simulateConnect()` exercise the reconnect path. Assertion to add to any new `BybitPublicWsClient` test: **no double-subscribe on initial connect** — replay a `simulateConnect()` without a prior disconnect, assert `sentTexts.size` did not grow. (Catches the bug fixed in `TradingViewQuoteSession` in commit `3f55288`.)

### Composite routing — DaemonCommandSourceWiringTest

`MarketSourceFactory.composite(mt5Profiles, fallbackProvider)` is the unit under test. Passing a `StubFallback` (a `MarketSource` whose `supports()` returns `true` for anything) avoids opening a real TradingView WebSocket in CI.

---

## Known limitations

- **MT5 ticks are HTTP-polled, not streamed.** The minimum effective latency is `pollIntervalMs` (default 50ms). For sub-50ms reaction time, the `mt5-gateway` would need to expose a WebSocket endpoint; today only REST exists. Strategies that fire on tick-level price breaks (anything sub-second) will lag.
- **No auto-reconnect for Bybit WS at the data-source layer.** Disconnects surface via `BybitPublicListener.onDisconnected`, but the WS does not retry. A `com.qkt.common.ReconnectSupervisor` extraction (deduping with the Bybit broker's reconnect logic) is deferred. Matches the existing TradingView behavior.
- **One WS per strategy per Bybit prefix.** If N strategies all subscribe to `BYBIT_SPOT:BTCUSDT`, N WebSocket connections are opened. Connection pooling is out of scope.
- **Public data only on Bybit.** Account-scoped events (positions, executions) flow through the existing broker code path (`BybitSpotBroker` / `BybitLinearBroker`), not through `MarketSource`.
- **No historical tick stream from Bybit.** `MarketSourceCapability.TICKS` (range-based tick replay) is not implemented for any Bybit source. Only `LIVE_TICKS` and `BARS`.
- **No DSL `FROM` clause.** A strategy cannot override the per-symbol source at the DSL level — routing is operator-config only. Workaround: distinguish via prefix (`OANDA:XAUUSD` vs `EXNESS:XAUUSD`).

---

## 0.26.0 follow-up: symbol convention normalization

**Released:** `v0.26.0` (2026-05-13).
**Spec:** [`../superpowers/specs/2026-05-13-symbol-convention-normalization-design.md`](../superpowers/specs/2026-05-13-symbol-convention-normalization-design.md)
**Plan:** [`../superpowers/plans/2026-05-13-symbol-convention-normalization.md`](../superpowers/plans/2026-05-13-symbol-convention-normalization.md)
**PR:** [#10](https://github.com/elitekaycy/qkt/pull/10)

### What was broken

Phase 28 introduced `CompositeMarketSource` with prefix-based routing (`EXNESS:` → MT5, `BYBIT_SPOT:` → Bybit, fallback → TradingView). The composite's contract required every call to receive `BROKER:SYMBOL` strings. But the engine call sites — `RunCommand`, `StrategyHandle`, `PortfolioDeployer` — extracted only `StreamDecl.symbol` (the bare ticker) and passed that to `source.liveTicks(symbols)`. `TradingViewMarketSource.supports()` rejected the bare form with a regex error, so a fresh-install deploy of any TV-routable strategy died at `LiveSession.kt:248`:

```
qkt: error: deploy failed (400):
  {"error":"TradingView symbols must match EXCHANGE:SYMBOL form: [XAUUSD]"}
```

CI didn't catch it because every test source overrode `supports = true` and ignored its `symbols` argument. The prod regex was never exercised from the engine side.

`MT5Broker.getOpenPositions` emitted bare keys (`XAUUSD`) while `BybitBroker.getOpenPositions` emitted prefixed keys (`BYBIT_LINEAR:BTCUSDT`). Mixed inventory would have silently collided on the global `PositionTracker`.

### What changed

The qkt symbol (`BROKER:BARE`) is now the single in-engine identifier. Sources, brokers, and strategies all speak it; wire forms (`XAUUSDm`, etc.) live only inside `Mt5MarketSource` and `MT5OrderTranslator` at the venue boundary.

- `StreamDecl.qktSymbol` and `HubKey.qktSymbol` getters return `"$broker:$symbol"`.
- `AstCompiler`, `ExprCompiler`, `ActionCompiler`, `IndicatorBinding`, `SizingCompiler`, and `CandleHub.feed` all key positions and compare candle symbols by `qktSymbol`.
- `RunCommand`, `StrategyHandle`, `PortfolioDeployer`, `PortfolioSupervisor` pass the prefixed form through to `LiveSession` and the source factory.
- `LiveSession` and `Backtest` build the broker-routing prefix set from `qktSymbol`, so `CompositeBroker` finds a route for every emitted signal.
- `Mt5MarketSource.liveTicks` builds a `wire→qkt` map and threads it through `Mt5TickFeedSource`; the feed polls by wire symbol and emits ticks with the qkt symbol.
- `MT5Broker.getOpenPositions`, `MT5Broker.onPendingPositionOpened`, and `MT5PositionPoller` prefix their emitted keys with `profile.name.uppercase()` to match Bybit's existing convention.
- `MT5OrderTranslator` strips the prefix at the wire boundary via a `bare()` helper so `MT5Symbol.toBroker(...)` and `profile.instrumentOverrides[...]` continue to receive the bare form they already expected.
- `ActionCompiler.compileCancelAll` emits `Signal.CancelPendingForSymbol` with the qkt-prefixed symbol so the broker layer can route it.

### New invariant

> **In-engine symbol = `BROKER:BARE`.** Anything else (wire form, bare alias) is local to the source/broker translator at the venue boundary.

The lock: `StrategyHandleTradingViewDeployTest` deploys a strategy with `OANDA:EURUSD` through `StrategyHandle.RealFactory` wired to a **real** `TradingViewMarketSource(FakeTradingViewWebSocket())`. The fake-WS-only stops the real network call; the regex check still runs. Any future engine-side regression that hands a bare symbol to a source will surface at this test before reaching prod.

### Migration (breaking)

State files written by `0.25.0` or earlier are keyed by bare symbols. After upgrading to `0.26.0`, broker emits arrive prefixed and won't reconcile against the stored bare keys.

**Operator action:** `rm -rf state/` between versions before `docker compose up -d`. The engine will reconcile fresh against the broker's open positions on next deploy (Phase 29's normal reconcile path). Acceptable because there are no live production users yet; documented in the spec as the explicit migration path.

### File-level summary

| Layer | Files |
|---|---|
| AST / DSL surface | `StrategyAst.kt`, `HubKey.kt` |
| DSL compile | `AstCompiler.kt`, `ExprCompiler.kt`, `ActionCompiler.kt`, `IndicatorBinding.kt`, `SizingCompiler.kt`, `CandleHub.kt` |
| CLI / wiring | `RunCommand.kt`, `StrategyHandle.kt`, `PortfolioDeployer.kt`, `PortfolioSupervisor.kt` |
| Engine session | `LiveSession.kt`, `Backtest.kt` |
| MT5 source | `Mt5MarketSource.kt`, `Mt5TickFeedSource.kt` |
| MT5 broker | `MT5Broker.kt`, `MT5PositionPoller.kt`, `MT5OrderTranslator.kt` |
| Test (regression-catcher) | `StrategyHandleTradingViewDeployTest.kt`, `tradingview_strategy.qkt` |
| Test fixture sweep | ~50 DSL compile / broker / source tests now feed `BROKER:SYMBOL` ticks |

---

## References

- Spec: [`../superpowers/specs/2026-05-13-phase28-multi-source-marketdata-design.md`](../superpowers/specs/2026-05-13-phase28-multi-source-marketdata-design.md)
- Plan: [`../superpowers/plans/2026-05-13-phase28-multi-source-marketdata.md`](../superpowers/plans/2026-05-13-phase28-multi-source-marketdata.md)
- Parity validation (driving motivation): [`../parity/parity-bars-xauusd-m5.md`](../parity/parity-bars-xauusd-m5.md), [`../parity/parity-ticks-xauusd.md`](../parity/parity-ticks-xauusd.md)
- TradingView QuoteSession reconnect-safety fix (pattern reused for Bybit): commit `3f55288`
- Composite routing logic: `src/main/kotlin/com/qkt/marketdata/source/CompositeMarketSource.kt`
- MT5 broker code path (data side reuses `MT5BrokerProfile` + `MT5Symbol`): `src/main/kotlin/com/qkt/broker/mt5/`
