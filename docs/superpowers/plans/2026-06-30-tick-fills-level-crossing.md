# Implementation plan — fast tick-fills via the running-extreme filter

Spec: `docs/superpowers/specs/2026-06-30-tick-fills-level-crossing-design.md`.
Branch: `feat-tick-fills-level-crossing`. Backtest-only. TDD throughout; the gate is the
existing byte-identical tick-fills parity oracle.

## Mechanism (final)

On a fill-possible bar with only static orders and no time-based exits, feed the engine
only: the **opening tick**, every tick that sets a **new running-max of the ask series
or new running-min of the bid series**, and the **close tick**. Bail to the full real
slice if any trailing/composite order *or* any time-based exit (GTD / TimeExit / stack)
is live on the symbol. No-fill bars stay synthetic, as today.

Parity rests on: the first crossing of any *static* level is necessarily a new-extreme
tick; equity/maxDrawdown extremes occur at price extremes; close fixes end-of-bar
`lastPrice`; the opening tick is emitted first (so bar-close logic places orders before
any fill resolves). The bail set is exactly `OrderManager.canTriggerInBar`'s conservative
`else -> true`, plus time-based exits.

## Tasks

1. **`OrderManager.intrabarFill(symbol, low, high): IntrabarFill`** — replace the boolean
   `canTriggerInBar` with a three-way resolution: `SYNTHETIC` (no order level in
   `[low, high]` and no live position concern), `ALL_TICKS` (a trailing/composite order
   or a time-based exit is live → bail), `EXTREMES` (a static stop/limit/if-touched level
   is in range, nothing forces a full replay). Tests: one per branch + the existing
   stop/limit/composite cases retargeted.

2. **`BarResolvedFeed` EXTREMES path** — in `SymbolFeed`, when the per-bar resolution is
   `EXTREMES`, stream the opening tick then only the new-extreme ticks (ask-max / bid-min,
   side-aware via `buyExecPrice`/`sellExecPrice`) then the close tick, instead of draining
   the whole slice. `ALL_TICKS` keeps streaming the full slice; `SYNTHETIC` keeps the
   synthetic O/L/H/C. Tests: a fill-bar with a static stop emits opening + extremes + close;
   a trailing order emits the full slice; ordering preserved.

3. **Wire `ReplayEngine` / `Backtest`** — thread the `intrabarFill` callback in place of
   `barFillPossible`. No behavior change to full-tick or plain-bars.

4. **Parity oracle** — `TickResolvedParityTest` must stay byte-identical (`totalPnL`,
   `trades`, `maxDrawdown`, `trades.csv`) vs a full-tick replay. Add cases that exercise
   each breaker: a trailing-stop strategy (bails, stays exact), a time-exit /
   `CLOSE_AT_MARKET` strategy (bails), a position-held-across-bar strategy (maxDrawdown
   extreme), the existing two-symbol interleave.

5. **Speedup** — end-to-end wall-clock on a real backtest (30-day XAUUSD), new path vs
   current tick-fills, and vs full-tick. Record the measured multiple.

6. **PR → dev → merge**, then promote so `:edge` rebuilds.

7. **qkt-forge** — the optimization is transparent (same `--bars --tick-fills` flag, faster),
   so forge's G6/G7 `tick_fills=True` inherits it via the `:edge` pull. Verify a forge G6
   run is still byte-identical and faster.

## Out of scope

- The aggressive level (compute the fill directly, bypassing the engine) — a separate,
  oracle-gated follow-up once the conservative wall-clock is in hand.
- Time-exit bars run the full slice (correct, just not accelerated). Resolving time-deadline
  ticks instead of bailing is a later refinement.
