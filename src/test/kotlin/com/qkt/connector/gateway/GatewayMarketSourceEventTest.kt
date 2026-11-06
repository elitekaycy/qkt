package com.qkt.connector.gateway

import com.qkt.connector.gateway.EventWire.code
import com.qkt.connector.gateway.EventWire.symbol
import com.qkt.marketdata.TickFeed
import java.util.concurrent.CopyOnWriteArrayList
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test

/** An event slot or event `CHAIN:` stream subscribes its whole family by series, live. */
class GatewayMarketSourceEventTest {
    private val fake = FakeGateway(emptyList())
    private val client = GatewayClient(fake.url, "k", httpTimeoutMs = 2_000, retryAttempts = 1)
    private val families = GatewayEventFamilies("KALSHI:", { client.instrumentPages(it) }, {}, 3_600_000L)
    private val source =
        GatewayMarketSource("KALSHI:", fake.url, "k", listing = { client.instruments() }, events = families)
    private val feeds = CopyOnWriteArrayList<TickFeed>()

    init {
        EventWire.list(fake, EventWire.ladder() + EventWire.contract(1, 1).copy(code = "KXB-H1-T1", series = "KXB"))
    }

    @AfterEach
    fun stop() {
        feeds.forEach { it.close() }
        families.close()
        fake.shutdown()
    }

    private fun open(vararg symbols: String): TickFeed {
        val feed = source.liveTicks(symbols.toList()).also { feeds += it }
        val deadline = System.currentTimeMillis() + 5_000
        while (fake.quotes.open == 0) {
            check(System.currentTimeMillis() < deadline) { "no quotes subscription" }
            Thread.sleep(10)
        }
        return feed
    }

    @Test
    fun `a slot and an event chain stream subscribe their family by series, and its contracts are not asked twice`() {
        val feed =
            open(
                "KALSHI:KXS@front.p70",
                "CHAIN:KALSHI.KXS.implied_median.front",
                symbol(1, 2),
                "KALSHI:KXB_H1_T1",
            )

        fake.quotes.send(WireQuote(code(2, 3), "0.40", "0.42", time = 7L))

        val tick = feed.next()!!
        assertThat(tick.symbol).isEqualTo(symbol(2, 3))
        assertThat(tick.price).isEqualByComparingTo("0.41")
        assertThat(fake.quotes.subscriptions.single()).isEqualTo("symbols=KXB-H1-T1&roots=KXS")
    }

    @Test
    fun `an event contract quoted on one side only is priced at that side`() {
        val feed = open("KALSHI:KXS@front.atm")

        fake.quotes.send(WireQuote(code(1, 4), ask = "0.03", time = 8L))

        val tick = feed.next()!!
        assertThat(tick.price).isEqualByComparingTo("0.03")
        assertThat(tick.bid).isNull()
    }

    @Test
    fun `only this account's event chain streams are its own, and an unlisted family fails the subscription`() {
        assertThat(source.supports("CHAIN:KALSHI.KXS.overround.next")).isTrue()
        assertThat(source.supports("CHAIN:KALSHI.BTC_USDC.atm_iv.30d")).isFalse()
        assertThat(source.supports("CHAIN:DERIBIT.KXS.overround.next")).isFalse()
        assertThatThrownBy { source.liveTicks(listOf("KALSHI:NOPE@front.atm")) }
            .hasMessageContaining("gateway lists no event contracts of KALSHI:NOPE")
    }

    @Test
    fun `a quote with no mark is priced on one side only for a contract that may quote that way`() {
        val oneSided = WireQuote(code(1, 1), bid = "0.20", time = 9L)

        assertThat(gatewayQuoteTick(symbol(1, 1), oneSided, oneSided = true)?.price).isEqualByComparingTo("0.20")
        assertThat(gatewayQuoteTick(symbol(1, 1), oneSided)).isNull()
        assertThat(gatewayQuoteTick(symbol(1, 1), WireQuote(code(1, 1), time = 9L), oneSided = true)).isNull()
    }
}
