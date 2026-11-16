package com.qkt.cli.daemon

import com.qkt.dsl.sets.SetCatalog
import com.qkt.dsl.sets.SymbolSetSpec
import com.qkt.instrument.InstrumentMeta
import com.qkt.instrument.universe.VenueCatalog
import com.qkt.marketdata.TickFeed
import com.qkt.marketdata.source.MarketSource
import com.qkt.marketdata.source.MarketSourceCapability
import java.math.BigDecimal
import java.nio.file.Files
import java.nio.file.Path
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/** A deployed strategy's filtered symbol set becomes its members from the venue's listing at session start. */
class StrategyHandleSymbolSetTest {
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

    @Test
    fun `a live deploy resolves the set from the listing, and the session trades its members`() {
        val requested = mutableListOf<List<String>>()
        val factory =
            StrategyHandle.RealFactory(
                stateDir = StateDir.resolve(tmp.resolve("state").toString()),
                marketSourceProvider = { symbols ->
                    requested += symbols
                    Silent
                },
                symbolSets = listing,
            )
        val handle = factory.create("metals", strategy(), false)
        try {
            assertThat(handle.ast.streams.map { it.qktSymbol }).containsExactly("GW:XAGUSD", "GW:XAUUSD")
            assertThat(handle.ast.streams.map { it.alias }).containsExactly("m.XAGUSD", "m.XAUUSD")
            assertThat(requested.single()).containsExactly("GW:XAGUSD", "GW:XAUUSD")
        } finally {
            handle.close()
        }
    }

    @Test
    fun `without a catalog a filtered set is still refused before anything trades`() {
        val factory =
            StrategyHandle.RealFactory(
                stateDir = StateDir.resolve(tmp.resolve("state").toString()),
                marketSourceProvider = { Silent },
            )
        assertThatThrownBy { factory.create("metals", strategy(), false) }
            .hasMessageContaining("symbol set 'm' is a filtered universe")
    }
}
