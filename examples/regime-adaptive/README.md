# Regime-adaptive portfolio

A minimal portfolio that switches risk allocation between a trend-following child and a mean-reversion child based on hourly ADX(14) on BTCUSDT.

## Files

| File | Purpose |
| --- | --- |
| `book.qkt` | Portfolio definition with regimes and allocation weights |
| `trend.qkt` | Trend-following child |
| `meanrev.qkt` | Mean-reversion child |

## Regime logic

```qkt
REGIMES
    NAME market_regime
    STATE trend WHEN adx(btc, 14) > 25
    STATE range DEFAULT
```

- **trend regime** (`adx > 25`): allocate 80% to trend, 20% to meanrev
- **range regime** (`adx <= 25`): allocate 20% to trend, 80% to meanrev

The weights are applied to new risk-increasing orders through the same `bookScaleFor` seam used by book drawdown de-risking, so the behavior is identical in backtest and live.

## Why this matters

Instead of fully switching one strategy off, the portfolio can *tilt* risk toward the child that fits the current market regime. Both children remain active, but the dominant regime receives the larger share of capital while the other stays as a small hedge/source of diversification.

## Validate

```bash
qkt parse examples/regime-adaptive/book.qkt
```

## Backtest

Requires hourly BTCUSDT data in `~/.qkt/data/`:

```bash
qkt backtest examples/regime-adaptive/book.qkt \
    --from 2024-01-01 --to 2024-12-31 \
    --json
```

## Live deploy

```bash
qkt deploy examples/regime-adaptive/book.qkt --as regime-adaptive
```

The daemon starts one child session per import (`trend`, `meanrev`) and updates the regime weights on each closed candle before the children evaluate their rules.
