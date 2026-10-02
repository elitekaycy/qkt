# Futures

Listed contracts and continuous streams. A listed contract names its expiry
(`BINANCE_UM:BTCUSDT_241227`); a continuous one follows the front or next
contract (`BINANCE_UM:BTCUSDT@front`, `@next`) across rolls. Live prices and
execution arrive through a [gateway account](https://github.com/elitekaycy/qkt-venue-gateway),
so the strategy never talks to the venue directly.

```qkt
SYMBOLS
    btc = BINANCE_UM:BTCUSDT_241227 EVERY 1h
RULES
    WHEN ema(btc.close, 20) > ema(btc.close, 50)
     AND POSITION.btc = 0
    THEN BUY btc SIZING 0.10
```

- **Contract fields** on every futures stream: `.contract` (followed code),
  `.dte` (days to expiry), `.days_to_roll`.
- **Backtest reports** carry the full derivatives tape: `rolls.csv`,
  `contracts.csv`, `settlements.csv`, `margin_daily.csv`, plus `rollCostsPaid`
  in the gross-to-net bridge.
- Margin is judged per position each day; a run without futures writes none of
  these files.

Deep dives: [Futures contract fields](../reference/dsl/expressions.md#futures-contract-fields) ·
[Backtest report artifacts](../reference/cli-commands.md#backtest-report-artifacts) ·
[qkt-venue-gateway](https://github.com/elitekaycy/qkt-venue-gateway)
