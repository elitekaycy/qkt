package com.qkt.persistence

import com.qkt.common.Side
import java.math.BigDecimal
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Excursion marks, saved from ticks, never wait on the disk (#1609): a save returns while the writer is still busy,
 * the latest mark of each leg book is what lands, and nothing pending is lost on close or lands after a clear.
 */
class ExcursionWriteBehindTest {
    private fun mark(mfe: String) =
        PersistedExcursion("L1", Side.BUY, BigDecimal("100"), BigDecimal(mfe), BigDecimal.ZERO, null)

    /** A disk that holds every excursion write until [release] opens. */
    private class SlowDisk : StatePersistor by NoopStatePersistor() {
        val release = CountDownLatch(1)
        val written = CopyOnWriteArrayList<Triple<String, String, String>>()
        val cleared = CopyOnWriteArrayList<String>()

        override fun saveExcursion(
            strategyId: String,
            symbol: String,
            excursion: PersistedExcursion,
        ) {
            release.await(5, TimeUnit.SECONDS)
            written += Triple(strategyId, symbol, excursion.mfe.toPlainString())
        }

        override fun clearStrategy(strategyId: String) {
            cleared += strategyId
        }
    }

    @Test
    fun `a save returns at once while the disk is busy, and the leg's latest mark is the one written`() {
        val disk = SlowDisk()
        val persistor = ExcursionWriteBehind(disk)

        val startedNanos = System.nanoTime()
        persistor.saveExcursion("s", "X:A", mark("1"))
        persistor.saveExcursion("s", "X:B", mark("5"))
        persistor.saveExcursion("s", "X:B", mark("6"))
        val tookMs = (System.nanoTime() - startedNanos) / 1_000_000

        assertThat(tookMs).isLessThan(1_000)
        assertThat(persistor.loadExcursion("s", "X:B")?.mfe).isEqualByComparingTo("6")
        assertThat(persistor.loadExcursion("s", "X:A")?.mfe).isEqualByComparingTo("1")
        disk.release.countDown()
        assertThat(persistor.awaitIdle()).isTrue()
        assertThat(disk.written).containsExactly(Triple("s", "X:A", "1"), Triple("s", "X:B", "6"))
    }

    @Test
    fun `close writes what is pending`() {
        val disk = SlowDisk().also { it.release.countDown() }
        val persistor = ExcursionWriteBehind(disk)
        persistor.saveExcursion("s", "X:A", mark("2"))

        persistor.close()

        assertThat(disk.written).contains(Triple("s", "X:A", "2"))
        persistor.saveExcursion("s", "X:A", mark("3"))
        assertThat(disk.written).contains(Triple("s", "X:A", "3"))
    }

    @Test
    fun `a cleared strategy's pending marks never land after its files are gone`() {
        val disk = SlowDisk()
        val persistor = ExcursionWriteBehind(disk)
        persistor.saveExcursion("other", "X:A", mark("1"))
        persistor.saveExcursion("s", "X:A", mark("2"))

        persistor.clearStrategy("s")
        disk.release.countDown()

        assertThat(persistor.awaitIdle()).isTrue()
        assertThat(disk.cleared).containsExactly("s")
        assertThat(disk.written).containsExactly(Triple("other", "X:A", "1"))
    }
}
