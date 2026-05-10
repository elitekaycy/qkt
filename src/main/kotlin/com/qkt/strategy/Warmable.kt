package com.qkt.strategy

/** Declares a strategy's warmup requirement. The warmer reads this before activating. */
interface Warmable {
    val warmup: WarmupSpec
}
