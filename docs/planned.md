---
title: Planned features
hide:
  - toc
---

# Planned features

A canonical, honest list of features the docs reference that **aren't yet implemented**. Each entry links to the phase that will ship it.

If you're reading this because a recipe or example mentioned something doesn't work yet — sorry. We over-described some surface in earlier drafts and we're catching up. This page is the single source of truth for "what's coming."

## Phase 24 — risk-sizing primitives

Tracked in `docs/backlog.md`. ETA: short — well-defined parser/sizer work.

| Feature | What you'll be able to write | Workaround today |
| --- | --- | --- |
| `SIZING N PCT RISK` | `BUY btc SIZING 1.0 PCT RISK STOP_LOSS AT btc.close - atr(btc, 14) * 2` | Compute size manually: `SIZING <usd> USD` after running the math yourself |
| `WARMUP N BARS` | Explicit warmup declaration at the top of `STRATEGY` blocks | The compiler infers warmup from indicator periods automatically — usually enough |
| `IS NULL` / `IS NOT NULL` | `WHEN ema(btc.close, 200) IS NOT NULL AND ...` | Lean on the implicit null-propagation behaviour — comparisons with null evaluate to false, so rules silently won't fire during warmup |
| `FLATTEN` DSL action | `THEN FLATTEN` (closes every position across symbols) | Use `CLOSE_ALL` which already exists |

## Phase 25 — operator tooling + DSL extensions

Tracked in `docs/backlog.md`. Heavier scope; multiple sub-tasks.

| Feature | What you'll get | Workaround today |
| --- | --- | --- |
| `qkt fetch <SYMBOL> --from --to` CLI | Populate the local data store from a fetcher (Dukascopy, etc.) without leaving the shell | Use `./scripts/fetch-dukascopy.sh` directly; or write data via the `DataStore` Kotlin API |
| `qkt sweep` CLI | Parameter grid-search wrapper around the existing `BacktestSweep` Kotlin harness | The harness exists today — use it programmatically (see `src/main/kotlin/com/qkt/backtest/sweep/`) |
| `qkt walkforward` CLI | Walk-forward validation wrapper | `BacktestSweep` + rolling time-windows manually |
| `TRAILING_STOP BY <amount>` wiring | The token exists in the parser; finishing the AST → action-compiler → broker dispatch path | Add a rule that closes when the trade drops below a moving high — manual trail |
| VWAP DSL registration | `VWAP(stream, period)` callable from `.qkt` files | The `VWAP` Kotlin class exists; needs `IndicatorInput.TICK_SERIES` plumbing first. Use SMA on `stream.close` as a proxy until then |
| `per_strategy:` risk-config block | Per-strategy risk rules (e.g. cooloff-after-loss for one strategy without affecting others) | Use daemon-wide rules in `risk: rules:` for now |
| `IF_TOUCHED`, `STOP_LIMIT` order types | Additional order shapes beyond MARKET/LIMIT/STOP | Compose manually with multiple rules + `WHEN` triggers |

## Phase 26+ — exploratory

These are ideas with no scheduled phase yet. Tell us if you'd use them (open an issue):

- `position(stream).is_long` / `.is_short` convenience accessors
- True per-symbol realized P&L (today, `POSITION.<stream>.realized_pnl` returns strategy-level)
- Block bootstrap in Monte Carlo (today: i.i.d. bootstrap; bad for clustered trades)
- Order-book-aware backtesting
- More broker integrations (Alpaca, IBKR, OANDA)

## How to read this list

If an admonition in another doc page sent you here, you're trying to use a feature that's **announced but not shipped yet**. Stick to the workaround until the phase that ships it merges. The phase changelogs at [`/phases/`](phases/index.md) document each shipping wave.

If you find docs referencing a feature that should be on this page but isn't, [open an issue](https://github.com/elitekaycy/qkt/issues/new). The aim is zero gap between docs and engine; we're aware this list shrinks toward empty.
