package com.qkt.connector.gateway

import com.qkt.common.FixedClock
import com.qkt.connectivity.DirectEventVenue
import com.qkt.connectivity.MarketFilter
import com.qkt.connector.gateway.EventWire.OPEN_MS
import com.qkt.connector.gateway.EventWire.closeMs
import com.qkt.connector.gateway.EventWire.code
import com.qkt.connector.gateway.EventWire.contract
import com.qkt.connector.gateway.EventWire.symbol
import com.qkt.instrument.SlotSymbol
import kotlin.concurrent.thread
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test

/** `qkt bot`'s event reads on a gateway: the catalog by filters, one event quoted, a slot resolved, a pause refused. */
class GatewayEventReadsTest {
    private val fake = FakeGateway(emptyList())
    private val client = GatewayClient(fake.url, "k", httpTimeoutMs = 2_000, retryAttempts = 1)
    private val clock = FixedClock(OPEN_MS + 60_000L)
    private val reads = GatewayEventReads("KALSHI:", client, fake.url, "k", clock, 500L)

    init {
        EventWire.list(fake, EventWire.ladder())
    }

    @AfterEach
    fun stop() = fake.shutdown()

    /**
     * Sends the H1 ladder (mids 0.90, 0.70, 0.40, 0.10), leaving out strike [missing], once the read's subscription
     * (the [nth] of the test) is open.
     */
    private fun quoteLadder(
        nth: Int,
        missing: Int? = null,
    ) = thread(isDaemon = true) {
        val deadline = System.currentTimeMillis() + 5_000
        while (fake.quotes.open < nth && System.currentTimeMillis() < deadline) Thread.sleep(5)
        listOf("0.90", "0.70", "0.40", "0.10").forEachIndexed { i, mid ->
            val m = mid.toBigDecimal()
            val cent = "0.01".toBigDecimal()
            val quote = WireQuote(code(1, i + 1), (m - cent).toPlainString(), (m + cent).toPlainString(), time = 1L)
            if (i + 1 != missing) fake.quotes.send(quote)
        }
    }

    @Test
    fun `the catalog is read through the listing filters, open ones only when asked`() {
        EventWire.list(fake, EventWire.ladder().map { if (it.code == code(2, 4)) contract(2, 4, "disabled") else it })
        fake.instruments[code(2, 3)] = contract(2, 3).copy(strikeType = null)

        val open = reads.markets(MarketFilter("Financials", "KALSHI:KXS", openOnly = true))
        val all = reads.markets(MarketFilter(null, "KXS", openOnly = false))

        assertThat(open.map { it.symbol }).hasSize(6).doesNotContain(symbol(2, 4), symbol(2, 3))
        assertThat(all.map { it.symbol }).hasSize(7).contains(symbol(2, 4))
        assertThat(all.single { it.symbol == symbol(2, 4) }.trading).isEqualTo("disabled")
        assertThat(all.first().terms.title).isEqualTo("above 100")
        assertThat(all.first().category).isEqualTo("Financials")
        assertThat(reads.markets(MarketFilter("Weather", null, openOnly = false))).isEmpty()
    }

    @Test
    fun `a chain lists every contract of the front event in strike order with its quote`() {
        quoteLadder(1, missing = 4)

        val chain = requireNotNull(reads.chain("KALSHI:KXS", 0))

        assertThat(chain.event).isEqualTo("KXS-H1")
        assertThat(chain.closeAtMs).isEqualTo(closeMs(1))
        assertThat(chain.contracts.map { it.market.symbol })
            .containsExactly(symbol(1, 1), symbol(1, 2), symbol(1, 3), symbol(1, 4))
        assertThat(chain.contracts[1].bid).isEqualByComparingTo("0.69")
        assertThat(chain.contracts[1].ask).isEqualByComparingTo("0.71")
        assertThat(chain.contracts[3].bid).isNull()
        assertThat(reads.chain("KALSHI:KXS", 2)).isNull()
    }

    @Test
    fun `a slot resolves to the contract its selectors pick now, never a paused one`() {
        quoteLadder(1)
        assertThat(reads.follow(SlotSymbol.parse("KALSHI:KXS@front.p70")!!)).isEqualTo(symbol(1, 2))

        fake.instruments[code(1, 2)] = contract(1, 2, trading = "disabled")
        quoteLadder(2)
        assertThat(reads.follow(SlotSymbol.parse("KALSHI:KXS@front.p70")!!)).isEqualTo(symbol(1, 1))
    }

    @Test
    fun `a slot with no event open or no quote to pick by is refused naming why`() {
        assertThatThrownBy { reads.follow(SlotSymbol.parse("KALSHI:KXS@front.top2")!!) }
            .hasMessageContaining("no tradable contract of KXS-H1 has a two-sided quote")
        EventWire.list(fake, (1..4).map { contract(1, it) })
        assertThatThrownBy { reads.follow(SlotSymbol.parse("KALSHI:KXS@next.atm")!!) }
            .hasMessageContaining("KALSHI:KXS@next.atm: KALSHI:KXS has no next event open now")
    }

    @Test
    fun `a paused or closing-out contract is refused by name and any other is not`() {
        EventWire.list(
            fake,
            listOf(contract(1, 1, trading = "disabled"), contract(1, 2, trading = "close_only"), contract(1, 3)) +
                FakeWire.instrument("BTC_USDC-25DEC26-92000-C").copy(trading = "disabled"),
        )

        assertThat(reads.refusal(symbol(1, 1))).contains("paused").contains(symbol(1, 1))
        assertThat(reads.refusal(symbol(1, 2))).contains("only closes out")
        assertThat(reads.refusal(symbol(1, 3))).isNull()
        assertThat(reads.refusal("KALSHI:BTC_USDC_25DEC26_92000_C")).isNull()
    }

    @Test
    fun `a gateway venue spoken to directly offers the event reads`() {
        val venue: DirectEventVenue = GatewayDirectVenue("kalshi", "KALSHI:", client, fake.url, "k", clock = clock)

        assertThat(venue.markets(MarketFilter(null, null, openOnly = true))).hasSize(8)
    }
}
