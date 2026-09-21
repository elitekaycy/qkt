package com.qkt.connector.mt5

import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class MT5UnknownVenueMatcherTest {
    private val books = MT5BrokerState(MT5DefaultProfiles.exness.copy(gatewayUrl = "http://127.0.0.1:1"))
    private val matcher = MT5UnknownVenueMatcher(books)
    private val sentAtMs = 1_700_000_000_000L
    private val placement =
        MT5OrderRequest(
            symbol = "XAUUSDm",
            volume = BigDecimal("0.10"),
            type = "BUY",
            magic = 77,
            comment = "gold-1",
            clientOrderId = "mt5-77-gold-1700000000-12",
        )

    private fun position(
        ticket: Long,
        volume: String = "0.10",
        clientOrderId: String? = placement.clientOrderId,
        openTime: Long = sentAtMs + 500L,
        symbol: String = "XAUUSDm",
    ) = MT5Position(
        ticket = ticket,
        symbol = symbol,
        type = 0,
        volume = BigDecimal(volume),
        priceOpen = BigDecimal("2400.00"),
        sl = BigDecimal.ZERO,
        tp = BigDecimal.ZERO,
        profit = BigDecimal.ZERO,
        magic = 77,
        openTime = openTime,
        comment = "gold-1",
        clientOrderId = clientOrderId,
    )

    private fun match(vararg positions: MT5Position) =
        matcher.match(emptyList(), positions.toList(), placement, sentAtMs, "XAUUSDm", "gold-1")

    @Test
    fun `a position carrying the placement id and size is the match`() {
        val result = match(position(500L))

        assertThat(result.matches).containsExactly(UnknownVenueMatch.Position(position(500L)))
    }

    @Test
    fun `the placement id wins over an older look-alike that only matches by comment and timing`() {
        val lookAlike = position(499L, clientOrderId = null)

        val result = match(lookAlike, position(500L))

        assertThat(result.positionCandidates).hasSize(2)
        assertThat(result.matches).containsExactly(UnknownVenueMatch.Position(position(500L)))
    }

    @Test
    fun `the same id at a different size is a candidate but never a match`() {
        val result = match(position(500L, volume = "0.30"))

        assertThat(result.positionCandidates).hasSize(1)
        assertThat(result.matches).isEmpty()
    }

    @Test
    fun `without an id the comment match must also sit near the send time`() {
        val near = match(position(500L, clientOrderId = null))
        val stale = match(position(500L, clientOrderId = null, openTime = sentAtMs - 61_000L))

        assertThat(near.matches).hasSize(1)
        assertThat(stale.matches).isEmpty()
    }

    @Test
    fun `tickets the broker already owns and other symbols are not candidates`() {
        books.positionBook.attribute(500L, MT5TicketMeta("earlier-order", "gold_trend"))

        val result = match(position(500L), position(501L, symbol = "EURUSDm"))

        assertThat(result.positionCandidates).isEmpty()
        assertThat(result.matches).isEmpty()
    }
}
