# Phase 30 — Instrument metadata

## Goal

Close GAP 4 from `qkt-prod/docs/PARITY.md`: the engine has no first-class notion of contract size, lot step, price step, or stops level, so strategies work around it (e.g. hedge-straddle's `/100` in `SIZING RISK $`), and backtest PnL is in different units than live PnL.

Phase 30 introduces an `InstrumentMeta` primitive that lives end-to-end through the trading pipeline and the backtest engine. After this phase, the SIZING math is the same in qkt as it is in MT5, the `/100` workaround disappears from hedge-straddle, and `Backtest + PaperBroker` produces a PnL number that's directly comparable to live MT5 fills.

## Architecture

### The primitive

New package `com.qkt.instrument`. One data class:

```kotlin
data class InstrumentMeta(
    val qktSymbol: String,
    val contractSize: BigDecimal,
    val volumeStep: BigDecimal,
    val volumeMin: BigDecimal,
    val volumeMax: BigDecimal?,
    val pointSize: BigDecimal,
    val digits: Int,
    val tradeStopsLevelPoints: Int,
)
```

Plus an `InstrumentRegistry` (per-engine, populated at strategy boot):

```kotlin
interface InstrumentRegistry {
    fun lookup(qktSymbol: String): InstrumentMeta?
    fun require(qktSymbol: String): InstrumentMeta  // throws on miss
}
```

The registry is owned by `TradingPipeline` and resolved once at strategy load. Two implementations:

- `MT5InstrumentRegistry` (live) — wraps the per-broker symbol_info cache that `MT5Broker.symbolMeta` already populates from `/symbol_info`. Adds `trade_contract_size` to the parse (currently dropped).
- `YamlInstrumentRegistry` (backtest) — reads `data/instruments.yaml` at startup.

### Where meta is consumed

Four call sites change from "hardcoded or absent" to "registry.require(symbol)":

1. **DSL sizing** — `ActionCompiler` compiles `SizeRiskAbs`. New evaluator:
   - **before**: `lots = riskAmount / slDistance`
   - **after**: `lots = riskAmount / (slDistance * contractSize)`
2. **PaperBroker fills** — `PaperBroker.publishFill` computes realized PnL:
   - **before**: `pnl = (exitPx - entryPx) * quantity`
   - **after**: `pnl = (exitPx - entryPx) * quantity * contractSize`
3. **MT5Broker quantization** — `quantizeForPlacement` already reads `volume_step`/`digits` from `symbolMeta`. Migrate to read from `InstrumentRegistry` for consistency. Behavior unchanged.
4. **Stops-level enforcement** — new rejection path in `MT5Broker.submitSingle` and `submitComposite`: if `|sl - entryPx| < tradeStopsLevel * pointSize`, reject with reason before placement (MT5 would reject anyway; surfacing it locally is clearer).

### Backtest meta source

Static YAML at `data/instruments.yaml`:

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

`YamlInstrumentRegistry(yamlPath)` reads it on construction, fails loudly on duplicate keys or missing required fields. Test fixture lives at `src/test/resources/instruments/`.

### Hedge-straddle migration

Remove the `/100` correction from both legs of `qkt-prod/strategies/hedge-straddle.qkt`:

```diff
- SIZING RISK $ ((50000 * 0.007 * <riskMultCase>) / 100)
+ SIZING RISK $ (50000 * 0.007 * <riskMultCase>)
```

Plus update the GAP 4 commentary block: drop the warning, point at this phase as the resolution.

Regression: the same backtest from earlier today (Feb 5-9 2024, 164 trades) must produce identical *trade counts and lot sizes* after migration, because the same `0.007 × 50000 / (18 × 100)` math now happens inside the engine instead of in the DSL. The *PnL number* will change because `PaperBroker` now multiplies by `contractSize=100` — that's the point.

## Decisions

- **Hard error on missing meta** in both live and backtest. No silent default of `contractSize=1`. A symbol declared in a strategy but absent from the registry fails strategy compile/load. This is the trade-off for never seeing a `/100`-class footgun again.
- **YAML lives in `data/`** alongside `data/sample/symbols/`. Same volume that `qkt backtest --data-root` points at.
- **`MT5InstrumentRegistry` and `YamlInstrumentRegistry` share the `InstrumentRegistry` interface** so `TradingPipeline` doesn't fork by mode. Selection happens at construction (Backtest builds Yaml; LiveSession builds MT5).
- **PaperBroker fills** apply contract size at PnL-compute time, not at quantity-track time. Position quantities stay in qkt-side lots throughout.

## Out of scope (carried to a future phase)

- **`MT5BrokerSimulator`** for backtest. The simulator that mirrors MT5's bid/ask fills, spread, and rejection semantics. Phase 30 fixes contract-size and stops-level — the *PnL magnitude* — but leaves the *fill price* model unchanged (still mid). The simulator is the next chunk.
- **`tradeStopsLevel` enforcement in backtest's `PaperBroker`**. Backtest still permits any SL/TP distance. Phase 30 only enforces it in live.
- **GAP 1 (armed trailing stop)** and **GAP 3 (spread gate)** — separately tracked. Both become easier once `InstrumentMeta` exists.

## Migration plan

Phase ships in **one PR**, but as four ordered commits so each compiles + tests independently:

1. `feat(instrument): add InstrumentMeta + Registry primitives` — types, two impls, registry tests. Engine unchanged.
2. `feat(dsl): route SIZING RISK through contractSize` — `SizeRiskAbs` reads from registry. Hedge-straddle migration also lands here. `BacktestLiveParityTest` still passes (PaperBroker on both sides).
3. `feat(broker): apply contractSize to PaperBroker fill PnL` — backtest PnL now in real units. Updates the lone existing backtest regression test's expected numbers.
4. `feat(broker): enforce tradeStopsLevel pre-placement in MT5Broker` — Phase 30's only behavior-change on the live path.

## Test plan

- New `InstrumentMetaTest`, `YamlInstrumentRegistryTest`, `MT5InstrumentRegistryTest`.
- DSL: extend `ActionCompilerSizingTest` (or equivalent) with assertions on contractSize=100 sizing for XAUUSD.
- PaperBroker: PnL math test with non-1 contractSize.
- MT5Broker: stops-level rejection test.
- Backtest regression: re-run hedge-straddle Feb 5-9, assert identical lot sizes and trade timestamps; record new PnL number (will be different).
- `BacktestLiveParityTest`: must keep passing after every commit.

## References

- GAP 4 description: `qkt-prod/docs/PARITY.md`
- Parity caveat for users: `docs/parity/backtest-vs-live.md` (row 7 — "the dangerous one")
- v0.26.3 quantize implementation we'll migrate: `src/main/kotlin/com/qkt/broker/mt5/MT5Broker.kt:quantizeForPlacement`
- The gateway field we'll start parsing: `trade_contract_size` in `/symbol_info/{symbol}` responses
