# Phase 18 — LiveSession typed-broker dispatch

**Released:** 2026-05-10
**Version:** 0.20.0

## Summary

Phase 18 closes the gap left by Phase 17: `LiveSession` now accepts a typed broker registry, so strategies declaring `EXNESS:EURUSD` actually route orders to the configured Exness profile (MT5 today, REST tomorrow if Exness ships one) instead of falling through to `PaperBroker`. The DSL broker label is the venue identity; the protocol is configured per-profile via `type:` in `qkt.config.yaml` — venues and protocols are decoupled.

The refactor is venue-agnostic: `BrokerFactory = (EventBus, Clock, MarketPriceTracker) -> Broker`. The daemon builds the registry from MT5 profiles at startup and threads it through `StrategyHandle.RealFactory` and `PortfolioDeployer` into each `LiveSession`. Each session invokes factories with its own bus/clock/price-tracker, so per-session lifecycles stay clean (each session gets its own MT5 position poller and state-recovery instance).

Existing paper-trading workflows continue to work — empty registry or unknown DSL labels fall back to `PaperBroker`.

## What's new

- `com.qkt.app.BrokerFactory` typealias: `(EventBus, Clock, MarketPriceTracker) -> Broker`. Registry of factories keyed by DSL broker label (lowercased).
- `LiveSession.brokerFactories: Map<String, BrokerFactory>` parameter (default empty). When non-empty, `LiveSession.start()` builds a `CompositeBroker` from the strategy's declared streams, routing each broker label to its factory output. Fallback = `PaperBroker` for unrecognized labels.
- `StrategyHandle.RealFactory.brokerFactories: Map<String, BrokerFactory>` parameter (default empty). Threads through to each `LiveSession` it creates.
- `PortfolioDeployer.brokerFactories: Map<String, BrokerFactory>` parameter (default empty). Portfolio child sessions get the same registry.
- `DaemonCommand.startDaemon()` builds the registry from MT5 profiles loaded via `Config.brokers` + `MT5DefaultProfiles.all` + env vars; passes it into `RealFactory` and `PortfolioDeployer`.
- DSL broker label resolution is case-insensitive (`EXNESS:`, `exness:`, `Exness:` all resolve to profile `exness`).

## Migration from Phase 17

**No DSL changes.** Existing strategies declaring `BACKTEST:` or other labels keep working — unknown labels resolve to `PaperBroker` fallback.

**No public API breakage.** All new constructor parameters have empty-map defaults. Existing `LiveSession(...)` / `RealFactory(...)` / `PortfolioDeployer(...)` callers continue to compile and behave identically.

**Phase 17's `qkt brokers list` continues to work** — it lists profiles loaded from config; now those same profiles actually drive live dispatch.

## Usage cookbook

### Run a strategy on Exness

```yaml
# ./qkt.config.yaml
brokers:
  exness:
    type: mt5
    gateway_url: http://localhost:5001
```

```qkt
# ./momentum.qkt
STRATEGY momentum VERSION 1
SYMBOLS
    eur = EXNESS:EURUSD EVERY 1m
RULES
    WHEN ema(eur, 9) crosses ABOVE ema(eur, 21)
    THEN BUY eur SIZING 0.1 BRACKET STOP_LOSS BY 50 PCT TAKE_PROFIT BY 100 PCT
```

```bash
$ qkt daemon &                                  # loads exness profile
$ qkt deploy momentum.qkt
```

The strategy's BUY signal routes to `MT5Broker(profile=exness)` → `mt5-gateway` at `localhost:5001` → MT5 → Exness fills it.

### Mixed-venue strategy (MT5 + paper)

```qkt
SYMBOLS
    eur = EXNESS:EURUSD EVERY 1m
    btc = BACKTEST:BTCUSDT EVERY 1m
```

`eur` orders → MT5; `btc` orders → fallback `PaperBroker` (since no `backtest` profile is configured). Useful for paper-testing one leg while live-trading another.

### Multiple Exness accounts

```yaml
brokers:
  exness-personal:
    type: mt5
    extends: exness
    gateway_url: http://localhost:5005
    magic: 10005
  exness-corporate:
    type: mt5
    extends: exness
    gateway_url: http://localhost:5006
    magic: 10006
```

Strategy uses `EXNESS-PERSONAL:EURUSD` or `EXNESS-CORPORATE:XAUUSD`. Each profile gets its own MT5Broker per session, with its own poller scoped by magic.

## Testing patterns

`LiveSession` continues to default to `PaperBroker` when `brokerFactories` is empty — so existing tests don't change. New tests opting into typed dispatch pass a factory map:

```kotlin
val factories: Map<String, BrokerFactory> = mapOf(
    "exness" to { bus, clock, _ -> MT5Broker(profile, bus, clock) },
)
LiveSession(strategies, source, symbols, brokerFactories = factories).start()
```

For end-to-end testing, point the MT5 profile at a `MockWebServer` and assert the gateway receives the translated order.

## Known limitations

- **Cross-broker same-symbol routing still deferred.** Carried from Phase 17. Two profiles handling the same symbol in one strategy would conflate at `PositionTracker`.
- **Per-session MT5Broker construction.** Each `LiveSession` instantiates its own `MT5Broker` (with its own state recovery + poller) for every profile its strategies use. CPU cost is bounded but means N pollers per N sessions per profile. Future enhancement: shared broker instances with session-scoped event filtering.
- **No `--strict-brokers` flag yet.** Unknown broker labels silently fall back to `PaperBroker`. Strict mode (fail on unknown labels) is a follow-on for prod safety.
- **Bybit not yet folded into typed registry.** Existing Bybit broker keeps its own wiring. Migration to `type: bybit` in `qkt.config.yaml` is a follow-on.
- **Live-broker tick source** still TradingView (Phase 7c). MT5 handles execution only; the deferred tick-feed accuracy audit will quantify TV-vs-MT5 price drift.

## References

- Phase 17 spec/plan/changelog (the broker; this phase wires it in): [`docs/phases/phase-17.md`](phase-17.md)
- Phase 14 portfolio daemon (multi-broker context): [`docs/phases/phase-14.md`](phase-14.md)
- `LiveSession`: `src/main/kotlin/com/qkt/app/LiveSession.kt`
- `BrokerFactory`: `src/main/kotlin/com/qkt/app/BrokerFactory.kt`
- `DaemonCommand` startup wiring: `src/main/kotlin/com/qkt/cli/DaemonCommand.kt`
