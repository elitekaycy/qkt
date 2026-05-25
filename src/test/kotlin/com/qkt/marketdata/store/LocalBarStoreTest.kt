package com.qkt.marketdata.store

import com.qkt.marketdata.Candle
import java.math.BigDecimal
import java.nio.file.Path
import java.time.LocalDate
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class LocalBarStoreTest {
    private val day = LocalDate.parse("2024-01-15")

    private fun bar(
        ts: Long,
        close: String = "100.50",
    ): Candle =
        Candle(
            symbol = "EXNESS:XAUUSD",
            open = BigDecimal("100.00"),
            high = BigDecimal("101.00"),
            low = BigDecimal("99.50"),
            close = BigDecimal(close),
            volume = BigDecimal("1.5"),
            startTime = ts,
            endTime = ts + 60_000L,
        )

    @Test
    fun `hasDay is false on a fresh store`(
        @TempDir tmp: Path,
    ) {
        val store = LocalBarStore(root = tmp)
        assertThat(store.hasDay("EXNESS", "XAUUSD", "1m", day)).isFalse
    }

    @Test
    fun `writeDay then hasDay is true`(
        @TempDir tmp: Path,
    ) {
        val store = LocalBarStore(root = tmp)
        store.writeDay("EXNESS", "XAUUSD", "1m", day, listOf(bar(1_704_067_200_000L)))
        assertThat(store.hasDay("EXNESS", "XAUUSD", "1m", day)).isTrue
    }

    @Test
    fun `writeDay then readDay round-trips bars in chronological order`(
        @TempDir tmp: Path,
    ) {
        val store = LocalBarStore(root = tmp)
        val bars =
            listOf(
                bar(1_704_067_320_000L, "100.6"),
                bar(1_704_067_200_000L, "100.5"),
                bar(1_704_067_260_000L, "100.55"),
            )
        store.writeDay("EXNESS", "XAUUSD", "1m", day, bars)

        val read = store.readDay("EXNESS", "XAUUSD", "1m", day)

        assertThat(read).hasSize(3)
        assertThat(read.map { it.startTime })
            .containsExactly(1_704_067_200_000L, 1_704_067_260_000L, 1_704_067_320_000L)
        assertThat(read[0].close).isEqualByComparingTo("100.5")
        assertThat(read[0].symbol).isEqualTo("EXNESS:XAUUSD")
    }

    @Test
    fun `readDay returns empty when file missing`(
        @TempDir tmp: Path,
    ) {
        val store = LocalBarStore(root = tmp)
        assertThat(store.readDay("EXNESS", "XAUUSD", "1m", day)).isEmpty()
    }

    @Test
    fun `readManifest on fresh store returns empty ranges`(
        @TempDir tmp: Path,
    ) {
        val store = LocalBarStore(root = tmp)
        val m = store.readManifest("EXNESS", "XAUUSD", "1m")
        assertThat(m.ranges).isEmpty()
        assertThat(m.broker).isEqualTo("EXNESS")
        assertThat(m.symbol).isEqualTo("XAUUSD")
        assertThat(m.timeframe).isEqualTo("1m")
    }

    @Test
    fun `recordDay creates a single-day range`(
        @TempDir tmp: Path,
    ) {
        val store = LocalBarStore(root = tmp)
        store.recordDay("EXNESS", "XAUUSD", "1m", day)
        val m = store.readManifest("EXNESS", "XAUUSD", "1m")
        assertThat(m.ranges).hasSize(1)
        assertThat(m.ranges.single()).isEqualTo(DayRange("2024-01-15", "2024-01-15"))
    }

    @Test
    fun `recordDay coalesces adjacent days into one range`(
        @TempDir tmp: Path,
    ) {
        val store = LocalBarStore(root = tmp)
        store.recordDay("EXNESS", "XAUUSD", "1m", LocalDate.parse("2024-01-15"))
        store.recordDay("EXNESS", "XAUUSD", "1m", LocalDate.parse("2024-01-16"))
        store.recordDay("EXNESS", "XAUUSD", "1m", LocalDate.parse("2024-01-17"))
        val m = store.readManifest("EXNESS", "XAUUSD", "1m")
        assertThat(m.ranges).hasSize(1)
        assertThat(m.ranges.single()).isEqualTo(DayRange("2024-01-15", "2024-01-17"))
    }

    @Test
    fun `recordDay leaves a gap when days are not adjacent`(
        @TempDir tmp: Path,
    ) {
        val store = LocalBarStore(root = tmp)
        store.recordDay("EXNESS", "XAUUSD", "1m", LocalDate.parse("2024-01-15"))
        store.recordDay("EXNESS", "XAUUSD", "1m", LocalDate.parse("2024-01-20"))
        val m = store.readManifest("EXNESS", "XAUUSD", "1m")
        assertThat(m.ranges).hasSize(2)
    }

    @Test
    fun `recordDay is idempotent on the same day`(
        @TempDir tmp: Path,
    ) {
        val store = LocalBarStore(root = tmp)
        store.recordDay("EXNESS", "XAUUSD", "1m", day)
        store.recordDay("EXNESS", "XAUUSD", "1m", day)
        val m = store.readManifest("EXNESS", "XAUUSD", "1m")
        assertThat(m.ranges).hasSize(1)
    }

    @Test
    fun `different timeframes and brokers don't collide`(
        @TempDir tmp: Path,
    ) {
        val store = LocalBarStore(root = tmp)
        store.writeDay("EXNESS", "XAUUSD", "1m", day, listOf(bar(0L, "1.0")))
        store.writeDay("EXNESS", "XAUUSD", "5m", day, listOf(bar(0L, "2.0")))
        store.writeDay("BYBIT_SPOT", "BTCUSDT", "1m", day, listOf(bar(0L, "3.0")))

        assertThat(store.readDay("EXNESS", "XAUUSD", "1m", day).single().close).isEqualByComparingTo("1.0")
        assertThat(store.readDay("EXNESS", "XAUUSD", "5m", day).single().close).isEqualByComparingTo("2.0")
        assertThat(store.readDay("BYBIT_SPOT", "BTCUSDT", "1m", day).single().close).isEqualByComparingTo("3.0")
    }
}
