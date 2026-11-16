package com.qkt.connector.gateway

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test

/**
 * A one-shot quote of an event contract with one side of its book empty, as thin event markets are: seen live on
 * the Kalshi demo (bid 0.01, no offer), where it refused every `qkt bot` call on the market.
 */
class GatewayDirectEventQuoteTest {
    private val event = "KXDEMOFUTHOURLY-26NOV16H14-UP"
    private val perp = "SOL_USDC-PERPETUAL"
    private val fake =
        FakeGateway(listOf(event, perp)).apply {
            instruments[event] = WireInstrument(event, "event", "USD", "1", "0.01", "0.01", "0.01")
            instruments[perp] = WireInstrument(perp, "perpetual", "USDC", "1", "0.001", "0.1", "0.1")
        }
    private val venue =
        GatewayDirectVenue(
            name = "kalshi",
            prefix = "KALSHI:",
            client = GatewayClient(fake.url, "k", httpTimeoutMs = 2_000, retryAttempts = 2),
            baseUrl = fake.url,
            apiKey = "k",
        )
    private val symbol = "KALSHI:KXDEMOFUTHOURLY_26NOV16H14_UP"

    @AfterEach
    fun stop() = fake.shutdown()

    @Test
    fun `an event contract with no offer is quoted at its bid and its payout`() {
        fake.quotes.greeting = WireQuote(event, bid = "0.01", time = 7)

        val facts = venue.facts(symbol)
        assertThat(facts.bid).isEqualByComparingTo("0.01")
        assertThat(facts.ask).isEqualByComparingTo("1")

        val tick = venue.tick(symbol)!!
        assertThat(tick.bid).isEqualByComparingTo("0.01")
        assertThat(tick.ask).isEqualByComparingTo("1")
    }

    @Test
    fun `an event contract with no bid is quoted at zero and its offer`() {
        fake.quotes.greeting = WireQuote(event, ask = "0.97", time = 7)

        val facts = venue.facts(symbol)
        assertThat(facts.bid).isEqualByComparingTo("0")
        assertThat(facts.ask).isEqualByComparingTo("0.97")
    }

    @Test
    fun `any other instrument with one side and no mark is still refused`() {
        fake.quotes.greeting = WireQuote(perp, bid = "105.4", time = 7)

        assertThatThrownBy { venue.facts("KALSHI:SOL_USDC_PERPETUAL") }
            .hasMessageContaining("one-sided book")
    }
}
