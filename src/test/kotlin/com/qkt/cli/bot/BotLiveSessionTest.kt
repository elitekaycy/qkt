package com.qkt.cli.bot

import com.qkt.cli.daemon.StateDir
import com.qkt.cli.daemon.StrategyHandle
import com.qkt.cli.daemon.createLiveSession
import com.qkt.marketdata.Tick
import com.qkt.marketdata.TickFeed
import com.qkt.marketdata.source.MarketSource
import com.qkt.marketdata.source.MarketSourceCapability
import com.qkt.trade.session.BarHistory
import com.qkt.trade.session.BotRunSession
import com.qkt.trade.session.BotSessionIdentities
import com.qkt.trade.session.BotSessionRecorder
import com.qkt.trade.session.BotSessionStreams
import com.qkt.trade.session.IntentLedger
import com.qkt.trade.session.LiveBotRunBackend
import java.math.BigDecimal
import java.nio.file.Path
import java.util.concurrent.Semaphore
import java.util.concurrent.atomic.AtomicInteger
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/** A live bot session is assembled by the deploy factory: the same caps, journals and halts as `qkt deploy`. */
class BotLiveSessionTest {
    private val gold = "EXNESS:XAUUSD"

    private class Gated(
        private val ticks: List<Tick>,
    ) : TickFeed {
        val permits = Semaphore(0)
        private val idx = AtomicInteger(0)

        override fun next(): Tick? {
            val i = idx.getAndIncrement()
            if (i >= ticks.size) return null
            permits.acquire()
            return ticks[i]
        }

        override fun close() = Unit
    }

    private fun ticks(): List<Tick> =
        (0 until 6).flatMap { bar ->
            listOf(
                Tick(gold, BigDecimal(2400 + bar), bar * 60_000L + 1_000L),
                Tick(
                    gold,
                    BigDecimal(2401 + bar),
                    bar * 60_000L + 30_000L,
                ),
            )
        }

    @Test
    fun `a live session applies the configured max order qty`(
        @TempDir tmp: Path,
    ) {
        val feed = Gated(ticks())
        val source =
            object : MarketSource {
                override val name = "gated"
                override val capabilities = setOf(MarketSourceCapability.LIVE_TICKS)

                override fun supports(symbol: String) = true

                override fun liveTicks(symbols: List<String>): TickFeed = feed
            }
        val factory =
            StrategyHandle.RealFactory(
                stateDir = StateDir.resolve(tmp.toString()),
                marketSourceProvider = { source },
                maxOrderQty = BigDecimal("0.05"),
            )
        val streams = BotSessionStreams(listOf(gold), listOf("1m"))
        val history = BarHistory(100)
        val recorder = BotSessionRecorder(history)
        val identities = BotSessionIdentities.compile(streams, listOf("brain"), IntentLedger(), history)
        val handle =
            factory.createLiveSession(
                name = "live-test",
                strategies = identities.map { (id, s) -> id to s } + (BotSessionRecorder.ID to recorder),
                symbols = streams.symbols,
                candleWindow = streams.baseWindow,
                ignoreMismatches = false,
                onTrade = { _, _, _ -> },
            )
        val session =
            BotRunSession("live", LiveBotRunBackend(handle, identities.keys), identities, streams, history, recorder)
        try {
            feed.permits.release(3)
            assertThat(session.next(gold)).isNotNull()
            session.submit(
                "brain",
                "STRATEGY bot VERSION 1\nSYMBOLS\n    x = $gold EVERY 1m\nRULES\n    WHEN true\n    THEN BUY x SIZING 0.1\n",
            )
            feed.permits.release(2)
            val deadline = System.currentTimeMillis() + 10_000L
            while (session.fates().single().state == "queued" && System.currentTimeMillis() < deadline) Thread.sleep(20)

            val fate = session.fates().single()
            assertThat(fate.state).isEqualTo("rejected")
            assertThat(fate.reason).containsIgnoringCase("qty")
        } finally {
            feed.permits.release(100)
            handle.stop()
        }
    }

    @Test
    fun `an identity a running daemon deploys is refused`() {
        assertThatThrownBy { refuseDeployedIdentities(listOf("brain", "trend"), listOf("trend", "meanrev")) }
            .hasMessageContaining("trend")
            .hasMessageContaining("daemon")
    }

    private fun bot(vararg tokens: String): Pair<Int, String> {
        val buffer = java.io.ByteArrayOutputStream()
        val original = System.out
        System.setOut(java.io.PrintStream(buffer))
        val code =
            try {
                BotCommand(com.qkt.cli.Args(arrayOf("bot", *tokens))).run()
            } finally {
                System.setOut(original)
            }
        return code to buffer.toString()
    }

    @Test
    fun `a backtest never takes a live run's id`(
        @TempDir tmp: Path,
    ) {
        val dir =
            com.qkt.trade.session.BotSessionFiles
                .sessionDir(StateDir.resolve(tmp.toString()).stateRoot, "live1")
        com.qkt.trade.session.writeSessionMeta(
            dir,
            com.qkt.trade.session
                .SessionMeta("live1", "live", listOf("a"), emptyList(), 1L),
        )
        java.nio.file.Files
            .writeString(dir.resolve("intents.jsonl"), "{}\n")

        val (code, out) =
            bot(
                "session",
                "start",
                "--backtest",
                "--symbols",
                gold,
                "--tf",
                "1m",
                "--from",
                "2024-01-02",
                "--to",
                "2024-01-03",
                "--run",
                "live1",
                "--state-dir",
                tmp.toString(),
                "--no-fetch",
                "--json",
            )

        assertThat(code).isEqualTo(com.qkt.cli.ExitCodes.USER_ERROR)
        assertThat(out).contains("live session's record")
        assertThat(dir.resolve("intents.jsonl")).exists()
    }

    @Test
    fun `reserved identity names are refused`(
        @TempDir tmp: Path,
    ) {
        val (code, out) =
            bot(
                "session",
                "start",
                "--backtest",
                "--symbols",
                gold,
                "--tf",
                "1m",
                "--identities",
                "__x",
                "--state-dir",
                tmp.toString(),
                "--json",
            )

        assertThat(code).isEqualTo(com.qkt.cli.ExitCodes.USER_ERROR)
        assertThat(out).contains("reserved")
    }
}
