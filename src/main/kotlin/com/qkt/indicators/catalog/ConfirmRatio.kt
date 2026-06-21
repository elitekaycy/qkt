package com.qkt.indicators.catalog

import com.qkt.common.Money
import com.qkt.indicators.MultiIndicator
import java.math.BigDecimal

/**
 * Cross-symbol confirmation ratio: the fraction of peer series whose return over the last
 * [period] bars is the same sign as the signal series' return over the same window, in `[0, 1]`.
 *
 * Each bar feeds an aligned tuple `[signal, peer1, …, peerN]` — the signal first. A low ratio
 * means the signal moved while the peers did not confirm (an idiosyncratic, likely-noise move
 * to fade); a high ratio means the basket moved together (a broad factor move). Polarity for
 * inverse pairs folds into the caller's expression: pass `-usdchf.close` so the peer's return
 * sign is the negation of USDCHF's, rather than carrying a separate polarity list.
 *
 * e.g. signal +2 over the window, peer1 +2, peer2 −2 → one of two peers confirms → 0.5.
 */
class ConfirmRatio(
    private val period: Int,
    private val peerCount: Int,
) : MultiIndicator {
    init {
        require(period >= 1) { "ConfirmRatio.period must be >= 1: $period" }
        require(peerCount >= 1) { "ConfirmRatio needs at least 1 peer: $peerCount" }
    }

    // Each row is the aligned tuple [signal, peer1, …, peerN]. Capacity bounded to period+1.
    private val rows: ArrayDeque<List<BigDecimal>> = ArrayDeque(period + 1)

    override val warmupBars: Int = period + 1

    override val isReady: Boolean
        get() = rows.size >= period + 1

    override fun update(values: List<BigDecimal>) {
        require(values.size == peerCount + 1) {
            "ConfirmRatio expects ${peerCount + 1} values (signal + $peerCount peers), got ${values.size}"
        }
        rows.addLast(values)
        if (rows.size > period + 1) rows.removeFirst()
    }

    override fun value(): BigDecimal? {
        if (!isReady) return null
        val first = rows.first()
        val last = rows.last()
        val signalSign = last[0].subtract(first[0]).signum()
        var confirming = 0
        for (i in 1..peerCount) {
            if (last[i].subtract(first[i]).signum() == signalSign) confirming++
        }
        return BigDecimal(confirming).divide(BigDecimal(peerCount), Money.CONTEXT)
    }
}
