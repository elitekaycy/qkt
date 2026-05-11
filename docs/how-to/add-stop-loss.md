# Add a stop-loss

Every shape of stop qkt supports, with a one-line decision rule for when to use each.

## Quick decision tree

| You want | Use |
| --- | --- |
| "Lose at most $50 on this trade" | Fixed-points stop |
| "Lose at most 2% of position value" | Percent stop |
| "Stop adapts to volatility" | ATR-based stop |
| "Lock in profit as price runs" | Trailing stop |
| "Exit if it doesn't work in 1 hour" | TimeExit (Phase 13a) |

## Fixed-points stop

The simplest: a stop a fixed price distance below entry.

```qkt
WHEN ema(btc.close, 9) CROSSES ABOVE ema(btc.close, 21)
THEN BUY btc SIZING 0.1
     BRACKET { STOP_LOSS BY 500, TAKE_PROFIT BY 1000 }
```

`BY 500` means "500 in the symbol's quote currency". For BTCUSDT at $67000, that's $500 of price movement — about 0.7% of position.

**When to use:** scalping, fixed-tick markets (futures), when you want predictable max loss in absolute terms.

## Percent stop

A stop expressed as percent of entry price.

```qkt
BUY btc SIZING 0.1
BRACKET { STOP_LOSS BY 0.5 PCT, TAKE_PROFIT BY 2 PCT }
```

`0.5 PCT` means 0.5% below entry. At BTC $67000, that's a $335 stop.

**When to use:** strategies that should scale with price (BTC at $20k or $70k should still risk the same percentage).

## ATR-based stop

Stop expressed as a multiple of recent volatility.

```qkt
LET atrStop = atr(btc, 14) * 2

RULES
    WHEN ema(btc.close, 9) CROSSES ABOVE ema(btc.close, 21)
    THEN BUY btc SIZING 0.1
         BRACKET {
           STOP_LOSS AT btc.close - atrStop,
           TAKE_PROFIT AT btc.close + atrStop * 3
         }
```

The stop adapts: wide when the market's choppy, tight when calm. The 3× ATR target gives a 3R reward/risk.

**When to use:** most discretionary-style strategies. ATR stops are the default in serious systematic trading.

## Trailing stop

!!! info "Coming in Phase 25"
    `TRAILING_STOP BY <amount>` (and `TRAILING_STOP BY <pct> PCT`) are **planned but not yet wired**. The `TRAILING` token is recognised by the parser; the action-compiler → broker dispatch path is the missing piece. See [Planned features](../planned.md).

    **Workaround today** — track the position's running high in a rule and close manually when price retreats by the trail distance:

    ```qkt
    LET runHigh   = highest(btc.close, 50)
    LET trailDist = atr(btc, 14) * 2

    RULES
        WHEN POSITION.btc > 0
         AND btc.close < runHigh - trailDist
        THEN CLOSE btc
             LOG "trailing-stop exit at {price}" price=btc.close
    ```

    This produces the same behaviour as `TRAILING_STOP BY atr(btc, 14) * 2` will once Phase 25 lands. The `highest(btc.close, 50)` is the lookback window — long enough to track the position's peak, short enough that old highs roll off.

## Engine-managed vs venue-managed

When the broker supports `BRACKET` natively (MT5, paper broker), qkt sends the entry + stop + target as one atomic order group. The venue manages them.

When it doesn't (e.g., Bybit Spot via REST), the engine splits the bracket into separate orders:

1. Entry submitted as `Market`
2. On fill, stop submitted as `Stop` and target as `Limit` linked by OCO (one-cancels-other)
3. When either fills, the other auto-cancels

The DSL is the same either way — `OrderManager` handles the routing. See [Broker integration](../concepts/broker-integration.md) for the capability matrix.

## Risk-engine stops (account-level)

The per-trade stops above are about exit logic. To halt **all** trading when total drawdown exceeds a threshold, use the risk engine instead — that's account-level, not trade-level.

```yaml
# qkt.config.yaml
risk:
  rules:
    - type: max-drawdown
      pct: 5.0          # halt at 5% account drawdown
    - type: max-daily-loss
      usd: 500
```

See [phase 9](../phases/phase-9-risk-engine.md) for the full risk-rule catalog.

## Common gotchas

- **Stop too tight for the venue.** MT5 brokers enforce a minimum stop distance (`tradeStopsLevel` points). If your stop is closer than that, the order rejects. Use the broker profile's `instrumentOverrides` to see the minimum.
- **Stop at exactly the entry price.** A 0-distance stop fills immediately. Use at least 1× tick size.
- **Percent stop on inverse contracts.** Bybit inverse uses the underlying for percent calculation; check the venue docs.

## See also

- [DSL: bracket and stack](../reference/dsl-grammar.md) — full bracket syntax
- [Broker integration](../concepts/broker-integration.md) — which venues support what
- [Phase 13a — STACK pyramiding](../phases/phase-13a-stack.md) — multi-layer entries with shared brackets
