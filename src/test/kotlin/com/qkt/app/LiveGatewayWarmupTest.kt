package com.qkt.app

import com.qkt.cli.MarketSourceFactory
import com.qkt.common.SystemClock
import com.qkt.common.TradingCalendar
import com.qkt.connectivity.AccountConfig
import com.qkt.connectivity.AccountDirectory
import com.qkt.connectivity.ConnectorContext
import com.qkt.connectivity.ConnectorRegistry
import com.qkt.connector.gateway.FakeGateway
import com.qkt.connector.gateway.WireBar
import com.qkt.dsl.compile.AstCompiler
import com.qkt.dsl.parse.Dsl
import com.qkt.dsl.parse.ParseResult
import java.time.Duration
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** A live strategy whose stream needs warmup history gets it from its gateway account's bars. */
class LiveGatewayWarmupTest {
    @Test
    fun `a gateway contract's warmup is read from the gateway's closed bars`() {
        val code = "BTC_USDC-PERPETUAL"
        val fake = FakeGateway(listOf(code))
        val minute = 60_000L
        val now = System.currentTimeMillis() / minute * minute
        fake.bars[code to minute] =
            (1L..30L).map { WireBar(now - it * minute, "100", "101", "99", "100.5", "1") }.reversed()
        val settings =
            mapOf(
                "type" to "gateway",
                "gateway_url" to fake.url,
                "api_key" to "env:GW_KEY",
                "expected_adapter" to "fake",
                "expected_account_login" to "7",
                "expected_trade_mode" to "demo",
            )
        val accounts =
            AccountDirectory.open(
                listOf(AccountConfig("deribit", "gateway", settings)),
                ConnectorRegistry.discover(),
                ConnectorContext(null, mapOf("GW_KEY" to "secret"), SystemClock()),
            )
        val dsl =
            "STRATEGY warm VERSION 1\nSYMBOLS\n    perp = DERIBIT:BTC_USDC_PERPETUAL EVERY 1m WARMUP 5 BARS\n" +
                "RULES\n    WHEN perp.close > 1000000 THEN BUY perp SIZING 0.1\n"
        val strategy = AstCompiler().compile((Dsl.parse(dsl) as ParseResult.Success).value)
        val symbols = listOf("DERIBIT:BTC_USDC_PERPETUAL")
        try {
            val handle =
                LiveSession(
                    strategies = listOf("warm" to strategy),
                    source = MarketSourceFactory.composite(accounts.marketDataRoutes(), "local")(symbols),
                    symbols = symbols,
                    clock = SystemClock(),
                    calendar = TradingCalendar.crypto(),
                    brokerFactories = accounts.orderEntry(),
                ).start()
            handle.stop()
            assertThat(handle.awaitTermination(Duration.ofSeconds(5))).isTrue()
        } finally {
            accounts.close()
            fake.shutdown()
        }
    }
}
