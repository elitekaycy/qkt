package com.qkt.connector.mt5

import com.qkt.broker.CloseReason
import com.qkt.common.Side
import com.qkt.connector.mt5.Mt5RoutedGateway.Companion.deal
import com.qkt.connector.mt5.Mt5RoutedGateway.Companion.position
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test

/** Deal, resting-order and position-ticket rows translated into engine terms. */
class MT5BrokerVenueReadsRowsTest {
    private val gateway = Mt5RoutedGateway()
    private val profile = MT5DefaultProfiles.exness.copy(gatewayUrl = gateway.url, retryAttempts = 0)
    private val books = MT5BrokerState(profile)
    private val reads =
        MT5BrokerVenueReads(
            profile,
            MT5Client(gateway.url, MT5ServerTimeZone.UTC, httpTimeoutMs = 2_000L, retryAttempts = 0),
            MT5Symbol(profile.symbolPolicy),
            books.positionBook,
            books.symbolMeta,
        )

    @AfterEach
    fun stop() = gateway.close()

    @Test
    fun `only trade deals are history and only a closing deal has a close reason`() {
        val bookings =
            listOf(
                deal(1, 7, entry = 0, volume = "0.1", price = "2400", comment = "x-sl", reason = 4),
                deal(2, 7, entry = 1, volume = "0.1", price = "2410", type = 1, reason = 4),
                deal(3, 0, entry = 1, volume = "0.1", price = "2410", type = 1, comment = "s-tp"),
                deal(4, 0, entry = 0, volume = "0", price = "0", type = 2),
            ).joinToString(",", "[", "]")
        gateway.on("/history_deals_get", bookings.replace(""""order":3,""", """"order":0,"""))

        val deals = reads.deals(0L, 1L)

        assertThat(deals.map { it.dealTicket }).containsExactly("1", "2", "3")
        assertThat(deals.map { it.side }).containsExactly(Side.BUY, Side.SELL, Side.SELL)
        assertThat(deals.map { it.closeReason }).containsExactly(null, CloseReason.STOP, CloseReason.TAKE_PROFIT)
        assertThat(deals.map { it.positionTicket }).containsExactly("7", "7", null)
        assertThat(deals.map { it.orderTicket }).containsExactly("1", "2", null)
        assertThat(deals.map { it.symbol }).containsOnly("EXNESS:XAUUSD")
        assertThat(deals.map { it.realizedSource }).containsOnly("venue")
    }

    @Test
    fun `a failed deal read is an error, never no deals`() {
        gateway.fail("/history_deals_get", 502)

        assertThatThrownBy { reads.deals(0L, 1L) }.hasMessageContaining("deals: gateway read failed (HTTP 502")
    }

    @Test
    fun `resting orders take their side from the type and keep only real times`() {
        gateway.on(
            "/orders",
            """[{"ticket":5,"symbol":"XAUUSDm","type_str":"BUY_LIMIT","volume_current":"0.2","price_open":"2390",
            "time_setup":1700000000,"time_expiration":1700003600,"magic":10001},
            {"ticket":6,"symbol":"EURUSDm","type_str":"SELL_STOP","volume_current":"1","price_open":"1.05","magic":10001}]""",
        )

        val orders = reads.pendingOrders()

        assertThat(orders.map { it.side }).containsExactly(Side.BUY, Side.SELL)
        assertThat(orders.map { it.symbol }).containsExactly("EXNESS:XAUUSD", "EXNESS:EURUSD")
        assertThat(orders[0].createdAt).isEqualTo(1_700_000_000L)
        assertThat(orders[0].expiresAt).isEqualTo(1_700_003_600L)
        assertThat(orders[1].createdAt).isNull()
        assertThat(orders[1].expiresAt).isNull()
    }

    @Test
    fun `resting orders the venue cannot report read as none`() {
        gateway.fail("/orders", 500)

        assertThat(reads.pendingOrders()).isEmpty()
    }

    @Test
    fun `position tickets carry the protection the engine asked for when it attributed them`() {
        gateway.on(
            "/get_positions",
            listOf(position(1, "0.1"), position(2, "0.2", type = 1)).joinToString(",", "[", "]"),
        )
        books.positionBook.attribute(
            1L,
            MT5TicketMeta("o1", "s1", MT5PositionProtection(BigDecimal("2390"), BigDecimal("2420"))),
        )

        val tickets = reads.positionTickets()

        assertThat(tickets.map { it.side }).containsExactly(Side.BUY, Side.SELL)
        assertThat(tickets[0].requestedStopLoss).isEqualByComparingTo("2390")
        assertThat(tickets[0].requestedTakeProfit).isEqualByComparingTo("2420")
        assertThat(tickets[1].requestedStopLoss).isNull()
        assertThat(tickets[1].requestedTakeProfit).isNull()
    }

    @Test
    fun `a failed position-ticket read is an error naming why`() {
        gateway.fail("/get_positions", 503)

        assertThatThrownBy { reads.positionTickets() }
            .hasMessageContaining("positionTickets: gateway read failed (HTTP 503")
    }

    @Test
    fun `another profile's symbol is not this venue's to describe`() {
        assertThat(reads.instrumentMeta("ICMARKETS:XAUUSD")).isNull()
        assertThat(gateway.requests).isEmpty()
    }
}
