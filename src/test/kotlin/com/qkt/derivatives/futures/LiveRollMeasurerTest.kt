package com.qkt.derivatives.futures

import com.qkt.instrument.ContinuousSelector
import com.qkt.instrument.ContractCatalog
import com.qkt.instrument.FuturesRoot
import com.qkt.instrument.ListedContract
import com.qkt.instrument.PriceAdjustment
import com.qkt.instrument.RollHistory
import com.qkt.instrument.RollHistoryStore
import com.qkt.instrument.RollPolicy
import com.qkt.marketdata.Candle
import java.math.BigDecimal
import java.nio.file.Path
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneOffset
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/** A roll measured live is the record `qkt fetch --rolls` computes later from the same 1-minute bars. */
class LiveRollMeasurerTest {
    private fun ms(iso: String) = Instant.parse(iso).toEpochMilli()

    private val root =
        FuturesRoot(
            "BINANCE_UM:BTCUSDT",
            "USDT",
            BigDecimal.ONE,
            BigDecimal("0.1"),
            BigDecimal("0.001"),
            BigDecimal("0.001"),
            null,
            null,
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            null,
            RollPolicy(8, LocalTime.of(8, 0), PriceAdjustment.PANAMA),
        )
    private val catalog =
        ContractCatalog(
            root.root,
            listOf(
                ListedContract("BTCUSDT_240927", ms("2024-09-27T08:00:00Z")),
                ListedContract("BTCUSDT_241227", ms("2024-12-27T08:00:00Z")),
                ListedContract("BTCUSDT_250328", ms("2025-03-28T08:00:00Z")),
            ),
        )
    private val schedule = RollSchedule(catalog.contracts, root.roll!!)
    private val first = schedule.transitions[0]
    private val second = schedule.transitions[1]

    private fun bar(
        contract: String,
        startIso: String,
        close: String,
    ) = Candle(
        contract,
        BigDecimal(close),
        BigDecimal(close),
        BigDecimal(close),
        BigDecimal(close),
        BigDecimal.ONE,
        ms(startIso),
        ms(startIso) + 60_000,
    )

    /** Every bar both contracts of both rolls have, including one that closes after the second roll. */
    private val bars =
        listOf(
            bar("BTCUSDT_240927", "2024-09-19T07:59:00Z", "63000.1"),
            bar("BTCUSDT_241227", "2024-09-19T07:59:00Z", "63800.2"),
            bar("BTCUSDT_241227", "2024-12-18T23:10:00Z", "96000"),
            bar("BTCUSDT_241227", "2024-12-19T07:58:00Z", "97000.5"),
            bar("BTCUSDT_241227", "2024-12-19T08:00:00Z", "11"),
            bar("BTCUSDT_250328", "2024-12-18T22:00:00Z", "98500.5"),
        )

    private fun minuteBars(
        contract: String,
        fromMs: Long,
        toMs: Long,
    ) = bars.filter { it.symbol == contract && it.startTime in fromMs until toMs }

    private fun byDay(
        contract: String,
        day: java.time.LocalDate,
    ) = bars.filter {
        it.symbol == contract &&
            Instant.ofEpochMilli(it.startTime).atZone(ZoneOffset.UTC).toLocalDate() == day
    }

    private fun storeWithFirstRoll(dir: Path): RollHistoryStore {
        val store = RollHistoryStore(dir)
        val built = RollHistoryBuilder(::byDay).build(root, catalog, setOf(ContinuousSelector.FRONT))
        store.write(built.copy(rolls = built.rolls.filter { it.atMs == first.atMs }))
        return store
    }

    @Test
    fun `the next roll measured live equals what the builder measures from the same bars, and is appended`(
        @TempDir dir: Path,
    ) {
        val store = storeWithFirstRoll(dir)

        val live = LiveRollMeasurer(::minuteBars, store).measure(root, catalog, ContinuousSelector.FRONT, second)

        val later = RollHistoryBuilder(::byDay).build(root, catalog, setOf(ContinuousSelector.FRONT))
        val expected = later.find(second.atMs, "BTCUSDT_241227", "BTCUSDT_250328")!!
        assertThat((live as LiveRoll.Measured).record).isEqualTo(expected)
        assertThat(expected.fromPrice).isEqualTo("97000.5")
        assertThat(expected.toPrice).isEqualTo("98500.5")
        assertThat(store.read(root.root)!!.rolls).isEqualTo(later.rolls)
        ContinuousChain(root, catalog, store.read(root.root), ContinuousSelector.FRONT)
    }

    @Test
    fun `measuring a roll already recorded answers the record and writes nothing new`(
        @TempDir dir: Path,
    ) {
        val store = storeWithFirstRoll(dir)
        val measurer = LiveRollMeasurer(::minuteBars, store)
        measurer.measure(root, catalog, ContinuousSelector.FRONT, second)
        val written = store.read(root.root)

        val again = measurer.measure(root, catalog, ContinuousSelector.FRONT, second)

        assertThat(again).isInstanceOf(LiveRoll.Measured::class.java)
        assertThat(store.read(root.root)).isEqualTo(written)
    }

    @Test
    fun `a roll that would leave a gap, or with no history to extend, is refused without writing`(
        @TempDir dir: Path,
    ) {
        val empty = RollHistoryStore(dir.resolve("empty"))
        val gapped =
            RollHistoryStore(
                dir.resolve("gapped"),
            ).also { it.write(RollHistory(root.root, "8d@08:00", emptyList())) }
        val otherPolicy =
            RollHistoryStore(dir.resolve("policy")).also {
                it.write(RollHistory(root.root, "3d@08:00", emptyList()))
            }

        assertThat(LiveRollMeasurer(::minuteBars, empty).measure(root, catalog, ContinuousSelector.FRONT, second))
            .isInstanceOf(LiveRoll.NotNext::class.java)
        assertThat(LiveRollMeasurer(::minuteBars, gapped).measure(root, catalog, ContinuousSelector.FRONT, second))
            .isInstanceOf(LiveRoll.NotNext::class.java)
        assertThat(LiveRollMeasurer(::minuteBars, otherPolicy).measure(root, catalog, ContinuousSelector.FRONT, second))
            .isInstanceOf(LiveRoll.NotNext::class.java)
        assertThat(empty.read(root.root)).isNull()
        assertThat(gapped.read(root.root)!!.rolls).isEmpty()
    }

    @Test
    fun `a roll whose contract has no closed minute at or before the instant is unpriced and not written`(
        @TempDir dir: Path,
    ) {
        val store = storeWithFirstRoll(dir)
        val noNext: (String, Long, Long) -> List<Candle> = { c, f, t ->
            if (c ==
                "BTCUSDT_250328"
            ) {
                emptyList()
            } else {
                minuteBars(c, f, t)
            }
        }

        val roll = LiveRollMeasurer(noNext, store).measure(root, catalog, ContinuousSelector.FRONT, second)

        assertThat(roll).isInstanceOf(LiveRoll.Unpriced::class.java)
        assertThat(store.read(root.root)!!.rolls.map { it.atMs }).containsExactly(first.atMs)
    }
}
