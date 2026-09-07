package com.qkt.marketdata.hub

import com.qkt.marketdata.Tick
import java.math.BigDecimal
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.io.path.createDirectories
import kotlin.io.path.writeBytes
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * The live half: tailing a hub journal as it grows, and refusing a bad binding at feed start.
 *
 * The tail deliberately starts at the END of what is already on disk. What is already there is
 * history, and history is the warmup coordinator's job; replaying a day of facts on every restart
 * would present them as newly published, which is the one thing a point-in-time store must never do.
 */
class HubLiveTest {
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
    fun `what is already on disk is history, not a new publication`(
        @TempDir tmp: Path,
    ) {
        val root = store(tmp)
        val (source, ticks) = tail(root, HubStreamSymbol("cal.high_impact", "USD", "actual").symbol)
        try {
            Thread.sleep(150)
            assertThat(ticks).isEmpty()
        } finally {
            source.stop()
        }
    }

    @Test
    fun `an appended record is delivered, stamped at its known_at`(
        @TempDir tmp: Path,
    ) {
        val root = store(tmp)
        val file = root.resolve("journal").resolve("cal.high_impact").resolve("2026-09-10.ndjson")
        val symbol = HubStreamSymbol("cal.high_impact", "USD", "actual").symbol
        val (source, ticks) = tail(root, symbol)
        try {
            val knownAt = effectiveAt + 5_000L
            Files.writeString(file, Files.readString(file) + record(99, "USD", knownAt, "0.7") + "\n")
            await { ticks.isNotEmpty() }
            assertThat(ticks).hasSize(1)
            assertThat(ticks.single().symbol).isEqualTo(symbol)
            assertThat(ticks.single().timestamp).isEqualTo(knownAt)
            assertThat(ticks.single().price).isEqualByComparingTo(BigDecimal("0.7"))
        } finally {
            source.stop()
        }
    }

    @Test
    fun `a partial trailing line waits until it is complete`(
        @TempDir tmp: Path,
    ) {
        val root = store(tmp)
        val file = root.resolve("journal").resolve("cal.high_impact").resolve("2026-09-10.ndjson")
        val symbol = HubStreamSymbol("cal.high_impact", "USD", "actual").symbol
        val (source, ticks) = tail(root, symbol)
        try {
            val line = record(99, "USD", effectiveAt + 5_000L, "0.7")
            Files.writeString(file, Files.readString(file) + line.substring(0, 40))
            Thread.sleep(120)
            assertThat(ticks).isEmpty()
            Files.writeString(file, Files.readString(file) + line.substring(40) + "\n")
            await { ticks.isNotEmpty() }
            assertThat(ticks).hasSize(1)
        } finally {
            source.stop()
        }
    }

    @Test
    fun `another scope's record is not delivered to this stream`(
        @TempDir tmp: Path,
    ) {
        val root = store(tmp)
        val file = root.resolve("journal").resolve("cal.high_impact").resolve("2026-09-10.ndjson")
        val usd = HubStreamSymbol("cal.high_impact", "USD", "actual").symbol
        val (source, ticks) = tail(root, usd)
        try {
            Files.writeString(file, Files.readString(file) + record(99, "EUR", effectiveAt + 5_000L, "0.7") + "\n")
            Thread.sleep(150)
            assertThat(ticks).isEmpty()
        } finally {
            source.stop()
        }
    }

    @Test
    fun `a stale heartbeat is reported as a disconnect and never ends the feed`(
        @TempDir tmp: Path,
    ) {
        val root = store(tmp)
        val now = System.currentTimeMillis()
        Files.setLastModifiedTime(root.resolve("heartbeat"), FileTime.fromMillis(now - 3_600_000L))
        val disconnects = AtomicInteger()
        val reconnects = AtomicInteger()
        val source =
            HubTailSource(
                root,
                listOf(HubStreamSymbol("cal.high_impact", "USD", "actual").symbol),
                pollIntervalMs = 20L,
                clock = { now },
            )
        source.start(
            onTick = {},
            onError = { throw it },
            onDisconnect = { disconnects.incrementAndGet() },
            onReconnect = { reconnects.incrementAndGet() },
        )
        try {
            await { disconnects.get() > 0 }
            assertThat(disconnects.get()).isEqualTo(1)
            // The beat comes back: reported once, not on every poll.
            Files.setLastModifiedTime(root.resolve("heartbeat"), FileTime.fromMillis(now))
            await { reconnects.get() > 0 }
            Thread.sleep(80)
            assertThat(reconnects.get()).isEqualTo(1)
        } finally {
            source.stop()
        }
    }

    @Test
    fun `a bad binding fails at feed start rather than reading as undefined all session`(
        @TempDir tmp: Path,
    ) {
        val root = store(tmp)
        assertThatThrownBy {
            HubMarketSource(root).liveTicks(listOf(HubStreamSymbol("cal.high_impact", "USD", "surpirse").symbol))
        }.isInstanceOf(
            IllegalArgumentException::class.java,
        ).hasMessageContaining("surpirse")
            .hasMessageContaining("surprise")
    }

    @Test
    fun `the live feed delivers an appended record through the engine's own adaptor`(
        @TempDir tmp: Path,
    ) {
        val root = store(tmp)
        val file = root.resolve("journal").resolve("cal.high_impact").resolve("2026-09-10.ndjson")
        val symbol = HubStreamSymbol("cal.high_impact", "USD", "actual").symbol
        val feed = HubMarketSource(root).liveTicks(listOf(symbol))
        try {
            Files.writeString(file, Files.readString(file) + record(99, "USD", effectiveAt + 5_000L, "0.7") + "\n")
            val tick = feed.next()
            assertThat(tick).isNotNull
            assertThat(tick!!.symbol).isEqualTo(symbol)
            assertThat(tick.price).isEqualByComparingTo(BigDecimal("0.7"))
        } finally {
            feed.close()
        }
    }
}
