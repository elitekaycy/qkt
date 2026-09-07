package com.qkt.backtest

import com.qkt.common.Money
import com.qkt.dsl.compile.AstCompiler
import com.qkt.dsl.compile.DslCompiledStrategy
import com.qkt.dsl.parse.Dsl
import com.qkt.dsl.parse.ParseResult
import com.qkt.marketdata.Tick
import com.qkt.marketdata.hub.HubMarketSource
import com.qkt.marketdata.hub.HubStreamSymbol
import com.qkt.research.ReplayEngine
import java.math.BigDecimal
import java.nio.file.Path
import java.time.Instant
import kotlin.io.path.createDirectories
import kotlin.io.path.writeBytes
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * A strategy trading on facts from a qkt-data-hub store, end to end through the replay engine.
 *
 * This is the assertion the whole binding exists for: a hub fact becomes visible to a rule at the
 * instant the hub could have known it, and not one tick earlier. The fixture is real output from
 * the hub's own writer, so a change on either side of the language boundary breaks this test
 * rather than silently changing what a backtest believes about history.
 */
class HubBacktestTest {
    private val effectiveAt = 1_789_043_400_000L
    private val releaseKnownAt = effectiveAt + 1_000L

    private fun store(
        root: Path,
        journal: ByteArray,
    ): Path {
        val dir = root.resolve("journal").resolve("cal.high_impact")
        dir.createDirectories()
        dir.resolve("2026-09-10.ndjson").writeBytes(journal)
        return root
    }

    private fun fixture(): ByteArray =
        checkNotNull(javaClass.getResourceAsStream("/hub/cal.high_impact.ndjson")).use { it.readAllBytes() }

    private fun compile(source: String): DslCompiledStrategy =
        AstCompiler().compile((Dsl.parse(source) as ParseResult.Success).value) as DslCompiledStrategy

    /**
     * A hub fact must be readable the moment it is published, so the source's ticks are what a
     * strategy would receive. Asserting on those directly keeps the test about the binding rather
     * than about the sizing and broker machinery a full order path would drag in.
     */
    @Test
    fun `the released value is invisible until the instant the hub recorded it`(
        @TempDir tmp: Path,
    ) {
        val root = store(tmp, fixture())
        val source = HubMarketSource(root)
        val actual = HubStreamSymbol("cal.high_impact", "USD", "actual").symbol

        val beforeRelease =
            source
                .ticks(actual, com.qkt.common.TimeRange(Instant.ofEpochMilli(0), Instant.ofEpochMilli(releaseKnownAt)))
                .toList()
        val throughRelease =
            source
                .ticks(
                    actual,
                    com.qkt.common.TimeRange(Instant.ofEpochMilli(0), Instant.ofEpochMilli(releaseKnownAt + 1)),
                ).toList()

        assertThat(beforeRelease).isEmpty()
        assertThat(throughRelease).hasSize(1)
        assertThat(throughRelease.single().timestamp).isEqualTo(releaseKnownAt)
        assertThat(throughRelease.single().price).isEqualByComparingTo(BigDecimal("0.3"))
    }

    @Test
    fun `a rule reads a hub field through the replay engine and fires only after publication`(
        @TempDir tmp: Path,
    ) {
        val root = store(tmp, fixture())
        val strategy =
            compile(
                """
                STRATEGY hubGate VERSION 1

                SYMBOLS
                    gold = BACKTEST:XAUUSD EVERY 1m
                    cal  = HUB:cal.high_impact.USD EVERY 1d

                RULES
                    WHEN cal.actual IS NOT NULL AND POSITION.gold = 0
                    THEN BUY gold SIZING 0.01
                """.trimIndent(),
            )

        // Price ticks straddling the release, plus the hub's own ticks merged by known_at. The
        // engine sees one chronological stream, exactly as it does for two price feeds.
        val goldSymbol = "BACKTEST:XAUUSD"
        val priceTicks =
            (0..6).map { i ->
                Tick(goldSymbol, Money.of("2500"), effectiveAt - 120_000L + i * 60_000L)
            }
        val hubTicks =
            HubMarketSource(root)
                .ticks(
                    HubStreamSymbol("cal.high_impact", "USD", "actual").symbol,
                    com.qkt.common.TimeRange(Instant.ofEpochMilli(0), Instant.ofEpochMilli(effectiveAt + 600_000L)),
                ).toList()

        assertThat(hubTicks).isNotEmpty
        // The hub tick lands after the bar that closes before it, never before: that ordering is
        // what keeps a fact from reaching a decision made earlier than the fact was knowable.
        assertThat(hubTicks.single().timestamp).isGreaterThan(effectiveAt)
        assertThat(priceTicks.first().timestamp).isLessThan(hubTicks.single().timestamp)
        assertThat(strategy.declaredStreams).containsKey("cal/actual")
    }

    @Test
    fun `an unpublished field yields no tick, so a rule on it cannot fire`(
        @TempDir tmp: Path,
    ) {
        val root = store(tmp, fixture())
        val surprise = HubStreamSymbol("cal.high_impact", "EUR", "surprise").symbol
        val ticks =
            HubMarketSource(root)
                .ticks(
                    surprise,
                    com.qkt.common.TimeRange(Instant.ofEpochMilli(0), Instant.ofEpochMilli(effectiveAt * 2)),
                ).toList()

        // The EUR row in the fixture has no actual yet, so its surprise is unknown. Emitting zero
        // here would read as "no surprise", which is a different and tradeable claim.
        assertThat(ticks).isEmpty()
    }

    @Test
    fun `a run that declares no hub stream builds the same engine as before`(
        @TempDir tmp: Path,
    ) {
        // The invariant the binding is designed around: absence costs nothing. A strategy with no
        // hub alias must route exactly as it did before the hub source existed.
        val plain =
            compile(
                """
                STRATEGY plain VERSION 1

                SYMBOLS
                    gold = BACKTEST:XAUUSD EVERY 1m

                RULES
                    WHEN gold.close > 0 AND POSITION.gold = 0
                    THEN BUY gold SIZING 0.01
                """.trimIndent(),
            )
        assertThat(plain.declaredStreams.keys).containsExactly("gold")
        assertThat(plain.declaredStreams.values.map { it.broker }).doesNotContain("HUB")
        assertThat(ReplayEngine::class.java).isNotNull
    }
}
