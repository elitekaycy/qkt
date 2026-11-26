package com.qkt.parity

import com.qkt.candles.TimeWindow
import com.qkt.cli.daemon.LiveSetPoller
import com.qkt.instrument.EventContracts
import com.qkt.instrument.InstrumentMeta
import com.qkt.instrument.universe.MembershipChange
import com.qkt.marketdata.Tick
import java.math.BigDecimal
import java.nio.file.Path
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * A moving set whose member rules read an indicator and the bar before (`sma(m.close, 3)`, `m.close[1]`), run live
 * with a restart in the middle of a bar and a contract subscribed only after the start, against one backtest. A live
 * member reads the history it lacks from the venue's bars, so its series must have every bar the backtest's has:
 * the decisions right after the restart (B closes at 13h) and right after the late join (D buys at 28h) each hinge
 * on the bar that closed just before.
 */
class LiveSetWarmupParityTest {
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
    private val late = contract("D", 27)

    private val source =
        "STRATEGY roll VERSION 1\nSYMBOLS\n" +
            "    s = BACKTEST:* WHERE kind = event TOP 1 BY symbol DESC DYNAMIC EVERY 1h\n" +
            "RULES\n    FOR EACH m IN s DO\n" +
            "      WHEN m.close > sma(m.close, 3) AND m.close[1] < m.close AND POSITION.m = 0 THEN BUY m SIZING 1\n" +
            "    FOR EACH m IN s DO\n" +
            "      WHEN m.close < sma(m.close, 3) AND POSITION.m > 0 THEN CLOSE m"

    /** Each contract's price in each hour: 100 unless named here. */
    private val prices =
        mapOf(
            "B" to mapOf(9 to "99", 10 to "102", 11 to "110", 12 to "104"),
            "D" to mapOf(26 to "90", 27 to "96", 28 to "96", 29 to "96"),
        )

    private val ticks: List<Tick> =
        (0 until 30 * 6).flatMap { i ->
            listOf("A", "B", "C", "D").map { code ->
                Tick("BACKTEST:$code", BigDecimal(prices[code]?.get(i / 6) ?: "100"), t0 + i * 600_000L)
            }
        }

    @Test
    fun `a live member's indicators read the same bars as the backtest's after a restart and a late join`() {
        val runs = LiveSetSessions(tmp, "roll", source, TimeWindow.ONE_HOUR, ticks)
        runs.listed += atStart
        val (journal, trades) =
            BacktestLiveParityFixtures.withQuietParityLogs { runs.backtest(atStart + late, t0, t0 + 30 * hour) }
        BacktestLiveParityFixtures.withQuietParityLogs {
            runs.session(t0, t0 + 12 * hour + hour / 2) { _, tape -> LiveSetVenue(tape, emptyMap(), ticks) }
            runs.session(t0 + 12 * hour + hour / 2, t0 + 30 * hour) { host, tape ->
                lateinit var venue: LiveSetVenue
                val poller = LiveSetPoller(host, runs.catalog, subscribe = { venue.feed.add(it) })
                val listing = {
                    runs.listed += late
                    poller.poll(t0 + 26 * hour)
                }
                venue = LiveSetVenue(tape, mapOf(t0 + 26 * hour to listing), ticks)
                venue
            }
        }

        assertThat(trades.map { Triple(it.symbol, it.side, (it.timestamp - t0) / hour) }).containsExactly(
            Triple("BACKTEST:B", "BUY", 11L),
            Triple("BACKTEST:B", "SELL", 13L),
            Triple("BACKTEST:D", "BUY", 28L),
        )
        assertThat(runs.trades.map { Triple(it.symbol, it.side, it.timestamp) })
            .containsExactlyElementsOf(trades.map { Triple(it.symbol, it.side, it.timestamp) })
        assertThat(runs.journal.filter { it.change != MembershipChange.RESTORE }).containsExactlyElementsOf(journal)
    }
}
