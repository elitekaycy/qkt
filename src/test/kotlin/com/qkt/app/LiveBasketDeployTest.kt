package com.qkt.app

import com.qkt.candles.TimeWindow
import com.qkt.cli.MarketSourceFactory
import com.qkt.common.Money
import com.qkt.common.SystemClock
import com.qkt.common.TradingCalendar
import com.qkt.connectivity.AccountConfig
import com.qkt.connectivity.AccountDirectory
import com.qkt.connectivity.ConnectorContext
import com.qkt.connectivity.ConnectorRegistry
import com.qkt.connector.gateway.FakeGateway
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
import org.assertj.core.api.Assertions.assertThatThrownBy
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
        val fake = FakeGateway(listOf(btc, eth))
        for (code in listOf(btc, eth)) {
            fake.instruments[code] = WireInstrument(code, "perpetual", "USDC", "1", "0.5", "0.0001", "0.0001")
        }
        val clock = SystemClock()
        val accounts = accounts(fake)
        val dsl =
            """
            STRATEGY bsk VERSION 1
            SYMBOLS
                btc = DERIBIT:BTC_USDC_PERPETUAL EVERY 1m,
                eth = DERIBIT:ETH_USDC_PERPETUAL EVERY 1m,
                both = BASKET EQUAL_WEIGHT [btc, eth] EVERY 1m
            RULES
                WHEN POSITION.both = 0 AND both.close > 0
                THEN BUY both SIZING 300 USD
            """.trimIndent()
        val symbols = listOf("DERIBIT:BTC_USDC_PERPETUAL", "DERIBIT:ETH_USDC_PERPETUAL")
        val handle =
            LiveSession(
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
        try {
            await(10) { fake.quotes.open > 0 }
            val base = clock.now() / 60_000 * 60_000 - 4 * 60_000
            for (minute in 0..4) {
                val at = base + minute * 60_000 + 1_000
                fake.quotes.send(quote(btc, "80000", minute, at))
                fake.quotes.send(quote(eth, "2000", minute, at))
            }
            await(60) { fake.submits.size >= 2 }
            assertThat(fake.submits.map { it.symbol to it.side }).containsExactlyInAnyOrder(btc to "buy", eth to "buy")
        } finally {
            handle.stop()
            handle.awaitTermination(Duration.ofSeconds(5))
            accounts.close()
            fake.shutdown()
        }
    }

    @Test
    fun `an indicator over a basket is refused at deploy, since a composite has no venue history`() {
        val fake = FakeGateway(listOf(btc, eth))
        val accounts = accounts(fake)
        val dsl =
            """
            STRATEGY bskwarm VERSION 1
            SYMBOLS
                btc = DERIBIT:BTC_USDC_PERPETUAL EVERY 1m,
                eth = DERIBIT:ETH_USDC_PERPETUAL EVERY 1m,
                both = BASKET EQUAL_WEIGHT [btc, eth] EVERY 1m
            RULES
                WHEN POSITION.both = 0 AND both.close > sma(both.close, 3)
                THEN BUY both SIZING 300 USD
            """.trimIndent()
        val symbols = listOf("DERIBIT:BTC_USDC_PERPETUAL", "DERIBIT:ETH_USDC_PERPETUAL")
        val strategy = AstCompiler().compile((Dsl.parse(dsl) as ParseResult.Success).value)
        try {
            assertThatThrownBy {
                LiveSession(
                    strategies = listOf("bskwarm" to strategy),
                    source = MarketSourceFactory.composite(accounts.marketDataRoutes(), "local")(symbols),
                    symbols = symbols,
                    candleWindow = TimeWindow.ONE_MINUTE,
                    clock = SystemClock(),
                    calendar = TradingCalendar.crypto(),
                    brokerFactories = accounts.orderEntry(),
                    initialBalance = Money.of("10000"),
                ).start()
            }.isInstanceOf(WarmupFailedException::class.java).hasMessageContaining("BASKET:BOTH")
        } finally {
            accounts.close()
            fake.shutdown()
        }
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
}
