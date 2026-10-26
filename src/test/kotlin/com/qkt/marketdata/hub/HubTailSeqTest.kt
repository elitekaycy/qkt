package com.qkt.marketdata.hub

import com.qkt.marketdata.Tick
import java.math.BigDecimal
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.io.path.createDirectories
import kotlin.io.path.writeBytes
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * The tail delivers each appended record exactly once whatever its `seq`. Hub stores written before
 * the hub fixed its writer's resume (2026-10) number a backfill's old days above the live tail, so
 * a live record can arrive with a `seq` below one already on disk; a guard on `seq` dropped it.
 */
class HubTailSeqTest {
    private val effectiveAt = 1_789_043_400_000L
    private val symbol = HubStreamSymbol("cal.high_impact", "USD", "actual").symbol

    private fun record(
        seq: Long,
        knownAt: Long,
        actual: String,
    ): String =
        """{"v":1,"dataset":"cal.high_impact","scope":"USD","key":"k$seq-$knownAt","revision":1,""" +
            """"known_at":$knownAt,"effective_at":$effectiveAt,"availability":"observed","source":"t",""" +
            """"seq":$seq,"fields":{"impact":2,"forecast":"0.2","actual":"$actual"}}""" + "\n"

    private fun store(root: Path): Path {
        val dir = root.resolve("journal").resolve("cal.high_impact")
        dir.createDirectories()
        val manifest = checkNotNull(javaClass.getResourceAsStream("/hub/manifest.json")).use { it.readAllBytes() }
        root.resolve("manifest.json").writeBytes(manifest)
        Files.writeString(root.resolve("heartbeat"), "1")
        return root
    }

    private fun day(
        root: Path,
        name: String,
    ): Path = root.resolve("journal").resolve("cal.high_impact").resolve("$name.ndjson")

    private fun tail(root: Path): Pair<HubTailSource, CopyOnWriteArrayList<Tick>> {
        val ticks = CopyOnWriteArrayList<Tick>()
        val source = HubTailSource(root, listOf(symbol), pollIntervalMs = 20L)
        source.start(onTick = { ticks.add(it) }, onError = { throw it }, onDisconnect = {}, onReconnect = {})
        return source to ticks
    }

    private fun await(until: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 3_000L
        while (!until() && System.currentTimeMillis() < deadline) Thread.sleep(10)
    }

    @Test
    fun `a live record numbered below a backfilled one is still delivered`(
        @TempDir tmp: Path,
    ) {
        val root = store(tmp)
        // The live day ends at seq 3; a backfill put seq 5992 into a day years back.
        Files.writeString(day(root, "2026-09-10"), (1L..3L).joinToString("") { record(it, effectiveAt + it, "0.$it") })
        Files.writeString(day(root, "2015-04-01"), record(5992, 1_427_850_000_000L, "9.9"))
        val (source, ticks) = tail(root)
        try {
            Thread.sleep(100)
            Files.writeString(
                day(root, "2026-09-10"),
                record(4, effectiveAt + 5_000L, "0.7"),
                java.nio.file.StandardOpenOption.APPEND,
            )
            await { ticks.isNotEmpty() }
            assertThat(
                ticks.map { it.price },
            ).usingElementComparator(BigDecimal::compareTo).containsExactly(BigDecimal("0.7"))
            assertThat(ticks.single().timestamp).isEqualTo(effectiveAt + 5_000L)
        } finally {
            source.stop()
        }
    }

    @Test
    fun `a journal file replaced under the tail is not replayed, and appends after it are delivered once`(
        @TempDir tmp: Path,
    ) {
        val root = store(tmp)
        val file = day(root, "2026-09-10")
        Files.writeString(file, record(1, effectiveAt + 1, "0.1"))
        val (source, ticks) = tail(root)
        try {
            Thread.sleep(100)
            Files.writeString(file, record(2, effectiveAt + 2_000L, "0.2"), java.nio.file.StandardOpenOption.APPEND)
            await { ticks.size == 1 }
            Files.writeString(file, record(1, effectiveAt + 1, "0.1")) // shorter than what was read
            Thread.sleep(100)
            Files.writeString(file, record(3, effectiveAt + 3_000L, "0.3"), java.nio.file.StandardOpenOption.APPEND)
            await { ticks.size >= 2 }
            Thread.sleep(100)
            assertThat(ticks.map { it.price })
                .usingElementComparator(BigDecimal::compareTo)
                .containsExactly(BigDecimal("0.2"), BigDecimal("0.3"))
        } finally {
            source.stop()
        }
    }
}
