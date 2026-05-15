# Backtest vs live — execution parity

Where `qkt backtest` and live MT5 trading agree, where they don't, and what you can claim from a backtest report.

This is the execution-side companion to the data-side parity reports in this directory. Those compare TradingView vs MT5 as **market-data sources**. This one compares the **execution pipelines** that consume those ticks.

## The proven contract — strategy pipeline is shared

Both `Backtest` (`src/main/kotlin/com/qkt/backtest/Backtest.kt`) and `LiveSession` (`src/main/kotlin/com/qkt/app/LiveSession.kt`) construct the same `TradingPipeline`. The strategy compilation, indicator math, candle aggregation, rule firing, signal-to-`OrderRequest` translation, and risk engine are byte-identical between modes.

`BacktestLiveParityTest` at `src/test/kotlin/com/qkt/parity/BacktestLiveParityTest.kt` enforces this contract: same ticks + same strategy must produce identical trade lists in both paths. CI runs it on every push.

If the trade lists ever drift in that test, the pipeline contract is broken and the test fails.

## The unproven part — broker layer is different

`BacktestLiveParityTest` uses `LiveSession` with its default `PaperBroker`. So the proven equation is:

```
Backtest + PaperBroker  ===  LiveSession + PaperBroker
```

It does **not** prove `Backtest === LiveSession + MT5Broker`. Every divergence below lives between `PaperBroker` and `MT5Broker`, and is invisible to the parity test.

## Catalog of broker-layer divergences

Each row lists the symptom, the source file the live behavior lives in, and whether the backtest models it.

| # | Concern | Backtest (`PaperBroker`) | Live (`MT5Broker`) | Status |
|---|---|---|---|---|
| 1 | **Volume quantization** | fills exactly the requested `quantity` (`PaperBroker.publishFill`) | rounds DOWN to `volume_step` from `/symbol_info` (`MT5Broker.quantizeForPlacement`, v0.26.3) | divergent |
| 2 | **Price rounding** | uses the raw 8-decimal `BigDecimal` from the engine | rounds `price`/`sl`/`tp`/`stopLimit` to `digits` (HALF_EVEN) before sending (`MT5Broker.quantizeForPlacement`, v0.26.4) | divergent |
| 3 | **Below-`volume_min` orders** | fills regardless of how small | rejected pre-flight with `OrderRejected("quantized volume below venue volumeMin …")` | divergent |
| 4 | **Bracket entry fills** | fills at `tickPrice` the moment the trigger is crossed (`PaperBroker.fillFromTrigger`) | venue fills at actual ask (for BUY_STOP) or bid (for SELL_STOP) when the trigger prints | divergent |
| 5 | **Spread / slippage** | uses `tick.price` (the mid set by `Mt5TickFeedSource` when `last=0`) | live pays the venue spread; volatile bars also slip | divergent |
| 6 | **Market-order fill price** | `priceProvider.lastPrice(symbol)` — the last tracked tick (`PaperBroker.fillMarket`) | MT5 fills at venue ask/bid at submit time, with `deviation` slack | divergent |
| 7 | **Contract size** | treats `quantity` as raw units; **no `trade_contract_size` multiplier** | MT5 sizes positions as `lot × contract_size` (XAUUSD = 100 oz/lot) | **dangerous** — see below |
| 8 | **`tradeStopsLevel`** | not enforced | MT5 rejects orders whose SL/TP is closer to entry than `trade_stops_level × point` | divergent (not yet hit in qkt-prod) |
| 9 | **OCO atomicity** | both legs always coupled in memory | emulated via comment-tag prefix + position poller; cancel-on-fill has a few-ms window between the fill event and the sibling-cancel request | divergent edge case |
| 10 | **Pending-order persistence** | always in memory of the running backtest | persists to the broker's order book; daemon restart re-reads via `MT5StateRecovery` | divergent edge case |
| 11 | **Latency** | instantaneous tick → fill | gateway HTTP round-trip + venue execution latency | divergent |
| 12 | **Retcode handling** | no concept | MT5-specific retcodes (`10009`, `10015`, `10015` price, etc.) translated to `OrderRejected` reasons | divergent |
| 13 | **Trading calendar / sessions** | runs through every tick the feed produces | respects venue session hours (gaps in `/tick` during weekends, holidays) | aligned in qkt by the `TradingCalendar` injection; divergent if backtest data covers a window live wouldn't trade |

## The dangerous one — contract size (#7)

The hedge-straddle strategy in `qkt-prod/strategies/hedge-straddle.qkt` works around the missing contract-size primitive by dividing the `SIZING RISK $` expression by `100`:

```
SIZING RISK $ ((50000 * 0.007 * <riskMult>) / 100)
```

That `/100` correction is the XAUUSD oz/lot ratio. Live MT5 then multiplies the lot back up by `trade_contract_size = 100` internally — so the qkt-side lot of `0.19` represents 19 ounces. In backtest, `PaperBroker` has no contract-size concept; a `quantity` of `0.19` represents 0.19 units of a unitless thing. The PnL math runs through the strategy's `quantity` directly.

**Concrete consequence:** the backtest reports trade quantities and PnL in different units than live. The Feb 5-9 hedge-straddle backtest produced `+$13.25 net PnL` over 164 trades on a $50,000 base. That figure is computed against `PaperBroker`'s unitless quantity, not against XAUUSD's 100 oz/lot. You cannot extrapolate it to expected live PnL by any direct ratio — the strategy's `/100` workaround happens at sizing time, not at fill time, and `PaperBroker` doesn't undo it on the fill side.

This is the gap that gets resolved when the engine gains an `InstrumentMeta` primitive (currently "GAP 4" in `qkt-prod/docs/PARITY.md`). Until then: **treat backtest PnL as a directional / ranking signal, not a dollar figure**.

## How to use the backtest safely today

- **Use the backtest to compare strategies and parameters against each other.** Rule firing, signal counts, win rate, drawdown ordering, sharpe ranking all transfer.
- **Don't quote backtest PnL as an expected live figure.** It's the right shape; it's not the right magnitude. The contract-size workaround means the magnitudes are in a different unit than live.
- **Don't backtest a brand-new strategy and immediately wire to live without a paper-mode run.** The MT5Broker can reject orders the backtest happily accepted (rows 3, 8, 12 above).
- **Treat `qkt backtest` as the pipeline test, not the broker test.** It's correct for what it covers; it does not cover what it doesn't cover.

## What would close the gap

A `MT5BrokerSimulator` usable in backtest mode, mirroring:

- `volume_step` / `volume_min` quantization (rows 1, 3)
- `digits` price rounding (row 2)
- bid/ask fills at venue prices (rows 4-6)
- contract-size multiplier in fill PnL (row 7, the big one)
- `tradeStopsLevel` rejection (row 8)
- realistic latency model (rows 11)

Plus a sibling test `BacktestMT5LiveParityTest` that replays a recorded MT5 session and asserts the simulator matches. That's the work that turns "backtest of hedge_straddle returned +$13.25" into "this is what live should look like."

Tracked as a future engine phase — not scheduled. Until it ships, this document is the answer to "can I trust the backtest."

## File pointers

- Pipeline contract — `docs/phases/phase-4-backtest.md` (the "Same pipeline, live execution" section)
- Pipeline parity test — `src/test/kotlin/com/qkt/parity/BacktestLiveParityTest.kt`
- Live-pipeline construction — `src/main/kotlin/com/qkt/app/LiveSession.kt` (`broker = buildBroker(paperBroker, ...)`)
- Backtest-pipeline construction — `src/main/kotlin/com/qkt/backtest/Backtest.kt:fromStore`
- `PaperBroker` fills — `src/main/kotlin/com/qkt/broker/PaperBroker.kt`
- `MT5Broker` quantization (v0.26.3 + v0.26.4) — `src/main/kotlin/com/qkt/broker/mt5/MT5Broker.kt` (`quantizeForPlacement`)
- Strategy-port parity (separate concern) — `qkt-prod/docs/PARITY.md`
- Data-source parity (separate concern) — `docs/parity/parity-bars-xauusd-m5.md`, `docs/parity/parity-ticks-xauusd.md`
