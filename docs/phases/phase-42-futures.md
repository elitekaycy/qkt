# Phase 42 — Futures

**Status:** Built on `phase42-derivatives-foundation` in stages 42.0–42.6 (not yet merged to `dev`).
**Spec:** [`../superpowers/specs/2026-09-30-futures-options-design.md`](../superpowers/specs/2026-09-30-futures-options-design.md)
**Plans:** [`42.0/42.1`](../superpowers/plans/2026-09-30-derivatives-42-0-42-1.md),
[`42.2/42.3`](../superpowers/plans/2026-09-30-derivatives-42-2-42-3.md),
[`42.4`](../superpowers/plans/2026-09-30-derivatives-42-4.md),
[`42.5`](../superpowers/plans/2026-09-30-derivatives-42-5.md),
[`42.6`](../superpowers/plans/2026-09-30-derivatives-42-6.md)

## Summary

qkt backtests futures the way it backtests CFDs: the same `.qkt` files, the same `qkt backtest`
command and the same reports. A strategy names a listed contract (`BINANCE_UM:BTCUSDT_241227`) or
follows a root (`BINANCE_UM:BTCUSDT@front`, `@next`); the continuous series is forward-adjusted from
measured rolls, orders execute on the real contract of the moment, positions and resting orders are
carried across every roll with the roll's cost booked, and contracts held to expiry settle at the
exchange's delivery price. The engine's P&L on a continuous stream equals the P&L of the contracts it
traded, to the cent — pinned on real Binance data across three rolls. CFD runs are unchanged: golden
pins on the report JSON and trade tape of ten CFD cases guard every stage.

## What's new

- **Instruments** — `futures:` roots in `instruments.yaml` (`root`, `currency`, `multiplier`,
  `tickSize`, `volumeStep`, `volumeMin`, optional `volumeMax`, `calendar`, `exchangeFeePerContract`,
  `takerFeeRate`, `margin`, `roll`, `slippageTicks`, `expiryGuardHours`); `FutureTerms` on
  `InstrumentMeta.derivative`; explicit instrument `currency`.
- **Free data** — `qkt fetch ROOT --catalog` (contract list and delivery prices), `qkt fetch ROOT --rolls`
  (the roll history `contracts/<VENUE>/<ROOT>.rolls.json`), `qkt fetch CONTRACT --tf …` (Binance
  per-contract klines from data.binance.vision, no key).
- **Continuous streams** — `@front` / `@next` selectors; `RollPolicy` (`daysBeforeExpiry`, `atUtc`,
  `adjust: panama | ratio | none`); `ContinuousChain` and `ContinuousMarketSource` serve the stitched,
  forward-adjusted series from the first measured roll on.
- **Execution** — `ExchangeSimulator` (netting, tick-grid snapping that never fills early, `volumeMax`,
  slippage from the run's model, fees on every fill as venue costs, expiry settlement, expiry guard);
  `ContinuousContractBroker` (the one translation boundary between a stream and its contracts, one lane
  per stream); `RollExecutor` (carry at the roll's reference prices, re-placement of resting orders,
  `ROLL_FAILED` handling); `CostIncurred` / `FillAccountingKind.COST` for roll costs; `ExitReason.EXPIRY`
  and `ROLL_FAILED`.
- **DSL** — `.contract`, `.dte`, `.days_to_roll` on futures streams; `.tick_value`, `.multiplier` on
  every stream.
- **Reports** — `rolls.csv`, `contracts.csv`, `settlements.csv` (futures runs only), `rollCostsPaid`
  in the gross-to-net bridge, `BacktestResult.rolls`, `.contractFills`, `.settlements`.

## Migration from previous phase

None for existing strategies. Two report behaviours changed for futures only: exchange fees now appear
in `commissionPaid` as venue-reported costs (they were engine commissions in 42.1), and orders off a
contract's tick grid are snapped instead of rejected.

## Usage cookbook

### 1. Backtest one listed contract

```bash
qkt fetch BINANCE_UM:BTCUSDT --catalog
qkt fetch BINANCE_UM:BTCUSDT_241227 --tf 1h --from 2024-09-20 --to 2024-12-27
```

```yaml
futures:
  - root: BINANCE_UM:BTCUSDT
    currency: USDT
    multiplier: 1
    tickSize: 0.1
    volumeStep: 0.001
    volumeMin: 0.001
    takerFeeRate: 0.0005
```

```qkt
STRATEGY dec VERSION 1
SYMBOLS
    btc = BINANCE_UM:BTCUSDT_241227 EVERY 1h
RULES
    WHEN ema(btc.close, 20) CROSSES ABOVE ema(btc.close, 50) AND btc.dte > 2
    THEN BUY btc SIZING 0.01
    WHEN btc.dte < 2 AND POSITION.btc != 0
    THEN CLOSE btc
```

A position still open at expiry is settled at the delivery price (`settlements.csv`); in the last
24 hours only exits are accepted.

### 2. Follow the front contract across rolls

```yaml
futures:
  - root: BINANCE_UM:BTCUSDT
    # …as above…
    roll: { daysBeforeExpiry: 8, atUtc: "08:00", adjust: panama }
```

```bash
qkt fetch BINANCE_UM:BTCUSDT --rolls
qkt fetch BINANCE_UM:BTCUSDT_241227 --tf 1h --from 2024-09-18 --to 2024-12-20
qkt fetch BINANCE_UM:BTCUSDT_250328 --tf 1h --from 2024-12-18 --to 2025-03-21
```

```qkt
STRATEGY trend VERSION 1
SYMBOLS
    btc = BINANCE_UM:BTCUSDT@front EVERY 1h
RULES
    WHEN ema(btc.close, 20) CROSSES ABOVE ema(btc.close, 50)
    THEN BUY btc SIZING 0.01
    WHEN ema(btc.close, 20) CROSSES BELOW ema(btc.close, 50)
    THEN CLOSE btc
```

Indicators run on the adjusted series, so a roll never looks like a price move. Each roll appears in
`rolls.csv` with its cost; `contracts.csv` shows the contract behind every fill.

### 3. Stay flat through rolls

```qkt
WHEN btc.days_to_roll < 0.5 AND POSITION.btc != 0
THEN CLOSE btc
```

No position is carried, so no roll cost is paid; the next entry trades the new contract. The
threshold must be longer than one bar: the bar closing at the roll already follows the new contract.

### 4. Add realistic costs

```yaml
    slippageTicks: 2
```

```bash
qkt backtest trend.qkt --from 2024-09-20 --to 2025-03-01 --slippage instrument
```

Market orders and triggered stops slip two ticks against the order; fees follow `takerFeeRate`.

### 5. Mix CFD and futures

A strategy may declare CFD and futures streams together. The CFD streams keep today's broker
(`--broker paper` or `mt5-sim`); the futures streams always use the exchange simulator. The CFD side
fills exactly as it would alone (`ReplayBrokerFuturesTest`).

## Testing patterns

`FuturesFixtureRun.run(dir, fixture, strategy, from, to, rootLines, flags)` backtests over the real
fixtures in `src/test/resources/futures/` (see each `PROVENANCE.md`) through the CLI's own assembly
and returns the `BacktestResult`. The identity every futures test can assert for a strategy that ends
flat:

```kotlin
val fillCash = result.contractFills.fold(ZERO) { c, f ->
    f.contractPrice.multiply(f.quantity).let { if (f.side == Side.SELL) c.add(it) else c.subtract(it) }
}
val rollCash = result.rolls.fold(ZERO) { c, r -> c.add(r.quantity.multiply(r.fromFill.subtract(r.toFill))) }
assertThat(result.perStrategy.values.single().realizedTotal)
    .isEqualByComparingTo(fillCash.add(rollCash).subtract(allFees))
```

Unit-level fixtures: `ExchangeFixture` (a simulator over two quarterlies) and `ContinuousFixture`
(a continuous broker over four quarterlies with three measured rolls).

## Known limitations

- Continuous streams and listed contracts are backtest-only; paper and live refuse them until the
  phase 44 gateway connector (parity rows A32–A37).
- Only `adjust: panama` streams can be traded; `ratio` and `none` can be read.
- `--tick-fills` is refused for continuous streams.
- Margin (`MarginModel`, `margin_daily.csv`) and the CME calendar are phase 42.7; options are phase 43.
- The exchange simulator ignores latency, venue-rejection and partial-fill settings (a warning says so).

## References

- Parity catalog rows A32–A37: [`../parity/backtest-vs-live.md`](../parity/backtest-vs-live.md)
- Data how-to: [`../how-to/backtest-data.md`](../how-to/backtest-data.md)
- Free-data research: [`../research/2026-09-30-binance-quarterly-free-data.md`](../research/2026-09-30-binance-quarterly-free-data.md)
