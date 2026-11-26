package com.qkt.parity

import com.qkt.candles.TimeWindow
import com.qkt.cli.daemon.decoratedSource
import com.qkt.cli.daemon.liveSetSource
import com.qkt.common.Clock
import com.qkt.instrument.EventContracts
import com.qkt.instrument.InstrumentMeta
import com.qkt.instrument.universe.MembershipChange
import com.qkt.marketdata.Tick
import java.math.BigDecimal
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicLong
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * A moving set run through the wiring a deploy uses: the session reads the source its strategy decorates
 * ([decoratedSource], [liveSetSource]), so the production merged feed and the listing poller on its own thread are in
 * the path. Three sessions (two restarts) over one state; the venue starts listing D and E at 26h and refuses to
 * quote E. D must still be subscribed, join at 27h and trade as in one uninterrupted backtest, and E be journalled
 * as unavailable once.
 */
class LiveSetProductionWiringTest {
    @TempDir
    lateinit var tmp: Path

    private val hour = 3_600_000L
    private val t0 = 1_699_999_200_000L

    private fun contract(
        code: String,
        open: Int,
    ): InstrumentMeta =
        EventContracts.meta(
            "BACKTEST:$code",
            EventContracts
                .terms()
                .copy(openAtMs = t0 + open * hour, closeAtMs = t0 + 60 * hour, expiryMs = t0 + 60 * hour),
        )

    private val atStart = listOf(contract("A", 0), contract("B", 10), contract("C", 25))
    private val d = contract("D", 27)
    private val e = contract("E", 40)

    private val source =
        "STRATEGY roll VERSION 1\nSYMBOLS\n" +
            "    s = BACKTEST:* WHERE kind = event TOP 1 BY symbol DESC DYNAMIC EVERY 1h\n" +
            "RULES\n    FOR EACH m IN s DO\n      WHEN m.close > 100 THEN BUY m SIZING 1"

    private val ticks: List<Tick> =
        (0 until 30 * 6).flatMap { i ->
            listOf("A", "B", "C", "D", "E").map { Tick("BACKTEST:$it", BigDecimal("101"), t0 + i * 600_000L) }
        }

    private val runs by lazy { LiveSetSessions(tmp, "roll", source, TimeWindow.ONE_HOUR, ticks) }
    private val tapeMs = AtomicLong(t0)
    private val tapeClock =
        object : Clock {
            override fun now(): Long = tapeMs.get()
        }

    /** One session through the deploy's source wiring, its poller reading the listing every 5 ms of wall time. */
    private fun session(
        fromMs: Long,
        toMs: Long,
    ) = runs.session(fromMs, toMs) { host, tape ->
        lateinit var venue: LiveSetMergedVenue
        venue =
            LiveSetMergedVenue(tape, emptyMap(), refused = setOf("BACKTEST:E")) { tick ->
                tapeMs.set(tick.timestamp)
                if (tick.timestamp >= t0 + 26 * hour && d !in runs.listed) runs.listed += listOf(d, e)
                tick.timestamp < t0 + 26 * hour + hour / 2 || venue.serves("BACKTEST:D")
            }
        host.sourceDecorator = { liveSetSource(host, runs.catalog, it, pollMs = 5, clock = tapeClock) }
        venue.reading(decoratedSource(listOf("roll" to host), venue))
    }

    @Test
    fun `sessions through the deploy's source wiring trade as the backtest does across two restarts`() {
        runs.listed += atStart
        val (journal, trades) =
            BacktestLiveParityFixtures.withQuietParityLogs { runs.backtest(atStart + d, t0, t0 + 30 * hour) }
        BacktestLiveParityFixtures.withQuietParityLogs {
            session(t0, t0 + 15 * hour)
            session(t0 + 15 * hour, t0 + 29 * hour)
            runs.listed -= e
            session(t0 + 29 * hour, t0 + 30 * hour)
        }

        assertThat(trades.map { it.symbol }).containsExactly("BACKTEST:A", "BACKTEST:B", "BACKTEST:C", "BACKTEST:D")
        assertThat(runs.trades.map { Triple(it.symbol, it.side, it.timestamp) })
            .containsExactlyElementsOf(trades.map { Triple(it.symbol, it.side, it.timestamp) })
        val unavailable = runs.journal.filter { it.change == MembershipChange.UNAVAILABLE }
        assertThat(unavailable.map { it.member }).containsExactly("BACKTEST:E")
        val stepped = runs.journal.filter { it.change != MembershipChange.RESTORE } - unavailable.toSet()
        assertThat(stepped).containsExactlyElementsOf(journal)
    }
}
