package com.qkt.app

import com.qkt.broker.BrokerPositionTicket
import com.qkt.common.FixedClock
import com.qkt.common.Side
import com.qkt.positions.LegRole
import com.qkt.positions.Position
import com.qkt.positions.PositionLeg
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class AdoptedLegsTest {
    private val adopted = AdoptedLegs(FixedClock(time = 7_000L))

    private fun ticket(
        id: String,
        side: Side,
        qty: String,
        openedAt: Long?,
        stop: String? = null,
        target: String? = null,
    ) = BrokerPositionTicket(
        ticket = id,
        symbol = "XAUUSD",
        side = side,
        qty = BigDecimal(qty),
        entryPrice = BigDecimal("2000"),
        currentPrice = null,
        profit = null,
        swap = null,
        openedAt = openedAt,
        comment = null,
        stopLoss = stop?.let(::BigDecimal),
        takeProfit = target?.let(::BigDecimal),
    )

    private fun leg(
        id: String,
        side: Side,
        qty: String,
        entry: String,
        openedAt: Long,
        ticket: String? = null,
    ) = PositionLeg(
        "gold-XAUUSD-reconciled-$id",
        "XAUUSD",
        side,
        BigDecimal(qty),
        BigDecimal(entry),
        openedAt,
        LegRole.INDEPENDENT,
        brokerTicket = ticket,
    )

    @Test
    fun `each venue ticket becomes an independent leg carrying its ticket and open time`() {
        val legs =
            adopted.from(
                "gold",
                "XAUUSD",
                listOf(
                    ticket("9001", Side.BUY, "0.10", openedAt = 1_000L, stop = "1990", target = "2050"),
                    ticket("9002", Side.SELL, "-0.20", openedAt = null, stop = "0", target = "0"),
                    ticket("9003", Side.BUY, "0.30", openedAt = 2_000L, stop = "1980"),
                    ticket("9004", Side.BUY, "0.40", openedAt = 3_000L, target = "2100"),
                ),
                brokerForSymbol = listOf(Position("XAUUSD", BigDecimal("9"), BigDecimal("1"))),
            )

        assertThat(legs).containsExactly(
            leg("9001", Side.BUY, "0.10", "2000", 1_000L, "9001"),
            leg("9002", Side.SELL, "0.20", "2000", 7_000L, "9002"),
            leg("9003", Side.BUY, "0.30", "2000", 2_000L, "9003"),
            leg("9004", Side.BUY, "0.40", "2000", 3_000L, "9004"),
        )
    }

    @Test
    fun `a venue without tickets adopts each net position as a ticketless leg`() {
        val legs =
            adopted.from(
                "gold",
                "XAUUSD",
                emptyList(),
                listOf(
                    Position("XAUUSD", BigDecimal("0.5"), BigDecimal("1995")),
                    Position("XAUUSD", BigDecimal("-0.25"), BigDecimal("2005")),
                ),
            )

        assertThat(legs).containsExactly(
            leg("0.5", Side.BUY, "0.5", "1995", 7_000L),
            leg("-0.25", Side.SELL, "0.25", "2005", 7_000L),
        )
    }

    @Test
    fun `nothing at the venue adopts nothing`() {
        assertThat(adopted.from("gold", "XAUUSD", emptyList(), emptyList())).isEmpty()
    }
}
