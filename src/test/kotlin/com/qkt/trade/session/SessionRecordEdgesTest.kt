package com.qkt.trade.session

import com.qkt.common.Side
import com.qkt.execution.OrderRequest
import com.qkt.execution.TimeInForce
import com.qkt.marketdata.Candle
import com.qkt.marketdata.Tick
import java.math.BigDecimal
import java.nio.file.Files
import java.nio.file.Path
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class SessionRecordEdgesTest {
    @TempDir
    lateinit var tmp: Path

    private val gold = "EXNESS:XAUUSD"

    private fun bar(start: Long): Candle {
        val one = BigDecimal.ONE
        return Candle(gold, one, one, one, one, BigDecimal.ZERO, start, start + 60_000L)
    }

    private fun market(id: String) = OrderRequest.Market(id, gold, Side.BUY, BigDecimal.ONE, TimeInForce.GTC, 1L)

    @Test
    fun `a tick log reopened over an existing file appends without a second header`() {
        val path = tmp.resolve("ticks.csv")
        LiveTickLog(path).use { it.append(Tick(gold, BigDecimal("2400"), 1L)) }

        LiveTickLog(path).use { it.append(Tick(gold, BigDecimal("2401"), 2L, bid = BigDecimal("2400.9"))) }

        val lines = Files.readAllLines(path)
        assertThat(lines.count { it.startsWith("timestamp,") }).isEqualTo(1)
        assertThat(lines.drop(1)).containsExactly("1,$gold,2400,,,,,", "2,$gold,2401,,2400.9,,,")
    }

    @Test
    fun `a tick log over an empty file writes its header first`() {
        val path = tmp.resolve("ticks.csv")
        Files.createFile(path)

        LiveTickLog(path).close()

        val header = "timestamp,symbol,price,volume,bid,ask,bidVolume,askVolume"
        assertThat(Files.readAllLines(path)).containsExactly(header)
    }

    @Test
    fun `reading ticks skips blanks, tolerates short rows and reads absent files as none`() {
        val path = tmp.resolve("t.csv")
        Files.write(path, listOf("header", "", "5,$gold,2400,1,2399,2401", "6,$gold,2402"))

        val ticks = LiveTickLog.read(path)

        assertThat(ticks).containsExactly(
            Tick(gold, BigDecimal("2400"), 5L, BigDecimal("1"), BigDecimal("2399"), BigDecimal("2401")),
            Tick(gold, BigDecimal("2402"), 6L),
        )
        assertThat(LiveTickLog.read(tmp.resolve("absent.csv"))).isEmpty()
    }

    @Test
    fun `a bar history needs room for at least one bar`() {
        assertThatThrownBy { BarHistory(0) }.hasMessage("BarHistory capacity must be positive, got 0")
    }

    @Test
    fun `an unknown series has no oldest index and no bars`() {
        val history = BarHistory(2)

        assertThat(history.oldestIndex("x")).isZero()
        assertThat(history.at("x", 0)).isNull()
    }

    @Test
    fun `a series reads by index only the bars it still holds`() {
        val history = BarHistory(2)
        listOf(0L, 60_000L, 120_000L).forEach { history.record("k", bar(it)) }

        assertThat(history.oldestIndex("k")).isEqualTo(1L)
        assertThat(history.at("k", 0)).isNull()
        assertThat(history.at("k", 1)?.startTime).isEqualTo(60_000L)
        assertThat(history.at("k", 2)?.startTime).isEqualTo(120_000L)
        assertThat(history.at("k", 3)).isNull()
    }

    @Test
    fun `a bar starting before the last one ended is never served as new`() {
        val history = BarHistory(5)
        history.record("k", bar(60_000L))

        history.record("k", bar(30_000L))

        assertThat(history.countFor("k")).isEqualTo(1L)
    }

    @Test
    fun `a request's ids include an oto's parent and children and an oco's legs`() {
        val qty = BigDecimal.ONE
        val oto = OrderRequest.OTO("oto", gold, Side.BUY, qty, market("p"), listOf(market("c1")), TimeInForce.GTC, 1L)
        val oco = OrderRequest.StandaloneOCO("oco", gold, Side.BUY, qty, market("l1"), oto, TimeInForce.GTC, 1L)

        assertThat(requestIds(oco)).containsExactly("oco", "l1", "oto", "p", "c1")
        assertThat(requestIds(market("m"))).containsExactly("m")
    }

    @Test
    fun `only intents the engine compiled are rebuilt and their identity must be declared`() {
        val brain = testBridge()
        val source = renderedBuy()
        val notCompiled = JournaledIntent("int-1", "ghost", source, 0L, 0L, IntentFate("int-1", "ghost", "fired"))
        val noFate = JournaledIntent("int-2", "ghost", source, 0L, 0L, lastFate = null)

        rebuildIdentities(listOf(notCompiled, noFate), testStreams(), mapOf("brain" to brain))

        val compiled = noFate.copy(lastFate = IntentFate("int-2", "ghost", "filled", compiled = true))
        assertThatThrownBy { rebuildIdentities(listOf(compiled), testStreams(), mapOf("brain" to brain)) }
            .hasMessage("journaled identity 'ghost' is not declared")
    }

    private fun renderedBuy() =
        "STRATEGY bot VERSION 1\n\nSYMBOLS\n    x = $gold EVERY 1m\n\nRULES\n" +
            "    WHEN true\n    THEN BUY x SIZING 0.01\n"
}
