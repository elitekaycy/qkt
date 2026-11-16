package com.qkt.marketdata.hub

import com.qkt.marketdata.Tick
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.io.path.createDirectories
import kotlin.io.path.writeBytes
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/** The tail outlives a bad journal line and a failing consumer: it reports the error and keeps delivering. */
class HubTailFailureTest {
    private val effectiveAt = 1_789_043_400_000L
    private val symbol = HubStreamSymbol("cal.high_impact", "USD", "actual").symbol

    private fun record(
        seq: Long,
        actual: String,
    ): String =
        """{"v":1,"dataset":"cal.high_impact","scope":"USD","key":"k$seq","revision":1,""" +
            """"known_at":${effectiveAt + 5_000L},"effective_at":$effectiveAt,""" +
            """"availability":"observed","source":"t",""" +
            """"seq":$seq,"fields":{"impact":2,"forecast":"0.2","actual":"$actual"}}""" + "\n"

    private fun store(root: Path): Path {
        root.resolve("journal").resolve("cal.high_impact").createDirectories()
        val manifest = checkNotNull(javaClass.getResourceAsStream("/hub/manifest.json")).use { it.readAllBytes() }
        root.resolve("manifest.json").writeBytes(manifest)
        Files.writeString(root.resolve("heartbeat"), "1")
        return root.resolve("journal").resolve("cal.high_impact").resolve("2026-09-10.ndjson")
    }

    @Test
    fun `an unparseable journal line is skipped and the next record is still delivered`(
        @TempDir tmp: Path,
    ) {
        val file = store(tmp)
        Files.writeString(file, record(1, "0.1"))
        val ticks = CopyOnWriteArrayList<Tick>()
        val delivered = CountDownLatch(1)
        val source = HubTailSource(tmp, listOf(symbol), pollIntervalMs = 10L)
        source.start({ ticks.add(it).also { delivered.countDown() } }, { throw it }, {}, {})
        try {
            Files.writeString(file, "not json\n" + record(2, "0.2"), StandardOpenOption.APPEND)
            assertThat(delivered.await(5, TimeUnit.SECONDS)).isTrue()
            assertThat(ticks.map { it.price.toPlainString() }).containsExactly("0.2")
        } finally {
            source.stop()
        }
    }

    @Test
    fun `a consumer that throws is reported and the tail keeps running`(
        @TempDir tmp: Path,
    ) {
        val file = store(tmp)
        Files.writeString(file, record(1, "0.1"))
        val errors = CopyOnWriteArrayList<Throwable>()
        val reported = CountDownLatch(1)
        val ticks = CopyOnWriteArrayList<Tick>()
        val later = CountDownLatch(1)
        val source = HubTailSource(tmp, listOf(symbol), pollIntervalMs = 10L)
        source.start(
            onTick = { tick ->
                if (tick.price.toPlainString() == "0.2") error("consumer failed")
                ticks.add(tick)
                later.countDown()
            },
            onError = {
                errors.add(it)
                reported.countDown()
            },
            onDisconnect = {},
            onReconnect = {},
        )
        try {
            Files.writeString(file, record(2, "0.2"), StandardOpenOption.APPEND)
            assertThat(reported.await(5, TimeUnit.SECONDS)).isTrue()
            Files.writeString(file, record(3, "0.3"), StandardOpenOption.APPEND)
            assertThat(later.await(5, TimeUnit.SECONDS)).isTrue()
            assertThat(errors.first()).hasMessage("consumer failed")
            assertThat(ticks.map { it.price.toPlainString() }).containsExactly("0.3")
        } finally {
            source.stop()
        }
    }
}
