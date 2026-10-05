# Futures

Listed contracts and continuous streams. A listed contract names its expiry
(`BINANCE_UM:BTCUSDT_241227`); a continuous one follows the front or next
contract (`BINANCE_UM:BTCUSDT@front`, `@next`) across rolls. Live prices and
execution arrive through a [gateway account](https://github.com/elitekaycy/qkt-venue-gateway),
so the strategy never talks to the venue directly.

```haskell
SYMBOLS
    btc = BINANCE_UM:BTCUSDT_241227 EVERY 1h
RULES
    WHEN ema(btc.close, 20) > ema(btc.close, 50)
     AND POSITION.btc = 0
    THEN BUY btc SIZING 0.10
```

Or follow the root instead of one contract — `@front` trades whichever
contract is front and rolls on schedule:

```haskell
SYMBOLS
    btc = BINANCE_UM:BTCUSDT@front EVERY 15m WARMUP 100 BARS
RULES
    WHEN ema(btc.close, 20) > ema(btc.close, 50)
     AND POSITION.btc = 0
    THEN BUY btc SIZING 0.10
```

- **Contract fields** on every futures stream: `.contract` (followed code),
  `.dte` (days to expiry), `.days_to_roll`.
- **Backtest reports** carry the full derivatives tape: `rolls.csv`,
  `contracts.csv`, `settlements.csv`, `margin_daily.csv`, `liquidations.csv`,
  plus `rollCostsPaid` in the gross-to-net bridge.
- Margin is judged per position each day; a run without futures writes none of
  these files.
- **Liquidation (backtest).** Every tick compares account equity, marked at
  that tick's prices, with the maintenance margin of every position whose root
  declares `margin` (an option root without `margin` is checked only on entry,
  at its worst-case expiry loss, and never triggers it). Below it, the venue
  liquidates them all, symbol by symbol: each position closes at the tick's
  executable price (a long at the bid, a short at the ask, no slippage), pays
  the root's taker fee, and reaches the strategy as a venue close with exit
  reason `LIQUIDATION` (it runs `ON_CLOSE`). The contract's working orders are
  cancelled first. While equity stays below maintenance (a position the venue
  could not close yet), orders that add risk are refused. Each close is a row of
  `liquidations.csv` with the equity and maintenance that triggered it. A root
  without `margin` is never liquidated, and live trading leaves liquidation to
  the venue (parity row A59).

## Getting data

Binance USDⓈ-M quarterlies read from the free public archive — no account or
API key. Fetch the root's catalog (every quarterly with expiry and delivery
price), then each contract's bars at the timeframe the strategy uses:

```bash
qkt fetch BINANCE_UM:BTCUSDT --catalog
qkt fetch BINANCE_UM:BTCUSDT_240927 --tf 15m --from 2024-06-01 --to 2024-09-27
```

For a continuous stream, give the root a roll policy in `instruments.yaml` and
measure its rolls once with `qkt fetch BINANCE_UM:BTCUSDT --rolls`. Live, a
continuous stream trades on a `type: gateway` account (parity rows A53-A57).

A root's perpetual (`perpetual: BTCUSDT`) pays funding. Store its rates once
with `qkt fetch BINANCE_UM:BTCUSDT --funding --from 2024-06-01 --to 2024-09-27`;
a backtest charges them on every leg held through each one (`fundingPaid`), and
refuses to run without them unless `--funding off`. Live, the gateway (or the
Bybit linear connector, from its `Funding` executions) reports what the venue
charged and each strategy books its own part; qkt trades a perpetual on a
gateway only when it declares `funding` (parity row A58). A strategy reads a contract's mark and
index as `perp.mark` and `perp.index` (the premium is their difference): live from a gateway declaring
`mark_prices`, in a backtest from marks stored with `qkt fetch <VENUE:CONTRACT> --marks --tf <tf>` (row A60).

Deep dives: [Getting & storing data, Scenario 2b (Binance quarterlies + continuous streams)](../how-to/backtest-data.md) ·
[Backtest report artifacts](../reference/cli-commands.md#backtest-report-artifacts) ·
[qkt-venue-gateway](https://github.com/elitekaycy/qkt-venue-gateway)
