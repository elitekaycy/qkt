package com.qkt.trade.session

import com.qkt.backtest.Backtest
import com.qkt.backtest.BacktestResult
import com.qkt.common.FixedClock
import com.qkt.marketdata.Tick
import com.qkt.positions.Position
import com.qkt.trade.BotQuoteContext
import java.math.BigDecimal
import java.nio.file.Files
import java.nio.file.Path
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class BotSessionHandlersTest {
    @TempDir
    lateinit var tmp: Path

    private val gold = "EXNESS:XAUUSD"

    /** A live-shaped backend: no bars arrive, equity is venue truth (null), and finishing yields no report. */
    private class LiveShaped(
        val held: Map<String, Position>,
    ) : BotRunBackend {
        override fun awaitNextBar(
            symbol: String,
            before: Long,
            history: BarHistory,
        ) = false

        override fun nowMs() = 5L

        override fun equity(): BigDecimal? = null

        override fun positions() = held

        override fun finish(): BacktestResult? = null

        override fun awaitFlat(
            timeoutMs: Long,
            pending: () -> Boolean,
        ) = Unit
    }

    private fun handlers(
        session: BotRunSession,
        quotes: ((String) -> BotQuoteContext)? = null,
        reads: Path? = null,
        onFinish: (BacktestResult?) -> String? = { null },
    ) = BotSessionHandlers(session, "USD", onFinish, quotes, reads, trail = null, clock = FixedClock(9L))

    private fun liveSession(held: Map<String, Position> = emptyMap()): BotRunSession {
        val history = BarHistory(10)
        val bridge = testBridge(history = history)
        val recorder = BotSessionRecorder(history)
        return BotRunSession("live", LiveShaped(held), mapOf("brain" to bridge), testStreams(), history, recorder)
    }

    /** Three 1m bars whose ticks carry a book (bid one below the print, ask one above). */
    private fun replaySession(): BotRunSession {
        val history = BarHistory(10)
        val recorder = BotSessionRecorder(history)
        val bridge = testBridge(history = history)
        val ticks =
            (0 until 3).flatMap { bar ->
                listOf(1_000L, 30_000L).map { off ->
                    val p = BigDecimal(2400 + bar)
                    Tick(gold, p, bar * 60_000L + off, bid = p - BigDecimal.ONE, ask = p + BigDecimal.ONE)
                }
            } + Tick(gold, BigDecimal("2410"), 181_000L)
        val engine =
            Backtest(
                strategies = listOf("brain" to bridge, BotSessionRecorder.ID to recorder),
                ticks = ticks,
                candleWindow = testStreams().baseWindow,
                startingBalance = BigDecimal("10000"),
            ).toEngine()
        val backend = ReplayBotRunBackend(engine)
        return BotRunSession("replay", backend, mapOf("brain" to bridge), testStreams(), history, recorder)
    }

    private fun intent(action: String) =
        "STRATEGY bot VERSION 1\n\nSYMBOLS\n    x = $gold EVERY 1m\n\nRULES\n    WHEN true\n    THEN $action\n"

    @Test
    fun `a live session reports no equity and finishes with no trades but the report directory`() {
        val h = handlers(liveSession(), onFinish = { tmp.resolve("report").toString() })

        assertThat(h.status()).contains("\"equity\":null").contains("\"finished\":false")
        val done = h.finish()

        assertThat(done).contains("\"trades\":null").contains("\"reportDir\":\"${tmp.resolve("report")}\"")
        assertThat(done).contains("\"note\":null")
        assertThat(h.finish()).isEqualTo("""{"ok":true,"finished":true}""")
        assertThat(h.status()).contains("\"finished\":true")
    }

    @Test
    fun `an account wide intent has no symbol and no preview`() {
        val reply = handlers(liveSession()).intent("brain", intent("CLOSE_ALL"))

        assertThat(reply).contains("\"symbol\":null").contains("\"lots\":null,\"sl\":null,\"tp\":null")
    }

    @Test
    fun `an entry before any quote is queued without a preview`() {
        val reply = handlers(liveSession()).intent("brain", intent("BUY x SIZING 0.01"))

        assertThat(reply).contains("\"symbol\":\"$gold\"").contains("\"lots\":null")
    }

    @Test
    fun `a venue quote context previews the entry's size and exits`() {
        val ctx =
            BotQuoteContext(
                bid = BigDecimal("2400"),
                ask = BigDecimal("2401"),
                equity = null,
                balance = null,
                contractSize = null,
                accountCurrency = "USD",
                quoteCurrency = "USD",
                volumeMin = null,
                volumeStep = null,
                volumeMax = null,
                digits = 2,
            )
        val buy = intent("BUY x SIZING 0.01 BRACKET { STOP LOSS BY 5, TAKE PROFIT BY 10 }")

        val reply = handlers(liveSession(), quotes = { ctx }).intent("brain", buy)

        assertThat(reply).contains("\"lots\":0.01,\"sl\":2396.00,\"tp\":2411.00")
    }

    @Test
    fun `a position with no legs of the session's identities lists none`() {
        val held = mapOf(gold to Position(gold, BigDecimal("0.5"), BigDecimal("2400")))

        assertThat(handlers(liveSession(held)).positions())
            .isEqualTo("""[{"symbol":"$gold","qty":0.5,"legs":[]}]""")
    }

    @Test
    fun `the quote, preview and reads journal follow the book on the cursor's last tick`() {
        val reads = tmp.resolve("reads.jsonl")
        val h = handlers(replaySession(), reads = reads)

        h.next(gold)
        val quote = h.quote(gold)
        val reply = h.intent("brain", intent("SELL x SIZING 0.01 BRACKET { STOP LOSS BY 5, TAKE PROFIT BY 10 }"))
        h.bars(gold, 3)

        assertThat(quote).contains("\"bid\":2400,\"ask\":2402,\"timeMs\":61000")
        assertThat(reply).contains("\"sl\":2405").contains("\"tp\":2390")
        val lines = Files.readAllLines(reads)
        assertThat(lines).hasSize(3)
        assertThat(lines[0]).contains("\"tsMs\":9,").contains("\"verb\":\"next\"").contains("\"delivered\":1,")
        assertThat(lines[1]).contains("\"verb\":\"quote\"").contains("\"delivered\":1,\"bars\":[]")
        assertThat(lines[2]).contains("\"verb\":\"bars\"").contains("\"delivered\":1,")
    }

    @Test
    fun `a filled intent's leg is listed under its symbol and finishing names the held exits`() {
        val h = handlers(replaySession())
        h.next(gold)
        h.intent("brain", intent("BUY x SIZING 0.01"))
        h.next(gold)

        assertThat(h.positions()).contains("\"qty\":0.01").contains("\"legs\":[{\"identity\":\"brain\"")
        val done = h.finish()
        assertThat(done).contains("\"trades\":1").contains("engine-held exits")
    }
}
