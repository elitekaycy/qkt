package com.qkt.risk

import com.qkt.common.Money
import com.qkt.pnl.PnLProvider
import java.math.BigDecimal

/** Test [PnLProvider] with settable realized/unrealized totals. */
class FakePnL(
    var realized: BigDecimal = Money.ZERO,
    var unrealized: BigDecimal = Money.ZERO,
) : PnLProvider {
    override fun realizedTotal(): BigDecimal = realized

    override fun unrealizedFor(symbol: String): BigDecimal = Money.ZERO

    override fun unrealizedTotal(): BigDecimal = unrealized

    override fun totalPnL(): BigDecimal = realized.add(unrealized)
}

/** Test [com.qkt.common.Clock] with a settable epoch-millis reading. */
class TestClock(
    var t: Long = 0L,
) : com.qkt.common.Clock {
    override fun now(): Long = t
}
