package com.qkt.instrument

/**
 * How a continuous futures series joins consecutive contracts. Every mode keeps one contract at raw
 * prices and shifts the others onto it: by default the first measured one, so the series is adjusted
 * forward (history is never rewritten, each new contract is shifted onto the level of the series so
 * far); with [RollPolicy.anchor] the declared one, so contracts before it are adjusted backward.
 */
enum class PriceAdjustment {
    /** Raw contract prices; the roll gap shows in the series. */
    NONE,

    /** Additive: every later contract is shifted by the cumulative price gap. Distances are unchanged. */
    PANAMA,

    /** Multiplicative: every later contract is scaled by the cumulative price ratio. Requires positive prices. */
    RATIO,
}
