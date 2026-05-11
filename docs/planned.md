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

## Phase 26 — pending-entry OCO and clock accessors

Spec: [`docs/superpowers/specs/2026-05-11-phase26-pending-oco-and-clock-design.md`](superpowers/specs/2026-05-11-phase26-pending-oco-and-clock-design.md). Unlocks pending-order straddle strategies. The execution-layer primitive (`StandaloneOCO`) is already present; this phase adds the DSL surface to reach it, plus DSL-visible time accessors.

| Feature | What you'll be able to write | Workaround today |
| --- | --- | --- |
| `OCO_ENTRY { BUY ..., SELL ... }` | Two pending entries linked one-cancels-other; whichever fills, the other auto-cancels | None — the only way today is two independent `Signal.Submit` calls from Kotlin code with no link |
| `NOW.hour_utc`, `NOW.minute_utc`, `NOW.weekday`, `NOW.date_utc`, `NOW.epoch_ms` | Session-window gating from inside `.qkt` files | None — strategies can't read the wall clock at all from the DSL |
| `TIF GTD UNTIL now + <duration>` | Auto-expire pending orders after a relative window | Use `TIF GTD UNTIL <absolute-epoch-ms>` and compute the timestamp at strategy-author time (only works in offline contexts) |
| MT5 broker OCO routing verified | Confidence that `OCO_ENTRY` actually works on live MT5 | Phase 17 broker exists; OCO path is currently untested |

## Phase 27+ — exploratory

These are ideas with no scheduled phase yet. Tell us if you'd use them (open an issue):

- Multi-leg positions per symbol (simultaneous long + short, separate brackets) — needed for *legacy* market-mode hedge-straddle; production hedge-straddle uses pending mode and doesn't need this
- `position(stream).is_long` / `.is_short` convenience accessors
- True per-symbol realized P&L (today, `POSITION.<stream>.realized_pnl` returns strategy-level)
- Block bootstrap in Monte Carlo (today: i.i.d. bootstrap; bad for clustered trades)
- Order-book-aware backtesting
- More broker integrations (Alpaca, IBKR, OANDA)

## How to read this list

If an admonition in another doc page sent you here, you're trying to use a feature that's **announced but not shipped yet**. Stick to the workaround until the phase that ships it merges. The phase changelogs at [`/phases/`](phases/index.md) document each shipping wave.

If you find docs referencing a feature that should be on this page but isn't, [open an issue](https://github.com/elitekaycy/qkt/issues/new). The aim is zero gap between docs and engine; we're aware this list shrinks toward empty.
