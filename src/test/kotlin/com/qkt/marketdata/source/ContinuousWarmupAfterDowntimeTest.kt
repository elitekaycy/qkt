package com.qkt.marketdata.source

import com.qkt.candles.TimeWindow
import com.qkt.common.FixedClock
import com.qkt.common.TimeRange
import com.qkt.derivatives.futures.ContinuousChains
import com.qkt.instrument.RollHistoryStore
import com.qkt.marketdata.source.DailyExpiryChain.FRONT
import com.qkt.marketdata.source.DailyExpiryChain.ms
import com.qkt.marketdata.source.DailyExpiryChain.root
import java.nio.file.Path
import java.time.Instant
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * qkt#1647: a live session back after a downtime that spanned two daily rolls warms its `@front` strategy, and
 * extends its legs' excursions, from bars across those rolls before the stream's first tick. Without the rolls
 * measured first the read fails, the strategy never deploys, and a contract that settled meanwhile is never booked.
 */
class ContinuousWarmupAfterDowntimeTest {
    private val now = ms("2026-11-05T15:49:00Z")

    /** A live session's source at [now] over the history last measured at the 11-03 roll; no [live]: no streams. */
    private fun source(
        store: RollHistoryStore,
        live: Boolean = true,
    ): ContinuousMarketSource {
        DailyExpiryChain.stored(store)
        val registry = DailyExpiryChain.registry(store)
        val directory = requireNotNull(registry.futures())
        val chains = ContinuousChains(directory)
        val account = DailyExpiryChain.account()
        val streams = LiveContinuousStreams(chains, directory, store, account, FixedClock(now))
        return ContinuousMarketSource(account, chains, registry, streams.takeIf { live })
    }

    private val warmup = TimeRange(Instant.parse("2026-11-03T15:00:00Z"), Instant.ofEpochMilli(now))

    @Test
    fun `the bars across the missed rolls are served once the rolls are measured, each from its own contract`(
        @TempDir dir: Path,
    ) {
        val store = RollHistoryStore(dir)

        val served = source(store).bars(FRONT, TimeWindow.ONE_MINUTE, warmup).toList()

        assertThat(served.map { it.symbol }.distinct()).containsExactly(FRONT)
        assertThat(served.map { it.startTime })
            .contains(ms("2026-11-04T07:00:00Z"), ms("2026-11-04T09:00:00Z"), ms("2026-11-05T15:00:00Z"))
        val rolls = requireNotNull(store.read(root.root)).rolls.filter { it.atMs >= ms("2026-11-04T08:00:00Z") }
        assertThat(rolls.map { "${it.from}->${it.to}" })
            .containsExactly("ETH_USDC_5NOV26->ETH_USDC_6NOV26", "ETH_USDC_6NOV26->ETH_USDC_7NOV26")
    }

    @Test
    fun `a source with no live streams still refuses a history that lacks the rolls`(
        @TempDir dir: Path,
    ) {
        val source = source(RollHistoryStore(dir), live = false)

        assertThatThrownBy { source.bars(FRONT, TimeWindow.ONE_MINUTE, warmup).toList() }
            .hasMessageContaining("after the last measured roll")
    }

    @Test
    fun `a roll still ahead of the session is never measured early`(
        @TempDir dir: Path,
    ) {
        val store = RollHistoryStore(dir)
        val ahead = TimeRange(Instant.parse("2026-11-05T09:00:00Z"), Instant.parse("2026-11-06T09:00:00Z"))

        runCatching { source(store).bars(FRONT, TimeWindow.ONE_MINUTE, ahead).toList() }

        assertThat(requireNotNull(store.read(root.root)).rolls.map { it.atMs }).allMatch { it <= now }
    }
}
