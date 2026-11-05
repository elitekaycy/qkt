package com.qkt.marketdata.source

import com.qkt.common.Clock
import com.qkt.derivatives.futures.ContinuousChain
import com.qkt.derivatives.futures.LiveRollMeasurer
import com.qkt.derivatives.futures.RollHistoryBuilder
import com.qkt.derivatives.futures.RollSchedule
import com.qkt.instrument.ContinuousSelector
import com.qkt.instrument.ContractCatalog
import com.qkt.instrument.FuturesRoot
import com.qkt.instrument.ListedContract
import com.qkt.instrument.PriceAdjustment
import com.qkt.instrument.RollHistoryStore
import com.qkt.instrument.RollPolicy
import com.qkt.marketdata.Candle
import com.qkt.marketdata.Tick
import com.qkt.marketdata.TickFeed
import java.math.BigDecimal
import java.nio.file.Path
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneOffset
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/** A root with a contract expiring every day at 08:00, rolled a day before expiry (qkt#1637). */
class ContinuousLiveFeedDailyRollTest {
    private fun ms(iso: String) = Instant.parse(iso).toEpochMilli()

    private val root =
        FuturesRoot(
            "PAPER_DERIBIT:BTC_USDC",
            "USDC",
            BigDecimal.ONE,
            BigDecimal("2.5"),
            BigDecimal("0.001"),
            BigDecimal("0.001"),
            null,
            null,
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            null,
            RollPolicy(1, LocalTime.of(8, 0), PriceAdjustment.PANAMA),
        )
    private val days = listOf(3, 4, 5, 6, 7, 8)
    private val catalog =
        ContractCatalog(
            root.root,
            days.map { ListedContract("BTC_USDC_${it}NOV26", ms("2026-11-0${it}T08:00:00Z")) },
        )
    private val schedule = RollSchedule(catalog.contracts, root.roll!!)

    /** One closed 1-minute bar per contract every hour from 11-01 to 11-06, each at its own price. */
    private val bars =
        days.flatMap { day ->
            (0 until 6 * 24).map { hour ->
                val start = ms("2026-11-01T00:00:00Z") + hour * 3_600_000L
                val close = BigDecimal(80_000 + day * 10 + hour % 7)
                Candle("BTC_USDC_${day}NOV26", close, close, close, close, BigDecimal.ONE, start, start + 60_000)
            }
        }

    private fun minuteBars(
        contract: String,
        fromMs: Long,
        toMs: Long,
    ) = bars.filter { it.symbol == contract && it.startTime in fromMs until toMs }

    private fun byDay(
        contract: String,
        day: java.time.LocalDate,
    ) = bars.filter {
        it.symbol == contract && Instant.ofEpochMilli(it.startTime).atZone(ZoneOffset.UTC).toLocalDate() == day
    }

    @Test
    fun `each roll time holds one front roll, into the contract expiring the day after`() {
        val rolls = schedule.transitions.map { t -> Instant.ofEpochMilli(t.atMs).toString() to t.toIndex - t.fromIndex }

        assertThat(rolls.map { it.first })
            .containsExactly(
                "2026-11-02T08:00:00Z",
                "2026-11-03T08:00:00Z",
                "2026-11-04T08:00:00Z",
                "2026-11-05T08:00:00Z",
                "2026-11-06T08:00:00Z",
            )
        assertThat(rolls.map { it.second }).containsOnly(1)
        assertThat(schedule.contractAt(ms("2026-11-04T08:00:00Z"), ContinuousSelector.FRONT)!!.symbol)
            .isEqualTo("BTC_USDC_6NOV26")
    }

    @Test
    fun `the history holds the front and the next roll at each instant, one each`() {
        val history = RollHistoryBuilder(asOfMs = ms("2026-11-05T09:00:00Z"), bars = ::byDay).build(root, catalog)

        val at = history.rolls.filter { it.atMs == ms("2026-11-04T08:00:00Z") }.map { "${it.from}->${it.to}" }

        assertThat(at).containsExactly("BTC_USDC_5NOV26->BTC_USDC_6NOV26", "BTC_USDC_6NOV26->BTC_USDC_7NOV26")
        val front = ContinuousChain(root, catalog, history, ContinuousSelector.FRONT)
        assertThat(front.rollOutOf(2).atMs).isEqualTo(ms("2026-11-04T08:00:00Z"))
    }

    @Test
    fun `a stream back after missing two rolls measures both in order and serves the new front`(
        @TempDir dir: Path,
    ) {
        val store = RollHistoryStore(dir)
        val built = RollHistoryBuilder(bars = ::byDay).build(root, catalog, setOf(ContinuousSelector.FRONT))
        store.write(built.copy(rolls = built.rolls.filter { it.atMs <= ms("2026-11-03T08:00:00Z") }))
        var now = ms("2026-11-05T15:49:00Z")
        val measured = mutableListOf<Long>()
        val measurer = LiveRollMeasurer(::minuteBars, store)
        val ticks =
            listOf(now, now + 5_000).map {
                Tick("PAPER_DERIBIT:BTC_USDC_7NOV26", BigDecimal("80070"), it, bid = BigDecimal("80070"), ask = null)
            }
        val queue = ArrayDeque(ticks)
        val feed =
            ContinuousLiveFeed(
                chain = { ContinuousChain(root, catalog, store.read(root.root), ContinuousSelector.FRONT) },
                ticks = { _ ->
                    object : TickFeed {
                        override fun next(): Tick? = queue.removeFirstOrNull()
                    }
                },
                measure = { t ->
                    measured += t.atMs
                    measurer.measure(root, catalog, ContinuousSelector.FRONT, t)
                },
                clock =
                    object : Clock {
                        override fun now() = now
                    },
                sleep = { now += it },
            )

        val served = generateSequence { feed.next() }.toList()

        assertThat(feed.terminalFailureReason()).isNull()
        assertThat(served.map { it.timestamp to it.symbol })
            .containsExactly(ticks[1].timestamp to "PAPER_DERIBIT:BTC_USDC@front")
        assertThat(measured).containsExactly(ms("2026-11-04T08:00:00Z"), ms("2026-11-05T08:00:00Z"))
        val rolls = store.read(root.root)!!.rolls.filter { it.atMs >= ms("2026-11-04T08:00:00Z") }
        assertThat(rolls.map { "${it.from}->${it.to}" })
            .containsExactly("BTC_USDC_5NOV26->BTC_USDC_6NOV26", "BTC_USDC_6NOV26->BTC_USDC_7NOV26")
        assertThat(rolls).isEqualTo(built.rolls.filter { it.atMs in ms("2026-11-04T08:00:00Z")..now })
    }
}
