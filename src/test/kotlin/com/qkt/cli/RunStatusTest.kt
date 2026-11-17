package com.qkt.cli

import com.qkt.app.LiveSession
import com.qkt.app.OrderManager
import com.qkt.app.SessionPnl
import com.qkt.common.Side
import com.qkt.dsl.compile.AstCompiler
import com.qkt.dsl.parse.Dsl
import com.qkt.dsl.parse.ParseResult
import com.qkt.execution.Trade
import com.qkt.marketdata.Tick
import com.qkt.marketdata.TickFeed
import com.qkt.marketdata.source.MarketSource
import com.qkt.marketdata.source.MarketSourceCapability
import java.math.BigDecimal
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class RunStatusTest {
    private val trade =
        Trade("o1", "BTCUSDT", BigDecimal("42000.50"), BigDecimal("0.25"), Side.BUY, 1_705_276_800_000L)

    @Test
    fun `a fill prints as one console line with its time side size price and realized`() {
        assertThat(tradeLogLine(trade, BigDecimal("12.5")))
            .isEqualTo("[INFO] 2024-01-15T00:00:00Z BUY BTCUSDT qty=0.25 px=42000.50 realized=12.5")
    }

    @Test
    fun `a fill serialises every field as a string`() {
        assertThat(tradeToJson(trade, BigDecimal("12.5")).toString())
            .isEqualTo(
                """{"timestamp":"2024-01-15T00:00:00Z","side":"BUY","symbol":"BTCUSDT","qty":"0.25",""" +
                    """"price":"42000.50","realized":"12.5"}""",
            )
    }

    @Test
    fun `pending stack layers keep their id layer trigger side and size`() {
        val layers =
            pendingStackLayers(
                listOf(OrderManager.PendingStackLayerInfo("s1", 2, BigDecimal("101.5"), "BUY", BigDecimal("3"))),
            )

        assertThat(layers).hasSize(1)
        assertThat(layers.single().stackId).isEqualTo("s1")
        assertThat(layers.single().layer).isEqualTo(2)
        assertThat(layers.single().triggerPrice).isEqualByComparingTo("101.5")
        assertThat(layers.single().side).isEqualTo("BUY")
        assertThat(layers.single().quantity).isEqualByComparingTo("3")
        assertThat(pendingStackLayers(emptyList())).isEmpty()
    }

    @Test
    fun `a snapshot reports the last trade with the session's money and no trade before one`() {
        val pnl = SessionPnl(BigDecimal("1010"), BigDecimal("1000"), BigDecimal("7"), BigDecimal("3"))

        val none = buildSnapshot("s", 4, System.currentTimeMillis(), "2024-01-15T00:00:00Z", emptyList())
        val last =
            buildSnapshot("s", 4, System.currentTimeMillis(), "2024-01-15T00:00:00Z", listOf(trade), pnl = pnl)

        assertThat(none.lastTrade).isNull()
        assertThat(none.equity).isEqualByComparingTo("0")
        assertThat(last.lastTrade?.symbol).isEqualTo("BTCUSDT")
        assertThat(last.lastTrade?.timestamp).isEqualTo("2024-01-15T00:00:00Z")
        assertThat(last.lastTrade?.qty).isEqualByComparingTo("0.25")
        assertThat(last.equity).isEqualByComparingTo("1010")
        assertThat(last.realized).isEqualByComparingTo("7")
        assertThat(last.version).isEqualTo(4)
    }

    private class ClosableFeed : TickFeed {
        private val closed = CountDownLatch(1)

        override fun next(): Tick? {
            closed.await(30, TimeUnit.SECONDS)
            return null
        }

        override fun close() = closed.countDown()
    }

    private class IdleSource : MarketSource {
        override val name = "Idle"
        override val capabilities = setOf(MarketSourceCapability.LIVE_TICKS)

        override fun supports(symbol: String) = true

        override fun liveTicks(symbols: List<String>): TickFeed = ClosableFeed()
    }

    @Test
    fun `a graceful shutdown stops the session and reports it, noting a requested flatten`() {
        val ast =
            (Dsl.parseFile(Path.of("src/test/resources/cli/valid_strategy.qkt")) as ParseResult.Success).value
        val session =
            LiveSession(
                strategies = listOf(ast.name to AstCompiler().compile(ast)),
                source = IdleSource(),
                symbols = listOf("BTCUSDT"),
            ).start()

        val run = captureCliRun { gracefulShutdown(session, null, 5_000L, flattenOnStop = true).let { 0 } }

        assertThat(session.running).isFalse()
        assertThat(run.err)
            .contains("[INFO] graceful shutdown initiated")
            .contains("[INFO] flatten-on-stop requested (no-op in 12b paper mode)")
            .contains("[INFO] terminated; 0 trades")
    }
}
