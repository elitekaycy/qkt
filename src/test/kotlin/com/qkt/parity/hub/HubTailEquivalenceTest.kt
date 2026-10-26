package com.qkt.parity.hub

import com.qkt.common.TimeRange
import com.qkt.marketdata.Tick
import com.qkt.marketdata.hub.HubMarketSource
import com.qkt.marketdata.hub.HubPolicy
import com.qkt.marketdata.hub.HubTailSource
import com.qkt.parity.hub.HubFixtureStore.Field
import com.qkt.parity.hub.HubFixtureStore.Rec
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * The live tail emits exactly the ticks a backtest reads for the session's window: the same symbols,
 * instants and values, for every record shape the scenario tests use. This is what lets those tests
 * drive the live session with backtest ticks and still speak for live.
 */
class HubTailEquivalenceTest {
    private val start = Instant.parse("2024-03-05T00:00:00Z").toEpochMilli()

    private fun m(minutes: Long) = start + minutes * 60_000L + 30_000L

    private val symbols =
        listOf(
            "HUB:test.release.USD/actual",
            "HUB:test.release.USD/forecast",
            "HUB:test.release.USD/known_at",
            "HUB:test.release.EUR/actual",
            "HUB:test.release.EUR/known_at",
        )

    private fun key(t: Tick) = Triple(t.timestamp, t.symbol, t.price.stripTrailingZeros())

    private fun check(
        tmp: Path,
        policy: HubPolicy,
    ) {
        val stored =
            listOf(
                Rec("USD", start - 3_600_000L, mapOf("actual" to "0.1", "forecast" to "0.1"), key = "old"),
                Rec("USD", m(10), mapOf("actual" to "0.5", "forecast" to "0.3"), key = "r1"),
                Rec("USD", m(30), mapOf("actual" to null, "forecast" to "0.4"), key = "r2"),
                Rec("EUR", m(30), mapOf("actual" to "1.0", "forecast" to "0.9"), key = "e1", availability = "derived"),
                Rec("USD", m(45), mapOf("actual" to "0.2", "forecast" to "0.3"), key = "late", effectiveAt = m(5)),
            )
        val root =
            HubFixtureStore(
                tmp,
            ).dataset("test.release", listOf(Field("actual"), Field("forecast")), stored).write()
        val appended =
            listOf(
                Rec("USD", m(60), mapOf("actual" to "0.6", "forecast" to "0.4"), key = "r2", revision = 2),
                Rec("EUR", m(70), mapOf("actual" to null, "forecast" to "1.1"), key = "e2"),
            )
        val now = AtomicLong(start)
        val got = CopyOnWriteArrayList<Tick>()
        val tail = HubTailSource(root, symbols, policy, pollIntervalMs = 10L, clock = { now.get() })
        tail.start(onTick = { got.add(it) }, onError = { throw it }, onDisconnect = {}, onReconnect = {})
        try {
            Thread.sleep(100)
            // Records appended during the session, in a new day file, with the journal's next seq numbers.
            val day = root.resolve("journal").resolve("test.release").resolve("2024-03-05.ndjson")
            val lines =
                appended.mapIndexed { i, r ->
                    val fields =
                        r.fields.entries.joinToString(",") { (k, v) ->
                            "\"$k\":" +
                                (v?.let { "\"$it\"" } ?: "null")
                        }
                    """{"v":1,"dataset":"test.release","scope":"${r.scope}","key":"${r.key}",""" +
                        """"revision":${r.revision},"known_at":${r.knownAt},"effective_at":${r.effectiveAt},""" +
                        """"availability":"${r.availability}","source":"fixture",""" +
                        """"seq":${stored.size + i + 1},"fields":{$fields}}""" + "\n"
                }
            Files.writeString(day, lines.joinToString(""), StandardOpenOption.CREATE, StandardOpenOption.APPEND)
            for (step in 0L..200L) {
                now.set(start + step * 60_000L)
                Thread.sleep(15)
            }
            Thread.sleep(100)
        } finally {
            tail.stop()
        }
        val end = Instant.ofEpochMilli(start + 200 * 60_000L)
        val source = HubMarketSource(root, policy)
        val expected =
            symbols.flatMap { s ->
                source.ticks(s, TimeRange(Instant.ofEpochMilli(start + 1), end)).toList()
            }
        assertThat(got.map(::key).sortedWith(compareBy({ it.first }, { it.second })))
            .isEqualTo(expected.map(::key).sortedWith(compareBy({ it.first }, { it.second })))
        // Every record the tail delivers carries its fields before the stream saying it arrived.
        val order = got.withIndex().groupBy { it.value.timestamp to it.value.symbol.substringBefore('/') }
        for ((record, ticks) in order) {
            val arrived = ticks.filter { it.value.symbol.endsWith("/known_at") }.map { it.index }
            val fields = ticks.filterNot { it.value.symbol.endsWith("/known_at") }.map { it.index }
            assertThat(arrived).describedAs("record stream for %s", record).hasSize(1)
            assertThat(fields).describedAs("fields of %s", record).allMatch { it < arrived.single() }
        }
    }

    @Test
    fun `the tail delivers what a backtest reads, with no policy`(
        @TempDir tmp: Path,
    ) = check(tmp, HubPolicy())

    @Test
    fun `the tail delivers what a backtest reads, with a minimum lag and derived records refused`(
        @TempDir tmp: Path,
    ) = check(tmp, HubPolicy(minLagMs = 120_000L, refuseDerived = true))
}
