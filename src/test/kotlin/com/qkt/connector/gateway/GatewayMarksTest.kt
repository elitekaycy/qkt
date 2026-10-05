package com.qkt.connector.gateway

import com.qkt.marketdata.TickFeed
import java.util.concurrent.CopyOnWriteArrayList
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test

/** A gateway account's quoted marks are kept for strategies, and served only when the gateway declares them. */
class GatewayMarksTest {
    private val code = "BTC_USDC-PERPETUAL"
    private val symbol = "DERIBIT:BTC_USDC_PERPETUAL"
    private val minute = 60_000L
    private val fake = FakeGateway(listOf(code))
    private val client = GatewayClient(fake.url, "k", httpTimeoutMs = 500, retryAttempts = 2)
    private val source =
        GatewayMarketSource("DERIBIT:", fake.url, "k", listing = { client.instruments() }) { fake.capabilities }
    private val feeds = CopyOnWriteArrayList<TickFeed>()

    @AfterEach
    fun stop() {
        feeds.forEach { it.close() }
        fake.shutdown()
    }

    private fun quote(
        time: Long,
        mark: String?,
        index: String?,
    ) = WireQuote(code, "86421", "86422", "1", "1", mark, null, null, time, index)

    private fun open(): TickFeed {
        val feed = source.liveTicks(listOf(symbol)).also { feeds += it }
        val deadline = System.currentTimeMillis() + 5_000
        while (fake.quotes.open == 0) {
            check(System.currentTimeMillis() < deadline) { "quotes socket never opened" }
            Thread.sleep(10)
        }
        return feed
    }

    @Test
    fun `the newest quoted mark and index are read back by qkt symbol`() {
        val feed = open()

        fake.quotes.send(quote(1_000L, "86432.49", "86403.5"))
        feed.next()

        val sample = source.marksFor(symbol)!!.at(symbol, minute, 0L)!!
        assertThat(sample.timeMs).isEqualTo(1_000L)
        assertThat(sample.mark!!.toPlainString()).isEqualTo("86432.49")
        assertThat(sample.index!!.toPlainString()).isEqualTo("86403.5")
    }

    @Test
    fun `a quote carrying neither mark nor index leaves the newest one in place`() {
        val feed = open()

        fake.quotes.send(quote(1_000L, "86432.49", "86403.5"))
        fake.quotes.send(quote(2_000L, null, null))
        feed.next()
        feed.next()

        assertThat(source.marksFor(symbol)!!.at(symbol, minute, 0L)!!.timeMs).isEqualTo(1_000L)
    }

    @Test
    fun `marks are a problem until the gateway declares mark_prices, and a whole option root has none`() {
        fake.capabilities = listOf("bars", "quotes")
        val marks = source.marksFor(symbol)!!

        assertThat(marks.problem(symbol)).contains("capability 'mark_prices'")
        fake.capabilities = listOf("bars", "mark_prices", "quotes")
        assertThat(marks.problem(symbol)).isNull()
        assertThat(source.marksFor("OPTIONS:DERIBIT.BTC_USDC")).isNull()
        assertThat(source.marksFor("BYBIT:BTCUSDT")).isNull()
    }
}
