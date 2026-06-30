# Fast tick-resolved fills via per-bar level-crossing

Status: design. Backtest-only. Builds on the shipped `--bars --tick-fills` mode
(`2026-06-29-tick-resolved-fills-design.md`).

## Why

`--bars --tick-fills` is byte-identical to a full-tick replay but only ~1.1–1.95x
faster, because on every bar where a fill is *possible* it still replays **every**
intrabar tick through the engine (`OrderManager.evaluateTriggers`, the BigDecimal
compares, candle aggregation, the event bus). Replaying ~all ticks is what makes
it slow; the bars tier is ~30x faster precisely because it skips them — but bars
drifts and is unusable for grading.

The insight: in tick-fills mode the strategy's **signals fire on bar close**, so the
intrabar ticks do exactly one job — **trigger pending orders**. They never feed an
indicator or a rule. So we do not need to replay them; we need only the few ticks
that actually change engine state. Find those by **searching** the bar's ticks
instead of replaying them.

## The must-feed set

For a fill-possible bar, the only intrabar ticks that can change a byte-identical
result are:

1. the bar's **opening tick** (already emitted first — it closes the prior candle
   and lets bar-close logic place the orders/bracket before any fill resolves);
2. each tick that **first crosses a live order's static trigger level** (the fill);
3. each **time-deadline tick** — GTD expiry, `TimeExit`, stack deadline — because
   those fire on *time*, not price, and a `CLOSE_AT_MARKET` fills at that tick's price;
4. the bar's **high/low extreme ticks** while a position is open — mark-to-market
   equity and `maxDrawdown` are sampled per fed tick, so the intrabar excursion must
   be seen;
5. the bar's **close tick** — `priceProvider.lastPrice` at the bar boundary must equal
   the real close (bracket entry-estimate and any market order read it).

Everything else is provably inert and can be skipped. If any **trailing or composite**
order is live on the symbol (see breaker 1), the whole bar bails to a full real-tick
replay — exactly the conservative set `OrderManager.canTriggerInBar` already forces.

## The data structure

Per fill-possible bar, lazily build a small index over that bar's tick slice (the
mmap'd `BinaryTickFeed.slice()`, already an O(log n) seek):

- **prefix running-max of the ask series** and **prefix running-min of the bid series**.

Both are monotone, so "first tick whose running-max(ask) ≥ L" (buy-side levels) and
"first tick whose running-min(bid) ≤ L" (sell-side levels) are each a **binary search,
O(log n)** per level. The first crossing of a static level is *necessarily* a
new-extreme tick — a non-record tick cannot be the first to exceed it — which is the
parity guarantee. The same prefix arrays give the bar's high/low extremes (breaker 4)
for free.

Crossing is tested on the **side-aware execution price**: ask for buy-side levels
(`Tick.buyExecPrice`), bid for sell-side (`Tick.sellExecPrice`) — *not* mid. Identical
to mid only when spread is constant/zero; different on real bid/ask data.

Lazy + bounded: non-fill bars never touch ticks (already true); fill bars build the
index only for the levels of currently-live orders. An optional bounded prefetch
window decodes the next bars' slices ahead of the consumer so tick I/O overlaps
compute — a latency refinement, not required for correctness (the OS page cache
already handles residency for the mmap'd store).

## The four parity breakers

The level-crossing search is valid only for **static** levels. Four mechanisms break
that; each has a fix, and getting them right is the difference between byte-identical
and the approximate-bars drift:

1. **Trailing stops move intrabar.** `evaluateTriggers` ratchets the stop toward price
   every tick, so a non-extreme tick can fire it — a static search is invalid. **Bail
   to a full real-tick replay** for any bar with a live `TrailingStop` /
   `TrailingStopLimit` / `ArmedTrailingStop` / OCO / bracket-with-unknown-leg on the
   symbol. This is the existing `canTriggerInBar` `else -> true` set.
2. **Time-based exits fire on time, not price.** Resolve the time-deadline ticks
   (breaker set item 3 above) so a `TimeExit` / GTD / stack deadline fires at the same
   tick — and `CLOSE_AT_MARKET` at the same price — as a full replay.
3. **Equity / maxDrawdown is sampled per fed tick.** Feed the bar extremes while a
   position is open (item 4).
4. **End-of-bar `lastPrice`.** Feed the close tick (item 5).

## Two aggressiveness levels

1. **Conservative (the first build).** Find the must-feed ticks via the index, then
   **feed only those ticks to the engine unchanged** — the engine still computes the
   fill exactly as today. Engine invocations per fill-bar drop from O(ticks) to
   O(crossings + deadlines + 2). Parity is trivially preserved: the engine does the
   fill on the same tick the full replay would.
2. **Aggressive (future, gated on the conservative number).** Compute the fill price
   directly from the crossing tick (sided fill price + slippage) and apply it, bypassing
   the per-tick engine pipeline entirely. Faster, but it re-implements the exact
   fill-price/time selection, so the parity oracle must be airtight before it ships.

## Parity oracle

The gate is the existing byte-for-byte tick-fills oracle (`TickResolvedParityTest`):
the new path must produce an identical `totalPnL`, `trades`, `maxDrawdown`, and
`trades.csv` to a full-tick replay. Extend it to exercise every breaker:

- a trailing-stop strategy (must bail and stay exact);
- a time-exit / `CLOSE_AT_MARKET` strategy (deadline ticks);
- a position-held-across-bar strategy (maxDrawdown extreme);
- a two-symbol strategy (interleave order across symbols, as today).

## Expected speedup — honest

Big, but **not** bars' ~30x. We still decode each fill-bar's ticks once to build the
index, and we bail to full replay for trailing/always-in strategies. Realistic
**~5–15x, strategy-dependent**: flat/breakout strategies approach bars; trailing-heavy
or always-in ones fall back toward today's speed via the bail. The win is turning
per-fill-bar work from O(ticks) engine-replays into O(log ticks) searches.

## Scope and non-goals

- Backtest only, like tick-fills. No change to the full-tick or plain-bars paths.
- Conservative level first; the aggressive level is a separate, oracle-gated follow-up.
- Lives in `BarResolvedFeed`: replace today's "stream the entire real slice on a fill
  bar" (`settle()` draining `awaitSlice`) with the must-feed resolver. The bail path
  keeps streaming the full slice unchanged.

## Risk

Medium. The mechanism is parity-critical, but it is de-risked by (a) the existing
byte-identical tick-fills oracle, and (b) the conservative level keeping the engine as
the single source of fill truth. The breakers are enumerated and each maps to an
existing conservative guard.
