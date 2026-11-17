package com.qkt.cli.daemon

import com.qkt.cli.PerStrategyRisk
import com.qkt.cli.liveRiskLimits
import com.qkt.dsl.sets.SetCatalog
import com.qkt.dsl.sets.SymbolSetSpec
import com.qkt.instrument.InstrumentMeta
import com.qkt.instrument.universe.VenueCatalog
import com.qkt.marketdata.TickFeed
import com.qkt.marketdata.source.MarketSource
import com.qkt.marketdata.source.MarketSourceCapability
import com.qkt.risk.SetCaps
import java.math.BigDecimal
import java.nio.file.Files
import java.nio.file.Path
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * `risk.per_strategy.<name>.sets.<alias>` caps on a deployed strategy's filtered symbol set bind to the members the
 * set resolved to from the venue's listing at start, not to the set's own declaration: a cap must guard the symbols
 * the session actually trades, and a cap on a set the strategy does not declare must refuse the start.
 */
class StrategyHandleSetCapsTest {
    @TempDir
    lateinit var tmp: Path

    private object Silent : MarketSource {
        override val name = "Silent"
        override val capabilities = setOf(MarketSourceCapability.LIVE_TICKS)

        override fun supports(symbol: String) = true

        override fun liveTicks(symbols: List<String>): TickFeed =
            object : TickFeed {
                override fun next() = null

                override fun close() {}
            }
    }

    private fun meta(
        symbol: String,
        category: String,
    ) = InstrumentMeta(
        qktSymbol = symbol,
        contractSize = BigDecimal.ONE,
        volumeStep = BigDecimal("0.01"),
        volumeMin = BigDecimal("0.01"),
        volumeMax = null,
        pointSize = BigDecimal("0.01"),
        digits = 2,
        tradeStopsLevelPoints = 0,
        category = category,
    )

    private val listing =
        object : SetCatalog {
            override val source = "the venue listing"
            override val survivorsOnly = false

            override fun venue(
                alias: String,
                spec: SymbolSetSpec.Filtered,
            ) = VenueCatalog(
                listOf(meta("GW:XAUUSD", "Metals"), meta("GW:XAGUSD", "Metals"), meta("GW:EURUSD", "Forex")),
            )
        }

    private fun strategy(): Path =
        tmp.resolve("metals.qkt").also {
            Files.writeString(
                it,
                """
                STRATEGY metals VERSION 1
                SYMBOLS
                    m = GW:* WHERE category = Metals TOP 5 BY symbol ASC EVERY 1m
                RULES
                    FOR EACH s IN m DO
                      WHEN s.close > 100 THEN BUY s SIZING 1
                """.trimIndent(),
            )
        }

    private fun factory(
        risk: Map<String, PerStrategyRisk>,
        onSource: () -> Unit = {},
    ) = StrategyHandle.RealFactory(
        stateDir = StateDir.resolve(tmp.resolve("state").toString()),
        marketSourceProvider = {
            onSource()
            Silent
        },
        symbolSets = listing,
        perStrategyRisk = risk,
    )

    @Test
    fun `a cap on the filtered set binds to the members it resolved to`() {
        val caps = SetCaps(maxOpenPositions = 1, maxPositionSize = BigDecimal("2"))
        val risk = PerStrategyRisk(sets = mapOf("m" to caps))
        val handle = factory(mapOf("metals" to risk)).create("metals", strategy(), false)
        try {
            val sets = liveRiskLimits("metals", risk, handle.ast.streams)!!.sets
            assertThat(sets.keys).containsExactly("m")
            assertThat(sets.getValue("m").members).containsExactlyInAnyOrder("GW:XAGUSD", "GW:XAUUSD")
            assertThat(sets.getValue("m").maxOpenPositions).isEqualTo(1)
            assertThat(sets.getValue("m").maxPositionSize).isEqualByComparingTo("2")
        } finally {
            handle.close()
        }
    }

    @Test
    fun `a cap on a set the strategy does not declare refuses the start before any market data is requested`() {
        var sourced = false
        val risk = PerStrategyRisk(sets = mapOf("fx" to SetCaps(maxOpenPositions = 1)))

        assertThatThrownBy { factory(mapOf("metals" to risk)) { sourced = true }.create("metals", strategy(), false) }
            .hasMessage("risk.per_strategy.metals.sets.fx: the strategy declares no symbol set 'fx'")
        assertThat(sourced).isFalse()
    }
}
