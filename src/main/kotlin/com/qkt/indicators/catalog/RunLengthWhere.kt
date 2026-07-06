package com.qkt.indicators.catalog

import com.qkt.indicators.Indicator
import java.math.BigDecimal

/**
 * Consecutive-bar dwell counter for a boolean state.
 *
 * Each `true` input increments the current dwell length; each `false` input resets it to zero.
 * This models path-dependent state duration such as "how many consecutive bars has volatility
 * been calm?" without approximating it as a rolling fraction. The value is defined after the
 * first condition sample.
 */
class RunLengthWhere : Indicator<Boolean> {
    private var count = 0
    private var ready = false
    private var lastValue: BigDecimal? = null

    override val warmupBars: Int = 1

    override val isReady: Boolean
        get() = ready

    override fun update(input: Boolean) {
        count = if (input) count + 1 else 0
        lastValue = BigDecimal(count)
        ready = true
    }

    override fun value(): BigDecimal? = if (ready) lastValue else null
}
