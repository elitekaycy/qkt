package com.qkt.connector.mt5

import com.qkt.connector.mt5.MT5InterchangeableOutcomes.InFlight
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class MT5InterchangeableOutcomesTest {
    private val sentAtMs = 1_790_160_905_000L

    private fun inFlight(
        tier: Int,
        startedAtMs: Long = sentAtMs + tier * 100L,
        tp: String = "4316.252",
    ) = InFlight(
        orderId = "dsl-gold_scale_burst_fixed--8-stack-tier$tier-entry",
        placement =
            MT5OrderRequest(
                symbol = "XAUUSDm",
                volume = BigDecimal("0.05"),
                type = "BUY",
                sl = BigDecimal("4301.152"),
                tp = BigDecimal(tp),
                magic = 918892,
                comment = "dsl-gold_scale_burst_fixed--8-stack-tier$tier-entry",
            ),
        startedAtMs = startedAtMs,
        brokerSymbol = "XAUUSDm",
    )

    private fun position(
        ticket: Long,
        openTime: Long,
        priceOpen: String = "4316.024",
        tp: String = "4316.252",
    ) = MT5Position(
        ticket = ticket,
        symbol = "XAUUSDm",
        type = 0,
        volume = BigDecimal("0.05"),
        priceOpen = BigDecimal(priceOpen),
        sl = BigDecimal("4301.152"),
        tp = BigDecimal(tp),
        profit = BigDecimal.ZERO,
        magic = 918892,
        openTime = openTime,
        comment = "dsl-gold_scale_burst_fixe",
    )

    private val live =
        listOf(
            position(3270424108L, sentAtMs + 8_144L, priceOpen = "4315.923"),
            position(3270423617L, sentAtMs + 2_631L),
            position(3270424046L, sentAtMs + 7_125L, priceOpen = "4316.193"),
        )
    private val legs = listOf(inFlight(7), inFlight(8), inFlight(9))

    @Test
    fun `three unanswered identical legs pair one-to-one with three look-alike positions in send order`() {
        val picks = legs.map { MT5InterchangeableOutcomes.pick(it, live, legs) }

        assertThat(picks.map { it?.ticket }).containsExactly(3270423617L, 3270424046L, 3270424108L)
    }

    @Test
    fun `one unanswered order never claims one of two look-alikes`() {
        val alone = listOf(inFlight(7))

        assertThat(MT5InterchangeableOutcomes.pick(alone.single(), live.take(2), alone)).isNull()
    }

    @Test
    fun `fewer look-alikes than unanswered orders stays unresolved`() {
        assertThat(MT5InterchangeableOutcomes.pick(legs.first(), live.take(2), legs)).isNull()
    }

    @Test
    fun `look-alikes holding different protection are not interchangeable`() {
        val mixed = live.take(2) + position(3270424108L, sentAtMs + 8_144L, tp = "4316.400")

        assertThat(MT5InterchangeableOutcomes.pick(legs.first(), mixed, legs)).isNull()
    }

    @Test
    fun `a look-alike opened before the first send blocks the pairing`() {
        val withOrphan = live.take(2) + position(3269990149L, sentAtMs - 60_000L)

        assertThat(MT5InterchangeableOutcomes.pick(legs.first(), withOrphan, legs)).isNull()
    }

    @Test
    fun `unanswered orders of another shape are not siblings`() {
        val otherShape = listOf(inFlight(7), inFlight(8), inFlight(9, tp = "4316.400"))

        assertThat(MT5InterchangeableOutcomes.pick(otherShape.first(), live, otherShape)).isNull()
    }

    @Test
    fun `a single match is left to the ordinary single-match path`() {
        assertThat(MT5InterchangeableOutcomes.pick(legs.first(), live.take(1), legs.take(1))).isNull()
    }
}
