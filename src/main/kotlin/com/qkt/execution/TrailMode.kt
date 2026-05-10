package com.qkt.execution

/** How a trailing-stop's trail distance is interpreted — fixed points or percent of price. */
enum class TrailMode {
    /** Trail by an absolute price delta (same units as the symbol's quote). */
    ABSOLUTE,

    /** Trail by a percentage of the current price; trailAmount must be in (0, 100]. */
    PERCENT,
}
