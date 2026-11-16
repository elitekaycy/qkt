package com.qkt.connector.mt5

import com.qkt.common.FixedClock
import com.qkt.common.Side
import com.qkt.connector.mt5.Mt5RoutedGateway.Companion.deal
import com.qkt.marketdata.MarketPriceProvider
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test

/** Engine quotes before the venue tick, and which closing deals price a close the ack names loosely. */
class MT5CloseVenueTruthPricesTest {
    private val now = 1_800_000_000_000L
    private val gateway = Mt5RoutedGateway()
    private val profile = MT5DefaultProfiles.exness.copy(gatewayUrl = gateway.url, retryAttempts = 0)
    private val books = MT5BrokerState(profile)
    private val client = MT5Client(gateway.url, MT5ServerTimeZone.UTC, httpTimeoutMs = 2_000L, retryAttempts = 0)

    @AfterEach
    fun stop() = gateway.close()

    private class Quotes(
        val execution: BigDecimal?,
        val last: BigDecimal?,
    ) : MarketPriceProvider {
        override fun lastPrice(symbol: String): BigDecimal? = last

        override fun executionPrice(
            symbol: String,
            side: Side,
        ): BigDecimal? = execution
    }

    private fun truth(quotes: MarketPriceProvider? = null) =
        MT5CloseVenueTruth(client, FixedClock(time = now), books, quotes, 100L)

    private fun ack(
        order: Long,
        deal: Long,
    ) = MT5OrderResult(retcode = 10009, order = order, deal = deal, price = BigDecimal.ZERO, comment = "")

    @Test
    fun `the engine's side quote prices a close before its last trade and the venue tick`() {
        gateway.on("/symbol_info_tick/XAUUSDm", """{"bid":2400.1,"ask":2400.3,"time":1}""")

        val sideQuote = truth(Quotes(BigDecimal("2401"), BigDecimal("2402")))
        val lastOnly = truth(Quotes(BigDecimal.ZERO, BigDecimal("2402")))
        val neither = truth(Quotes(null, BigDecimal.ZERO))

        assertThat(sideQuote.marketClosePrice("EXNESS:XAUUSD", "XAUUSDm", Side.SELL)).isEqualByComparingTo("2401")
        assertThat(lastOnly.marketClosePrice("EXNESS:XAUUSD", "XAUUSDm", Side.SELL)).isEqualByComparingTo("2402")
        assertThat(neither.marketClosePrice("EXNESS:XAUUSD", "XAUUSDm", Side.BUY)).isEqualByComparingTo("2400.3")
        assertThat(gateway.calls("/symbol_info_tick")).hasSize(1)
    }

    @Test
    fun `an ack naming only its order is priced by that order's closing deals`() {
        books.positionBook.setOpenedAt(9001L, now - 60_000L)
        gateway.on(
            "/history_deals_get",
            listOf(
                deal(1, 9001, entry = 0, volume = "0.2", price = "2390", timeMs = now - 60_000L),
                deal(2, 9001, entry = 1, volume = "0.1", price = "2410", type = 1, timeMs = now),
                deal(3, 9001, entry = 1, volume = "0.1", price = "2420", type = 1, timeMs = now),
            ).joinToString(",", "[", "]"),
        )

        val byOrder = truth().venueTruthForPositionClose(9001L, ack(order = 3L, deal = 0L), now, positionClosed = false)
        val byTime = truth().venueTruthForPositionClose(9001L, ack(order = 99L, deal = 0L), now, positionClosed = false)
        val unnamed = truth().venueTruthForPositionClose(9001L, ack(order = 0L, deal = 0L), now + 60_000L, false)

        assertThat(byOrder.closingDealPrice).isEqualByComparingTo("2420")
        assertThat(byTime.closingDealPrice).`as`("no deal of order 99: both recent exits").isEqualByComparingTo("2415")
        assertThat(unnamed.closingDealPrice).`as`("every exit predates the close").isNull()
        assertThat(gateway.calls("/history_deals_get").first()).contains("from_date=2027-01-14T07:59:00Z")
    }

    @Test
    fun `a named deal not yet in history prices nothing and an unreadable history books nothing`() {
        gateway.on(
            "/history_deals_get",
            "[${deal(2, 9001, entry = 1, volume = "0.1", price = "2410", type = 1, timeMs = now)}]",
        )

        val pending = truth().venueTruthForPositionClose(9001L, ack(order = 0L, deal = 7L), now, positionClosed = false)
        assertThat(pending.closingDealPrice).isNull()

        gateway.fail("/history_deals_get", 500)
        val unreadable =
            truth().venueTruthForPositionClose(
                9001L,
                ack(order = 0L, deal = 7L),
                now,
                positionClosed = true,
            )
        assertThat(unreadable.closingDealPrice).isNull()
        assertThat(unreadable.costs).isEqualByComparingTo("0")
    }

    @Test
    fun `booking a closed position's costs forgets when it opened`() {
        books.positionBook.setOpenedAt(9001L, now - 1L)
        books.positionBook.setOpenedAt(9002L, now - 1L)
        val open = truth().bookVenueCloseCosts(9002L, emptyList(), positionClosed = false)
        val closed = truth().bookVenueCloseCosts(9001L, emptyList(), positionClosed = true)

        assertThat(books.positionBook.openedAt(9001L)).isNull()
        assertThat(books.positionBook.openedAt(9002L)).isEqualTo(now - 1L)
        assertThat(listOf(open, closed)).allSatisfy { assertThat(it).isEqualByComparingTo("0") }
    }
}
