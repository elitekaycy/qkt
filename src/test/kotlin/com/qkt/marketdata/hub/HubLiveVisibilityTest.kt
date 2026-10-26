package com.qkt.marketdata.hub

import com.qkt.marketdata.Tick
import java.math.BigDecimal
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong
import kotlin.io.path.createDirectories
import kotlin.io.path.writeBytes
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * A live session sees a hub fact at the same instant a backtest would: its known_at plus the minimum
 * lag, never earlier, and never not at all -- whether the record was in the store at start or appended
 * during the session, to today's file or a backfilled older one.
 */
class HubLiveVisibilityTest {
    private val effectiveAt = 1_789_043_400_000L

    private fun fixture(): ByteArray =
        checkNotNull(javaClass.getResourceAsStream("/hub/cal.high_impact.ndjson")).use { it.readAllBytes() }

    private fun manifest(): ByteArray =
        checkNotNull(javaClass.getResourceAsStream("/hub/manifest.json")).use { it.readAllBytes() }

    private fun store(root: Path): Path {
        val dir = root.resolve("journal").resolve("cal.high_impact")
        dir.createDirectories()
        dir.resolve("2026-09-10.ndjson").writeBytes(fixture())
        root.resolve("manifest.json").writeBytes(manifest())
        Files.writeString(root.resolve("heartbeat"), "1")
        return root
    }

    private fun record(
        seq: Long,
        scope: String,
        knownAt: Long,
        actual: String,
    ): String =
        """{"v":1,"dataset":"cal.high_impact","scope":"$scope","key":"k$seq","revision":1,""" +
            """"known_at":$knownAt,"effective_at":$effectiveAt,"availability":"observed","source":"t",""" +
            """"seq":$seq,"fields":{"impact":2,"forecast":"0.2","actual":"$actual"}}"""

    private fun tail(
        root: Path,
        vararg symbols: String,
        clock: () -> Long = { System.currentTimeMillis() },
    ): Pair<HubTailSource, CopyOnWriteArrayList<Tick>> {
        val ticks = CopyOnWriteArrayList<Tick>()
        val source = HubTailSource(root, symbols.toList(), pollIntervalMs = 20L, clock = clock)
        source.start(onTick = { ticks.add(it) }, onError = { throw it }, onDisconnect = {}, onReconnect = {})
        return source to ticks
    }

    private fun await(
        timeoutMs: Long = 3_000L,
        until: () -> Boolean,
    ) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!until() && System.currentTimeMillis() < deadline) Thread.sleep(10)
    }

    @Test
    fun `a record already in the store but not yet visible is delivered at its known_at, not dropped`(
        @TempDir tmp: Path,
    ) {
        val root = store(tmp)
        val file = root.resolve("journal").resolve("cal.high_impact").resolve("2026-09-10.ndjson")
        // The fixture's own release is known 1 s after effectiveAt; start the clock past it.
        val now = AtomicLong(effectiveAt + 10_000L)
        val knownAt = effectiveAt + 50_000L
        Files.writeString(file, Files.readString(file) + record(99, "USD", knownAt, "0.7") + "\n")
        val symbol = HubStreamSymbol("cal.high_impact", "USD", "actual").symbol
        val (source, ticks) = tail(root, symbol, clock = { now.get() })
        try {
            Thread.sleep(120)
            assertThat(ticks).isEmpty()
            now.set(knownAt)
            await { ticks.isNotEmpty() }
            assertThat(ticks).hasSize(1)
            assertThat(ticks.single().timestamp).isEqualTo(knownAt)
        } finally {
            source.stop()
        }
    }

    @Test
    fun `an appended record ahead of the clock is held until its known_at`(
        @TempDir tmp: Path,
    ) {
        val root = store(tmp)
        val file = root.resolve("journal").resolve("cal.high_impact").resolve("2026-09-10.ndjson")
        val now = AtomicLong(effectiveAt + 10_000L)
        val symbol = HubStreamSymbol("cal.high_impact", "USD", "actual").symbol
        val (source, ticks) = tail(root, symbol, clock = { now.get() })
        try {
            val knownAt = effectiveAt + 20_000L
            Files.writeString(file, Files.readString(file) + record(99, "USD", knownAt, "0.7") + "\n")
            Thread.sleep(120)
            assertThat(ticks).isEmpty()
            now.set(knownAt - 1)
            Thread.sleep(80)
            assertThat(ticks).isEmpty()
            now.set(knownAt)
            await { ticks.isNotEmpty() }
            assertThat(ticks.single().timestamp).isEqualTo(knownAt)
        } finally {
            source.stop()
        }
    }

    @Test
    fun `the minimum lag delays a live record exactly as it delays a backtest`(
        @TempDir tmp: Path,
    ) {
        val root = store(tmp)
        val file = root.resolve("journal").resolve("cal.high_impact").resolve("2026-09-10.ndjson")
        // Past the fixture's own release (known 1 s after effectiveAt, visible 31 s after with this lag).
        val knownAt = effectiveAt + 50_000L
        val now = AtomicLong(knownAt + 10_000L)
        val symbol = HubStreamSymbol("cal.high_impact", "USD", "actual").symbol
        val ticks = CopyOnWriteArrayList<Tick>()
        val source =
            HubTailSource(
                root,
                listOf(symbol),
                HubPolicy(minLagMs = 30_000L),
                pollIntervalMs = 20L,
                clock = { now.get() },
            )
        source.start(onTick = { ticks.add(it) }, onError = { throw it }, onDisconnect = {}, onReconnect = {})
        try {
            Files.writeString(file, Files.readString(file) + record(99, "USD", knownAt, "0.7") + "\n")
            Thread.sleep(120)
            assertThat(ticks).isEmpty()
            now.set(knownAt + 30_000L)
            await { ticks.isNotEmpty() }
            assertThat(ticks.single().timestamp).isEqualTo(knownAt + 30_000L)
        } finally {
            source.stop()
        }
    }

    @Test
    fun `a record backfilled into an older day file is still delivered, once`(
        @TempDir tmp: Path,
    ) {
        val root = store(tmp)
        val symbol = HubStreamSymbol("cal.high_impact", "USD", "actual").symbol
        val (source, ticks) = tail(root, symbol)
        try {
            val older = root.resolve("journal").resolve("cal.high_impact").resolve("2026-09-01.ndjson")
            Files.writeString(older, record(100, "USD", effectiveAt - 86_400_000L, "0.4") + "\n")
            await { ticks.isNotEmpty() }
            Thread.sleep(100)
            assertThat(ticks).hasSize(1)
            assertThat(ticks.single().price).isEqualByComparingTo(BigDecimal("0.4"))
        } finally {
            source.stop()
        }
    }
}
