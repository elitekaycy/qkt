package com.qkt.connector.mt5

import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** The rules that decide what a lost acknowledgement turned into at the venue. */
class MT5UnknownOutcomeMatchingTest {
    private val sentAt = 1_800_000_000_000L
    private val placement =
        MT5OrderRequest(symbol = "BTCUSDm", volume = BigDecimal("0.02"), type = "BUY", magic = 7, comment = "dsl-s--4")

    private fun deal(
        volume: String,
        type: Int = 0,
        timeMs: Long = sentAt + 1_500L,
        positionTicket: Long = 900L,
        orderTicket: Long = 800L,
        ticket: Long = 700L,
        price: String = "100",
    ) = MT5Deal(
        ticket = ticket,
        orderTicket = orderTicket,
        positionTicket = positionTicket,
        symbol = "BTCUSDm",
        type = type,
        entry = 0,
        volume = BigDecimal(volume),
        price = BigDecimal(price),
        profit = BigDecimal.ZERO,
        commission = BigDecimal.ZERO,
        swap = BigDecimal.ZERO,
        fee = BigDecimal.ZERO,
        magic = 7,
        comment = "dsl-s--4",
        timeMs = timeMs,
    )

    @Test
    fun `deals are the outcome when they match the side, add up to the size, and one is near the send time`() {
        val slices = listOf(deal("0.01"), deal("0.01", timeMs = sentAt + 90_000L))

        assertThat(MT5UnknownOutcomeMatching.matchesUnknownDeals(slices, placement, sentAt)).isTrue()
    }

    @Test
    fun `a partial size, the opposite side, or deals far from the send time are not the outcome`() {
        assertThat(MT5UnknownOutcomeMatching.matchesUnknownDeals(listOf(deal("0.01")), placement, sentAt)).isFalse()
        assertThat(
            MT5UnknownOutcomeMatching.matchesUnknownDeals(listOf(deal("0.02", type = 1)), placement, sentAt),
        ).isFalse()
        assertThat(
            MT5UnknownOutcomeMatching.matchesUnknownDeals(
                listOf(deal("0.02", timeMs = sentAt + 61_000L)),
                placement,
                sentAt,
            ),
        ).isFalse()
    }

    @Test
    fun `venue times in seconds and in milliseconds are both understood, and a missing time never matches`() {
        assertThat(MT5UnknownOutcomeMatching.isNearPlacement(sentAt / 1_000L, sentAt)).isTrue()
        assertThat(MT5UnknownOutcomeMatching.isNearPlacement(sentAt + 59_000L, sentAt)).isTrue()
        assertThat(MT5UnknownOutcomeMatching.isNearPlacement(sentAt + 60_001L, sentAt)).isFalse()
        assertThat(MT5UnknownOutcomeMatching.isNearPlacement(0L, sentAt)).isFalse()
    }

    @Test
    fun `deals group by position, then order, then their own ticket`() {
        assertThat(MT5UnknownOutcomeMatching.dealPositionKey(deal("0.01"))).isEqualTo(900L)
        assertThat(MT5UnknownOutcomeMatching.dealPositionKey(deal("0.01", positionTicket = 0L))).isEqualTo(800L)
        assertThat(
            MT5UnknownOutcomeMatching.dealPositionKey(deal("0.01", positionTicket = 0L, orderTicket = 0L)),
        ).isEqualTo(700L)
    }

    @Test
    fun `the booked price of sliced fills is weighted by size`() {
        val price =
            MT5UnknownOutcomeMatching.weightedDealPrice(
                listOf(deal("0.01", price = "100"), deal("0.03", price = "104")),
            )

        assertThat(price).isEqualByComparingTo("103")
        assertThat(MT5UnknownOutcomeMatching.weightedDealPrice(emptyList())).isNull()
    }

    @Test
    fun `a resting order reported with MT5's numeric type is recognised as the named placement`() {
        // /orders says "type": 2 for a buy limit; the placement said BUY_LIMIT.
        assertThat(MT5UnknownOutcomeMatching.sameOrderType("2", "BUY_LIMIT")).isTrue()
        assertThat(MT5UnknownOutcomeMatching.sameOrderType("5", "SELL_STOP")).isTrue()
        assertThat(MT5UnknownOutcomeMatching.sameOrderType("BUY_LIMIT", "buy_limit")).isTrue()
        assertThat(MT5UnknownOutcomeMatching.sameOrderType("3", "BUY_LIMIT")).isFalse()
        assertThat(MT5UnknownOutcomeMatching.sameOrderType("42", "BUY_LIMIT")).isFalse()
    }
}
