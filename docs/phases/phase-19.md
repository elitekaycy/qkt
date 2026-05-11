# Phase 19 — Pre-live confidence pack

**Released:** 2026-05-10
**Version:** 0.21.0

## Summary

Phase 19 ships the operational hardening needed before putting investor money on the line. Four deliverables: an end-to-end MT5 smoke test that exercises the full daemon → strategy → broker → gateway path, a `qkt audit-ticks` CLI tool for quantifying TradingView vs MT5 price drift, a standardized logging guide that documents every MDC key and operator convention, and a memory leak audit that found + fixed two real leaks in `ObservabilityServer` (executor never shut down) and `OrderManager` (risk map grew unbounded). No major architectural changes — this phase is the audit pass before live deployment.

## What's new

### Tests

- `src/test/kotlin/com/qkt/parity/MT5DaemonE2ETest.kt` — end-to-end smoke test: spins up `StrategyHandle.RealFactory` with an MT5 profile pointing at `MockWebServer`; deploys a strategy that says `EXNESS:EURUSD`; ticks drive the BUY rule; asserts the gateway received an order with the translated symbol `EURUSDm`, magic `10001`, type `BUY`, volume `0.1`. Validates Phase 17 (broker) + Phase 18 (typed dispatch) work end-to-end via the daemon.
- `src/test/resources/parity/mt5_e2e_strategy.qkt` — fixture strategy used by the smoke test.

### CLI

- `qkt audit-ticks` — new subcommand that captures TV + MT5 ticks side-by-side for `--duration` seconds, reports mean/median/p95/max absolute price difference. Use before live deployment to confirm TV prices your strategies see track MT5 prices your orders fill at within an acceptable bound.
  ```
  qkt audit-ticks --symbol EURUSD --duration 60 --mt5-profile exness
  qkt audit-ticks --symbol EURUSD --duration 60 --mt5-profile exness --json
  ```
- New `MT5Client.getTick(brokerSymbol)` method that powers the audit. Returns `MT5Tick(bid, ask, time)` with TZ-normalized timestamp.

### Docs

- `docs/logging.md` — comprehensive logging guide: MDC keys, console + file patterns, logback config overrides, DSL `LOG` action conventions, troubleshooting.
- `docs/memory-audit.md` — code review findings for long-running session components. Two fixes shipped, two future enhancements documented.

### Bug fixes

- `ObservabilityServer.close()` now shuts down its fixed-thread-pool executor (4 threads were leaking per close cycle). Threads also marked daemon so they don't block JVM exit if cleanup is missed.
- `OrderManager.riskUsdFor()` now consumes-and-removes the entry instead of pure-read. The single caller (Backtest's onFilled lambda) reads exactly once per trade; live sessions follow the same pattern. Prevents unbounded growth of `riskByClientOrderId` over multi-day sessions.

## Migration from Phase 18

**No DSL changes. No Broker interface changes.** All changes are operational.

**`OrderManager.riskUsdFor()` semantic change.** Previously a pure read; now consume-and-remove. Existing single caller (`Backtest`) is unaffected — it reads once per trade in the `onFilled` lambda. New callers must respect the contract: read each `clientOrderId` at most once.

**`ObservabilityServer` thread name change.** Threads now named `qkt-observability-<port>` instead of pool defaults. Logging filters keying on thread name need an update if any rely on the old pool naming.

## Usage cookbook

### Run the E2E smoke test

```bash
$ ./gradlew test --tests com.qkt.parity.MT5DaemonE2ETest
```

Validates Phase 17 + 18 wiring. Run after any change to broker dispatch, MT5 client, or daemon factory.

### Audit TV vs MT5 drift

Before going live with a strategy that depends on tight stops:

```bash
$ qkt audit-ticks --symbol EURUSD --duration 300 --mt5-profile exness
qkt audit-ticks: symbol=EURUSD duration=300s profile=exness poll=250ms
samples:        1180
mean abs diff:  0.000034
median abs diff:0.000028
p95 abs diff:   0.000091
max abs diff:   0.000412
```

Interpret:
- `mean` / `median` < typical spread → strategies depending on TV-driven decisions track MT5 fills closely.
- `p95` > typical SL buffer → tighten stops only if you accept that 5% of decisions could land on the wrong side of the broker's price.
- `max` is informational; large outliers usually correlate with low-liquidity moments.

### Reading the logging guide

Strategy authors writing a `LOG` action should skim `docs/logging.md` for the MDC conventions and the level guidance (INFO/WARN/ERROR/DEBUG when). Operators configuring logback for log shipping should read the override section.

## Testing patterns

E2E test pattern shows how to wire the daemon's broker registry against a fake gateway:

```kotlin
val server = MockWebServer().apply { start() }
server.enqueue(MockResponse().setBody("[]"))                            // recovery
server.enqueue(MockResponse().setBody("[]"))                            // poller seed
server.enqueue(MockResponse().setBody("""{"result":{"retcode":10009,...}}"""))  // order fill

val profile = MT5DefaultProfiles.exness.copy(gatewayUrl = server.url("/").toString().trimEnd('/'))
val factories: Map<String, BrokerFactory> = mapOf("exness" to { bus, clock, _ -> MT5Broker(profile, bus, clock) })
val factory = StrategyHandle.RealFactory(stateDir, marketSourceProvider, brokerFactories = factories)
val handle = factory.create("smoke", strategyFile)
// ... drive ticks, assert order received
```

Reusable for any future MT5 integration test.

## Known limitations

- **`qkt audit-ticks` requires both feeds live.** TV connection happens via `TradingViewMarketSource.connect()` which needs network; MT5 needs a running gateway. Offline / CI use is not supported.
- **`audit-ticks` does not estimate timing skew.** Reports price drift only. Future enhancement: capture both feeds with high-resolution timestamps and report median TV-leads-MT5 delay.
- **MT5 broker shutdown not yet wired into `LiveSession.stop()`.** Documented in `docs/memory-audit.md` as a Watchout. One MT5 poller thread leaks per closed-but-not-shutdown session. Acceptable for v1; future enhancement defines a `BrokerLifecycle` marker.
- **`CandleHub` has no `unregister`.** Documented in `docs/memory-audit.md`. Per-session hubs go out of scope so there's no per-tick growth, but a shared daemon-level hub would accumulate listeners over many sessions. Future enhancement.

## References

- Spec/plan: skipped per user direction — refactor scope was clear, executed task-by-task.
- Phase 17 (MT5 broker): [`docs/phases/phase-17.md`](phase-17.md)
- Phase 18 (LiveSession typed dispatch): [`docs/phases/phase-18.md`](phase-18.md)
- Logging guide: [`docs/logging.md`](../logging.md)
- Memory audit: [`docs/memory-audit.md`](../memory-audit.md)
