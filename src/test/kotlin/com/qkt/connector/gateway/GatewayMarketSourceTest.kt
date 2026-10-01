package com.qkt.connector.gateway

import com.qkt.marketdata.Tick
import com.qkt.marketdata.TickFeed
import com.qkt.marketdata.live.LiveTickFeed
import java.util.concurrent.CopyOnWriteArrayList
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test

class GatewayMarketSourceTest {
    private val code = "BTC_USDC-25DEC26-92000-C"
    private val symbol = "DERIBIT:BTC_USDC_25DEC26_92000_C"
    private val fake = FakeGateway(listOf(code, "ETH_USDC-25DEC26-3000-P"))
    private val client = GatewayClient(fake.url, "k", httpTimeoutMs = 500, retryAttempts = 2)
    private val recorded = CopyOnWriteArrayList<String>()
    private val source =
        GatewayMarketSource(
            "DERIBIT:",
            fake.url,
            "k",
            listing = { client.instruments() },
            recorderFor = { root -> if (root == "DERIBIT:BTC_USDC") { quote -> recorded += quote.symbol } else null },
            bars = client::bars,
        )
    private val feeds = CopyOnWriteArrayList<TickFeed>()

    @AfterEach
    fun stop() {
        feeds.forEach { it.close() }
        fake.shutdown()
    }

    private fun open(vararg symbols: String): TickFeed {
        val feed = source.liveTicks(symbols.toList()).also { feeds += it }
        await { fake.quotes.open > 0 }
        return feed
    }

    private fun quote(
        code: String,
        mark: String? = "648.5",
    ) = WireQuote(code, "640", "655", "1", "1", mark, "52.3", "84437.55", 1_000L)

    @Test
    fun `a contract's quotes arrive as ticks of its qkt symbol`() {
        val feed = open(symbol)

        fake.quotes.send(quote(code))

        val tick = feed.next()!!
        assertThat(tick.symbol).isEqualTo(symbol)
        assertThat(tick.price).isEqualByComparingTo("648.5")
        assertThat(fake.quotes.subscriptions.single()).isEqualTo("symbols=$code")
    }

    @Test
    fun `a fed root subscribes every option of the root once, including contracts listed later`() {
        val feed = open("OPTIONS:DERIBIT.BTC_USDC", symbol)
        val later = "BTC_USDC-26MAR27-95000-C"

        fake.quotes.send(quote("ETH_USDC-25DEC26-3000-P"))
        fake.quotes.send(quote(later))

        assertThat(feed.next()!!.symbol).isEqualTo("DERIBIT:BTC_USDC_26MAR27_95000_C")
        assertThat(fake.quotes.subscriptions.single()).isEqualTo("roots=BTC_USDC")
        assertThat(recorded).containsExactly(later)
    }

    @Test
    fun `a contract fed alone is never recorded as chain history`() {
        val feed = open(symbol)

        fake.quotes.send(quote(code))

        feed.next()
        assertThat(recorded).isEmpty()
    }

    @Test
    fun `a contract or root the gateway does not list fails the subscription`() {
        assertThatThrownBy { source.liveTicks(listOf("DERIBIT:BTC_USDC_25DEC26_99000_C")) }
            .hasMessageContaining("does not list DERIBIT:BTC_USDC_25DEC26_99000_C")
        assertThatThrownBy { source.liveTicks(listOf("OPTIONS:DERIBIT.SOL_USDC")) }
            .hasMessageContaining("lists no options of [SOL_USDC]")
        assertThat(source.supports("OPTIONS:OKX.BTC_USDC")).isFalse()
        assertThat(source.supports("EXNESS:XAUUSD")).isFalse()
    }

    @Test
    fun `a dropped socket subscribes again and the feed resumes after one disconnect and one reconnect`() {
        val feed = open(symbol) as LiveTickFeed
        val changes = CopyOnWriteArrayList<String>()
        feed.onDisconnect { changes += "down" }
        feed.onReconnect { changes += "up" }

        fake.quotes.drop()
        await { fake.quotes.open > 0 && changes == listOf("down", "up") }
        fake.quotes.send(quote(code, mark = "650"))

        assertThat(feed.next()!!.price).isEqualByComparingTo("650")
        assertThat(fake.quotes.subscriptions).hasSize(2)
    }

    @Test
    fun `a malformed or priceless quote is skipped and the next one still arrives`() {
        val feed = open(symbol)

        fake.quotes.sendRaw("""{"bid":"1"}""")
        fake.quotes.send(WireQuote(code, bid = "640", time = 1L))
        fake.quotes.send(quote(code, mark = "651"))

        val tick: Tick = feed.next()!!
        assertThat(tick.price).isEqualByComparingTo("651")
    }

    private fun await(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 5_000
        while (!condition()) {
            check(System.currentTimeMillis() < deadline) { "timed out" }
            Thread.sleep(10)
        }
    }

    @Test
    fun `a contract's closed bars come from the gateway across pages, as candles of its qkt symbol`() {
        val minute = 60_000L
        fake.bars[code to minute] = (0L until 5L).map { WireBar(it * minute, "10", "12", "9", "11.5", "3") }

        val range =
            com.qkt.common.TimeRange(java.time.Instant.ofEpochMilli(minute), java.time.Instant.ofEpochMilli(4 * minute))
        val candles = source.bars(symbol, com.qkt.candles.TimeWindow.ONE_MINUTE, range).toList()

        assertThat(candles.map { it.startTime }).containsExactly(minute, 2 * minute, 3 * minute)
        assertThat(candles.first().symbol).isEqualTo(symbol)
        assertThat(candles.first().endTime).isEqualTo(2 * minute)
        assertThat(candles.first().close).isEqualByComparingTo("11.5")
        assertThat(candles.first().volume).isEqualByComparingTo("3")
    }

    @Test
    fun `a whole root or a window that does not divide a day has no gateway bars`() {
        val day = com.qkt.common.TimeRange(java.time.Instant.EPOCH, java.time.Instant.ofEpochMilli(86_400_000L))

        assertThatThrownBy { source.bars("OPTIONS:DERIBIT.BTC_USDC", com.qkt.candles.TimeWindow.ONE_MINUTE, day) }
            .isInstanceOf(com.qkt.marketdata.source.UnsupportedDataException::class.java)
        assertThatThrownBy { source.bars(symbol, com.qkt.candles.TimeWindow(7 * 60_000L), day) }
            .isInstanceOf(com.qkt.marketdata.source.UnsupportedDataException::class.java)
    }
}
