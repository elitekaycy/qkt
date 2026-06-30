package com.qkt.app

/**
 * How a bar's intrabar ticks must be resolved for tick-resolved fills (`--bars --tick-fills`).
 *
 * Given a bar's price range and the live orders on a symbol, the engine decides how few real ticks
 * the replay can get away with feeding while staying byte-identical to a full-tick replay:
 *
 * - [SYNTHETIC] — no order can fill in the bar; emit the synthetic open/low/high/close (no real ticks).
 * - [EXTREMES] — only static stop/limit orders are in range; feed just the bar's new-extreme ticks
 *   plus the close (the first crossing of any static level is necessarily a new-extreme tick).
 * - [ALL_TICKS] — a trailing/composite order or a time-based exit is live; its trigger moves with the
 *   path or fires on time, so the bar must replay every real tick.
 */
enum class IntrabarFill {
    SYNTHETIC,
    EXTREMES,
    ALL_TICKS,
}
