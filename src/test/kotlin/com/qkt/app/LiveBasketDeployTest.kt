package com.qkt.app

import com.qkt.candles.TimeWindow
import com.qkt.cli.MarketSourceFactory
import com.qkt.common.Clock
import com.qkt.common.Money
import com.qkt.common.SystemClock
import com.qkt.common.TradingCalendar
import com.qkt.connectivity.AccountConfig
import com.qkt.connectivity.AccountDirectory
import com.qkt.connectivity.ConnectorContext
import com.qkt.connectivity.ConnectorRegistry
import com.qkt.connector.gateway.FakeGateway
import com.qkt.connector.gateway.WireBar
import com.qkt.connector.gateway.WireInstrument
import com.qkt.connector.gateway.WireQuote
import com.qkt.dsl.compile.AstCompiler
import com.qkt.dsl.parse.Dsl
import com.qkt.dsl.parse.ParseResult
import com.qkt.instrument.InstrumentMeta
import com.qkt.instrument.InstrumentRegistry
import java.math.BigDecimal
import java.time.Duration
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** A BASKET strategy deploys live, and one BUY of its composite reaches the venue as one order per constituent. */
class LiveBasketDeployTest {
    private val btc = "BTC_USDC-PERPETUAL"
    private val eth = "ETH_USDC-PERPETUAL"

    private val perpetuals =
        object : InstrumentRegistry {
            override fun lookup(qktSymbol: String): InstrumentMeta =
                InstrumentMeta(
                    qktSymbol = qktSymbol,
                    contractSize = BigDecimal.ONE,
                    volumeStep = BigDecimal("0.0001"),
                    volumeMin = BigDecimal("0.0001"),
                    volumeMax = null,
                    pointSize = BigDecimal("0.5"),
                    digits = 1,
                    tradeStopsLevelPoints = 0,
                )
        }

    @Test
    fun `a basket deploys through a gateway account and fans its order out to each constituent`() {
        val fake = gateway()
        val clock = SteppedClock(alignedMinute())
        val accounts = accounts(fake)
        val handle = deploy("WHEN POSITION.both = 0 AND both.close > 0 THEN BUY both SIZING 300 USD", clock, accounts)
        try {
            await(10) { fake.quotes.open > 0 }
            // Window 0 sets the index baseline, window 1 is the first composite; minute 2's quotes close it.
            sendMinutes(fake, clock, 0..2)
            await(30) { fake.submits.size >= 2 }
            assertThat(fake.submits.map { it.symbol to it.side }).containsExactlyInAnyOrder(btc to "buy", eth to "buy")
        } finally {
            stop(handle, accounts, fake)
        }
    }

    @Test
    fun `an indicator over a basket warms from its constituents and fires on the first live close`() {
        val fake = gateway()
        val start = alignedMinute()
        for ((code, price) in listOf(btc to 79_000L, eth to 1_900L)) {
            fake.bars[code to MINUTE_MS] =
                (5 downTo 1).map { back ->
                    val close = (price + (5 - back) * 10).toString()
                    WireBar(start - back * MINUTE_MS, close, close, close, close, "1")
                }
        }
        val clock = SteppedClock(start)
        val accounts = accounts(fake)
        val rule = "WHEN POSITION.both = 0 AND both.close > sma(both.close, 3) THEN BUY both SIZING 300 USD"
        val handle = deploy(rule, clock, accounts)
        try {
            await(10) { fake.quotes.open > 0 }
            // One live close: cold, the sma would need a baseline window and three composites first.
            sendMinutes(fake, clock, 0..1)
            await(30) { fake.submits.size >= 2 }
            assertThat(fake.submits.map { it.symbol to it.side }).containsExactlyInAnyOrder(btc to "buy", eth to "buy")
        } finally {
            stop(handle, accounts, fake)
        }
    }

    private fun gateway(): FakeGateway =
        FakeGateway(listOf(btc, eth)).also { fake ->
            fake.capabilities = listOf("funding")
            for (code in listOf(btc, eth)) {
                fake.instruments[code] = WireInstrument(code, "perpetual", "USDC", "1", "0.5", "0.0001", "0.0001")
            }
        }

    private fun deploy(
        rule: String,
        clock: Clock,
        accounts: AccountDirectory,
    ): LiveSessionHandle {
        val dsl =
            """
            STRATEGY bsk VERSION 1
            SYMBOLS
                btc = DERIBIT:BTC_USDC_PERPETUAL EVERY 1m,
                eth = DERIBIT:ETH_USDC_PERPETUAL EVERY 1m,
                both = BASKET EQUAL_WEIGHT [btc, eth] EVERY 1m
            RULES
                $rule
            """.trimIndent()
        val symbols = listOf("DERIBIT:BTC_USDC_PERPETUAL", "DERIBIT:ETH_USDC_PERPETUAL")
        return LiveSession(
            strategies = listOf("bsk" to AstCompiler().compile((Dsl.parse(dsl) as ParseResult.Success).value)),
            source = MarketSourceFactory.composite(accounts.marketDataRoutes(), "local")(symbols),
            symbols = symbols,
            candleWindow = TimeWindow.ONE_MINUTE,
            clock = clock,
            calendar = TradingCalendar.crypto(),
            brokerFactories = accounts.orderEntry(),
            instrumentRegistry = perpetuals,
            initialBalance = Money.of("10000"),
        ).start()
    }

    /** The quotes are current as sent: the clock follows them, so the market-data gate sees a healthy feed. */
    private fun sendMinutes(
        fake: FakeGateway,
        clock: SteppedClock,
        minutes: IntRange,
    ) {
        val base = clock.time / MINUTE_MS * MINUTE_MS
        for (minute in minutes) {
            val at = base + minute * MINUTE_MS + 1_000
            clock.time = at
            fake.quotes.send(quote(btc, "80000", minute, at))
            fake.quotes.send(quote(eth, "2000", minute, at))
        }
    }

    private fun stop(
        handle: LiveSessionHandle,
        accounts: AccountDirectory,
        fake: FakeGateway,
    ) {
        handle.stop()
        handle.awaitTermination(Duration.ofSeconds(5))
        accounts.close()
        fake.shutdown()
    }

    private fun alignedMinute(): Long = System.currentTimeMillis() / MINUTE_MS * MINUTE_MS

    private class SteppedClock(
        @Volatile var time: Long,
    ) : Clock {
        override fun now(): Long = time
    }

    private fun quote(
        code: String,
        price: String,
        minute: Int,
        atMs: Long,
    ): WireQuote {
        val mid = BigDecimal(price).add(BigDecimal(minute))
        return WireQuote(code, bid = mid.toPlainString(), ask = mid.add(BigDecimal.ONE).toPlainString(), time = atMs)
    }

    private fun accounts(fake: FakeGateway): AccountDirectory =
        AccountDirectory.open(
            listOf(
                AccountConfig(
                    "deribit",
                    "gateway",
                    mapOf(
                        "type" to "gateway",
                        "gateway_url" to fake.url,
                        "api_key" to "env:GW_KEY",
                        "expected_adapter" to "fake",
                        "expected_account_login" to "7",
                        "expected_trade_mode" to "demo",
                    ),
                ),
            ),
            ConnectorRegistry.discover(),
            ConnectorContext(null, mapOf("GW_KEY" to "secret"), SystemClock()),
        )

    private fun await(
        seconds: Long,
        condition: () -> Boolean,
    ) {
        val deadline = System.currentTimeMillis() + seconds * 1_000
        while (!condition()) {
            check(System.currentTimeMillis() < deadline) { "timed out" }
            Thread.sleep(20)
        }
    }

    private companion object {
        const val MINUTE_MS = 60_000L
    }
}
