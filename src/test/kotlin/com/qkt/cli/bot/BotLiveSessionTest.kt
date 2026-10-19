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
}
