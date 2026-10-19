package com.qkt.connector.gateway

import com.qkt.common.Side
import com.qkt.execution.OrderRequest
import com.qkt.execution.TimeInForce
import java.math.BigDecimal
import kotlin.concurrent.thread
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test

class GatewayDirectVenueTest {
    private val perp = "SOL_USDC-PERPETUAL"
    private val option = "BTC_USDC-25DEC26-92000-C"
    private val fake =
        FakeGateway(listOf(perp, option)).apply {
            instruments[perp] = WireInstrument(perp, "perpetual", "USDC", "1", "0.001", "0.1", "0.1")
        }
    private val venue =
        GatewayDirectVenue(
            name = "deribit",
            prefix = "DERIBIT:",
            client = GatewayClient(fake.url, "k", httpTimeoutMs = 2_000, retryAttempts = 2),
            baseUrl = fake.url,
            apiKey = "k",
            pollTimeoutMs = 2_000,
            pollMs = 20,
        )

    @AfterEach
    fun stop() = fake.shutdown()

    private fun market(
        side: Side = Side.BUY,
        qty: String = "2",
        symbol: String = "DERIBIT:SOL_USDC_PERPETUAL",
    ) = OrderRequest.Market("bot-a-1", symbol, side, BigDecimal(qty), TimeInForce.GTC, 1_000L, "a")

    private fun limit(price: String) =
        OrderRequest.Limit(
            "bot-a-2",
            "DERIBIT:SOL_USDC_PERPETUAL",
            Side.BUY,
            BigDecimal("2"),
            BigDecimal(price),
            TimeInForce.GTC,
            1_000L,
            "a",
        )

    /** Fills the first order the gateway receives, as the venue would. */
    private fun fillOnArrival(price: String) =
        thread {
            while (fake.submits.isEmpty()) Thread.sleep(5)
            val body = fake.submits.first()
            fake.act { fill(body.clientOrderId, "f1", body.quantity, price, FakeGateway.TIME) }
        }

    @Test
    fun `market buy places and reports the fill`() {
        val filler = fillOnArrival("105.5")

        val result = venue.place(market(), null, null, "bot-a-1")
        filler.join()

        assertThat(result.ok).isTrue
        assertThat(result.state).isEqualTo("filled")
        assertThat(result.price).isEqualByComparingTo("105.5")
        assertThat(result.deal).isEqualTo("f1")
        assertThat(fake.submits.single().symbol).isEqualTo(perp)
        assertThat(fake.submits.single().type).isEqualTo("market")
    }

    @Test
    fun `limit buy reports working and snaps to the tick`() {
        val result = venue.place(limit("104.12345"), null, null, "bot-a-2")

        assertThat(result.ok).isTrue
        assertThat(result.state).isEqualTo("working")
        assertThat(fake.submits.single().limitPrice).isEqualTo("104.123")
    }

    @Test
    fun `rejected order is not ok and carries the venue reason`() {
        fake.killed = true

        val result = venue.place(market(), null, null, "bot-a-1")

        assertThat(result.ok).isFalse
        assertThat(result.error).contains("kill_switch")
    }

    @Test
    fun `sl on a gateway is refused and nothing is sent`() {
        assertThatThrownBy { venue.place(market(), BigDecimal("100"), null, "bot-a-1") }
            .hasMessageContaining("a gateway venue holds no stop for a position")
            .hasMessageContaining("qkt bot session start")
        assertThat(fake.submits).isEmpty()
    }

    @Test
    fun `gtd is refused`() {
        val gtd = limit("104").copy(timeInForce = TimeInForce.GTD, expiresAt = 5_000L)

        assertThatThrownBy { venue.place(gtd, null, null, "bot-a-2") }.hasMessageContaining("no good-till-date")
        assertThat(fake.submits).isEmpty()
    }

    @Test
    fun `close sends a reduce close for the whole position`() {
        val filler = fillOnArrival("105.5")
        venue.place(market(), null, null, "bot-a-1")
        filler.join()

        val position = venue.positions()!!.single()
        val result = venue.close(position, null, "bot-close-1")

        assertThat(position.side).isEqualTo("BUY")
        assertThat(position.lots).isEqualByComparingTo("2")
        assertThat(result.ok).isTrue
        assertThat(result.price).isEqualByComparingTo(CLOSE_PRICE)
        assertThat(venue.positions()).isEmpty()
    }

    @Test
    fun `partial close sends the quantity`() {
        val filler = fillOnArrival("105.5")
        venue.place(market(), null, null, "bot-a-1")
        filler.join()

        venue.close(venue.positions()!!.single(), BigDecimal("0.5"), "bot-close-1")

        assertThat(venue.positions()!!.single().lots).isEqualByComparingTo("1.5")
    }

    @Test
    fun `cancel ends a working order`() {
        venue.place(limit("100"), null, null, "bot-a-2")
        val ticket = venue.pendingOrders()!!.single().ticket

        val result = venue.cancel(ticket)

        assertThat(result.ok).isTrue
        assertThat(venue.pendingOrders()).isEmpty()
    }

    @Test
    fun `facts carry contract size steps currency and the quote`() {
        fake.quotes.greeting = WireQuote(perp, bid = "105.4", ask = "105.6", time = 7)

        val facts = venue.facts("DERIBIT:SOL_USDC_PERPETUAL")

        assertThat(facts.bid).isEqualByComparingTo("105.4")
        assertThat(facts.ask).isEqualByComparingTo("105.6")
        assertThat(facts.contractSize).isEqualByComparingTo("1")
        assertThat(facts.volumeStep).isEqualByComparingTo("0.1")
        assertThat(facts.volumeMin).isEqualByComparingTo("0.1")
        assertThat(facts.quoteCurrency).isEqualTo("USDC")
        assertThat(facts.accountCurrency).isEqualTo("USDC")
        assertThat(facts.equity).isEqualByComparingTo("1000")
        assertThat(facts.digits).isEqualTo(3)
    }

    @Test
    fun `a listing whose minimum is below its step trades in steps of the minimum`() {
        // Deribit's USDC options as a gateway lists them: volume_step 1 (the contract size), volume_min 0.01.
        fake.instruments[option] =
            WireInstrument(option, "option", "USDC", "1", "5", "1.0", "0.01", underlying = "BTC_USDC")
        fake.quotes.greeting = WireQuote(option, bid = "1260", ask = "1340", time = 7)

        val facts = venue.facts("DERIBIT:BTC_USDC_25DEC26_92000_C")

        assertThat(facts.volumeStep).isEqualByComparingTo("0.01")
        assertThat(facts.volumeMin).isEqualByComparingTo("0.01")
    }

    @Test
    fun `option and perpetual symbols map to venue codes`() {
        assertThat(venue.venueSymbol("DERIBIT:BTC_USDC_25DEC26_92000_C")).isEqualTo(option)
        assertThat(venue.venueSymbol("DERIBIT:SOL_USDC_PERPETUAL")).isEqualTo(perp)
        assertThatThrownBy { venue.venueSymbol("DERIBIT:ETH_USDC_PERPETUAL") }
            .hasMessageContaining("gateway deribit does not list DERIBIT:ETH_USDC_PERPETUAL")
    }

    @Test
    fun `a position stop cannot be modified on a gateway`() {
        val filler = fillOnArrival("105.5")
        venue.place(market(), null, null, "bot-a-1")
        filler.join()

        assertThatThrownBy { venue.modify(venue.positions()!!.single(), BigDecimal("100"), null) }
            .hasMessageContaining("holds no stop or target on a position")
    }
}
