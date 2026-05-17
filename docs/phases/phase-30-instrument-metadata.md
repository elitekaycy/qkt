# Phase 30 — Instrument metadata

## Summary

Phase 30 closes GAP 4 from the strategy port: the engine gains a first-class `InstrumentMeta` primitive that carries `contractSize`, `volumeStep`, `volumeMin`, `pointSize`, `digits`, and `tradeStopsLevelPoints`. SIZING math, PaperBroker PnL math, and MT5Broker pre-placement enforcement all read from the same `InstrumentRegistry` — so the `/100` correction the hedge-straddle strategy carried as a workaround is no longer needed, and `Backtest + PaperBroker` produces a PnL number that's directly comparable to live MT5 fills.

`/symbol_info` was already cached on the broker side (v0.26.3); this phase exposes that cache via `MT5InstrumentRegistry` so strategy-side code sees the same data. Backtests read a static `data/instruments.yaml` via `YamlInstrumentRegistry`.

## What's new

- `com.qkt.instrument.InstrumentMeta` — data class with `(qktSymbol, contractSize, volumeStep, volumeMin, volumeMax, pointSize, digits, tradeStopsLevelPoints)`.
- `com.qkt.instrument.InstrumentRegistry` — interface with `lookup(qktSymbol): InstrumentMeta?` and a `require()` default that fails loud.
- `com.qkt.instrument.YamlInstrumentRegistry.load(path)` — backtest impl, reads the schema documented below.
- `com.qkt.instrument.MT5InstrumentRegistry(broker)` — live impl, adapts the broker's `/symbol_info` cache.
- `com.qkt.instrument.NoopInstrumentRegistry` — default for tests and unconfigured contexts. Returns null from every lookup; `require()` throws helpfully.
- `MT5SymbolInfo.contractSize` — new field; `MT5Client.getSymbolInfo` now parses `trade_contract_size` from the gateway response.
- `MT5Broker.instrumentMeta(qktSymbol)` — public method exposing the cached meta as an `InstrumentMeta`.
- `StrategyContext.instruments` — new field, default `NoopInstrumentRegistry`.
- `TradingPipeline.instruments` — constructor parameter, plumbed into `StrategyContext`.
- `BacktestCommand --instruments <path>` — CLI flag, default `<data-root>/instruments.yaml`.
- Contract-size-aware math in `SizingCompiler` (`SizeRiskAbs`, `SizeRiskFrac`), `PnLCalculator.unrealizedFor`, `StrategyPnL.unrealizedFor`, and `TradingPipeline`'s realized accumulator on every `OrderFilled` and `OrderPartiallyFilled` event.
- `MT5Broker` rejects orders with SL/TP within `tradeStopsLevelPoints × pointSize` of entry — pre-flight, before the gateway round-trip.

## Migration from previous phase

- `SizingCompiler.compile(sizing, stopDistance)` → `SizingCompiler.compile(sizing, stopDistance, streamAlias)`. The third arg is the alias of the stream the action targets; the evaluator uses it to look up `contractSize` from the registry.
- `PnLCalculator(positions, prices)` → `PnLCalculator(positions, prices, instruments = NoopInstrumentRegistry)`. Existing callers compile because of the default. Production paths (`Backtest`, `LiveSession`) wire a real registry.
- `StrategyPnL(strategyPositions, prices)` → `StrategyPnL(strategyPositions, prices, instruments = NoopInstrumentRegistry)`.
- Strategies that ship the `/100` (or similar) contract-size workaround in `SIZING RISK $` should drop it. With the new sizing math, the strategy's `SIZING RISK $ X` produces `lots = X / (slDistance × contractSize)` directly. The hedge-straddle migration in `qkt-prod` is part of this phase's cleanup.

## Usage cookbook

### 1. Author a backtest data manifest

`data/instruments.yaml`:

```yaml
instruments:
  - qktSymbol: EXNESS:XAUUSD
    contractSize: 100
    volumeStep: 0.01
    volumeMin: 0.01
    volumeMax: 200
    pointSize: 0.001
    digits: 3
    tradeStopsLevelPoints: 0
  - qktSymbol: EXNESS:EURUSD
    contractSize: 100000
    volumeStep: 0.01
    volumeMin: 0.01
    pointSize: 0.00001
    digits: 5
    tradeStopsLevelPoints: 0
```

`qkt backtest` auto-discovers it; `qkt backtest <file> --instruments <path>` overrides.

### 2. Run a backtest with real contract sizes

```sh
qkt backtest strategies/hedge-straddle.qkt \
    --from 2024-02-05 --to 2024-02-10 \
    --data-root ./data \
    --starting-balance 50000
```

Trade counts and lot sizes match the prior `/100`-hacked version of the strategy (the contractSize math now happens in the engine instead). PnL magnitudes are now in real dollars — for XAUUSD they scale by 100× vs the pre-Phase-30 numbers.

### 3. Write a strategy without the contract-size footgun

```qkt
STRATEGY clean VERSION 1

SYMBOLS
    gold = EXNESS:XAUUSD EVERY 5m

RULES
    WHEN gold.close > 0 AND POSITION.gold = 0
    THEN BUY gold
        SIZING RISK $ (50000 * 0.007)
        BRACKET { STOP LOSS BY 18, TAKE PROFIT BY 12 }
```

`50000 * 0.007 = 350` risk USD. SL distance 18. XAUUSD contractSize 100. Engine computes `lots = 350 / (18 * 100) = 0.1944 → quantized to 0.19`. Same as the pre-Phase-30 strategy's `/100` math — without the workaround.

### 4. Live mode with MT5

`LiveSession` constructs `MT5InstrumentRegistry` automatically when the broker tree contains an `MT5Broker`. No config required — the broker already calls `/symbol_info` on first placement of a symbol (v0.26.3) and the registry adapter reads from that cache.

### 5. Compose with a custom registry (advanced)

```kotlin
val live = MT5InstrumentRegistry(mt5Broker)
val backtest = YamlInstrumentRegistry.load(Paths.get("data/instruments.yaml"))
val composite =
    object : InstrumentRegistry {
        override fun lookup(qktSymbol: String): InstrumentMeta? =
            live.lookup(qktSymbol) ?: backtest.lookup(qktSymbol)
    }
```

Useful for live runs that backstop with a YAML file for symbols the venue hasn't exposed yet.

### 6. Pre-flight rejection at the stops level

```qkt
WHEN gold.close > 0 THEN BUY gold
    SIZING QTY 0.01
    BRACKET { STOP LOSS BY 0.05, TAKE PROFIT BY 10 }
```

If XAUUSD has `tradeStopsLevelPoints=100` and `pointSize=0.001`, the minimum SL distance is `100 × 0.001 = 0.1`. A 0.05 SL is rejected pre-placement with a structured reason — no gateway round-trip needed.

## Testing patterns

- `testStrategyContext()` accepts an `instruments` parameter. Default is `UnitContractRegistry` (returns a unit-contract meta for every lookup) — sizing math degenerates to the pre-Phase-30 shape, so existing tests that don't care about contract size keep passing.
- Tests that exercise contract size:

```kotlin
val regWith100 =
    object : InstrumentRegistry {
        override fun lookup(qktSymbol: String) =
            InstrumentMeta(qktSymbol, BigDecimal("100"), …)
    }
val ec = EvalContext(..., strategyContext = testStrategyContext(instruments = regWith100))
```

- `YamlInstrumentRegistryTest` covers duplicate keys, missing fields, missing files, and basic parse. Use the same fixture file (`src/test/resources/instruments/instruments.yaml`) for downstream tests that need realistic XAUUSD/EURUSD meta.
- The phase-30 PR adds an integration regression at `MT5BrokerIntegrationTest.bracket with SL too close to entry is rejected pre-placement` — model new stops-level tests on it.

## Known limitations

- **`MT5BrokerSimulator` is not in this phase.** PaperBroker still fills at the tick mid price; bid/ask-aware fills, simulated spread, and venue-style retcode rejection are the next layer of broker-parity work (`docs/parity/backtest-vs-live.md` rows 4–6 stay open).
- **Stops level only enforced on live.** `PaperBroker` still accepts any SL/TP distance. Closing that is the simulator's job.
- **`volumeMax` is parsed but not enforced.** Strategies that ask for a notional larger than the venue accepts will be rejected by the venue, not pre-flight. Add a check inside `prepareForPlacement` when this becomes a real concern.
- **Composite-broker registries** (live, multi-vendor) are sketched but not built. Today's `LiveSession.buildInstrumentRegistry` returns the first MT5 broker it finds. Multi-broker setups need an explicit composite, deferred until needed.

## References

- Spec: `docs/superpowers/specs/2026-05-15-instrument-metadata-design.md`
- Plan: `docs/superpowers/plans/2026-05-15-instrument-metadata.md`
- Strategy-port parity (separate concern): `qkt-prod/docs/PARITY.md`
- Execution-parity reference: `docs/parity/backtest-vs-live.md` (row 7 status changes to "closed in Phase 30" with this PR)
- Commits on the `phase30-instrument-metadata` branch:
  - `feat(instrument): add InstrumentMeta and registry primitives`
  - `feat(dsl): route SIZING RISK through contractSize`
  - `feat(pnl): apply contractSize to realized and unrealized math`
  - `feat(broker): enforce tradeStopsLevel pre-placement in MT5Broker`
