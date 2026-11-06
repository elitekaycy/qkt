package com.qkt.trade.session

import com.qkt.backtest.Backtest
import com.qkt.marketdata.Tick
import com.qkt.research.ReplayEngine
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class IntentReplayTest : IntentSessionFixture() {
    private class Parts(
        val engine: ReplayEngine,
        val recorder: BotSessionRecorder,
        val session: BotRunSession,
    ) {
        fun replay() = IntentReplay(engine, recorder, session)
    }

    /** Six 1m bars, a tick at +1 s and +30 s of each. */
    private fun parts(): Parts {
        val streams = BotSessionStreams(listOf(gold), listOf("1m"))
        val history = BarHistory(capacity = 100)
        val recorder = BotSessionRecorder(history)
        val strategies = BotSessionIdentities.compile(streams, listOf("brain"), history = history)
        val ticks =
            (0 until 6).flatMap { bar ->
                val p = BigDecimal(2400 + bar)
                listOf(Tick(gold, p, bar * 60_000L + 1_000L), Tick(gold, p + BigDecimal.ONE, bar * 60_000L + 30_000L))
            }
        val engine =
            Backtest(
                strategies = strategies.map { (id, s) -> id to s } + (BotSessionRecorder.ID to recorder),
                ticks = ticks,
                candleWindow = streams.baseWindow,
                startingBalance = BigDecimal("10000"),
                instruments = goldSpec,
            ).toEngine()
        val session = BotRunSession("t", ReplayBotRunBackend(engine), strategies, streams, history, recorder)
        return Parts(engine, recorder, session)
    }

    private fun journaled(
        id: String,
        source: String,
        simMs: Long = 0L,
        tickSeq: Long = 0L,
    ) = JournaledIntent(id, "brain", source, simMs, tickSeq, lastFate = null)

    @Test
    fun `by arrival time an intent re-enters at the first tick at or after it arrived`() {
        val p = parts()

        val result =
            p.replay().run(
                listOf(journaled("int-1", intent("BUY x SIZING 0.01"), simMs = 150_000L)),
                byArrivalTime = true,
            )

        val buy = result.trades.single().trade
        assertThat(buy.timestamp).isGreaterThan(150_000L)
        assertThat(p.engine.exhausted).isTrue()
    }

    @Test
    fun `an intent that arrived before the cursor re-enters at once`() {
        val p = parts()
        p.engine.advanceUntil { p.engine.currentTimestamp >= 200_000L }

        val early = journaled("int-1", intent("BUY x SIZING 0.01"), simMs = 10L)
        val result = p.replay().run(listOf(early), byArrivalTime = true)

        assertThat(result.trades.single().trade.timestamp).isBetween(200_000L, 270_000L)
    }

    @Test
    fun `by arrival time a recorded finish stops the replay there`() {
        val p = parts()

        p.replay().run(
            listOf(journaled("int-1", intent("BUY x SIZING 0.01"), simMs = 60_000L)),
            byArrivalTime = true,
            finishedAt = 180_000L to 0L,
        )

        assertThat(p.engine.currentTimestamp).isBetween(180_000L, 181_000L)
        assertThat(p.engine.exhausted).isFalse()
    }

    @Test
    fun `a finish already behind the cursor does not advance it`() {
        val p = parts()

        p.replay().run(
            listOf(journaled("int-1", intent("BUY x SIZING 0.01"), simMs = 200_000L)),
            byArrivalTime = true,
            finishedAt = 30_000L to 0L,
        )

        assertThat(p.engine.currentTimestamp).isEqualTo(210_000L)
    }

    @Test
    fun `by tick count a recorded finish stops after that many ticks`() {
        val p = parts()

        p.replay().run(
            listOf(journaled("int-1", intent("BUY x SIZING 0.01"), tickSeq = 2L)),
            byArrivalTime = false,
            finishedAt = 0L to 6L,
        )

        assertThat(p.recorder.ticksSeen).isEqualTo(6L)
        assertThat(p.engine.exhausted).isFalse()
    }
}
