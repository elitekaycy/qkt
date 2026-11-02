package com.qkt.app

import com.qkt.dsl.compile.AstCompiler
import com.qkt.dsl.compile.CandleHub
import com.qkt.dsl.compile.DslCompiledStrategy
import com.qkt.dsl.parse.Dsl
import com.qkt.dsl.parse.ParseResult
import com.qkt.marketdata.hub.HubMarketSource
import java.math.BigDecimal
import java.nio.file.Path
import java.time.Instant
import kotlin.io.path.createDirectories
import kotlin.io.path.writeBytes
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * A hub fact published before a session starts must be readable from the session's first tick.
 *
 * A strategy that only reads a hub field (`cal.actual`, a calendar's next release time) declares no
 * lookback, so the bar warmup never touched its stream: the value read as undefined until the hub
 * published the next record, which for a release calendar can be weeks. Live, every restart went
 * blind; in a backtest starting mid-history, the first event was missed.
 */
class LatestObservationSeedTest {
    private val releaseKnownAt = 1_789_043_400_000L + 1_000L

    private fun store(root: Path): Path {
        val dir = root.resolve("journal").resolve("cal.high_impact")
        dir.createDirectories()
        val bytes = checkNotNull(javaClass.getResourceAsStream("/hub/cal.high_impact.ndjson")).use { it.readAllBytes() }
        dir.resolve("2026-09-10.ndjson").writeBytes(bytes)
        return root
    }

    private fun strategy(): DslCompiledStrategy =
        AstCompiler().compile(
            (
                Dsl.parse(
                    """
                    STRATEGY readsHub VERSION 1

                    SYMBOLS
                        gold = BACKTEST:XAUUSD EVERY 1m
                        cal  = HUB:cal.high_impact.USD EVERY 1d

                    RULES
                        WHEN cal.actual IS NOT NULL AND POSITION.gold = 0
                        THEN BUY gold SIZING 0.01
                    """.trimIndent(),
                ) as ParseResult.Success
            ).value,
        ) as DslCompiledStrategy

    @Test
    fun `a session starting after a release is seeded with the released value`(
        @TempDir tmp: Path,
    ) {
        val strategy = strategy()
        val key = checkNotNull(strategy.declaredStreams["cal/actual"])
        val hub = CandleHub()

        PerStreamWarmupCoordinator(
            strategies = listOf("readsHub" to strategy),
            source = HubMarketSource(store(tmp)),
            hub = hub,
            now = Instant.ofEpochMilli(releaseKnownAt + 3_600_000L),
        ).prepareHub()

        val seeded = hub.seededHistory(key)
        assertThat(seeded).hasSize(1)
        assertThat(seeded.single().close).isEqualByComparingTo(BigDecimal("0.3"))
        assertThat(seeded.single().startTime).isEqualTo(releaseKnownAt)
    }

    @Test
    fun `a session starting before the release is not seeded with it`(
        @TempDir tmp: Path,
    ) {
        val strategy = strategy()
        val key = checkNotNull(strategy.declaredStreams["cal/actual"])
        val hub = CandleHub()

        PerStreamWarmupCoordinator(
            strategies = listOf("readsHub" to strategy),
            source = HubMarketSource(store(tmp)),
            hub = hub,
            now = Instant.ofEpochMilli(releaseKnownAt),
        ).prepareHub()

        assertThat(hub.seededHistory(key)).isEmpty()
    }
}
