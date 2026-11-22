package com.qkt.cli.daemon

import com.qkt.common.Side
import com.qkt.dsl.compile.AstCompiler
import com.qkt.dsl.sets.DynamicSetHost
import com.qkt.dsl.sets.SetCatalog
import com.qkt.dsl.sets.SymbolSetSpec
import com.qkt.instrument.EventContracts
import com.qkt.instrument.InstrumentMeta
import com.qkt.instrument.universe.VenueCatalog
import com.qkt.marketdata.TickFeed
import com.qkt.marketdata.source.MarketSource
import com.qkt.marketdata.source.MarketSourceCapability
import com.qkt.persistence.FileStatePersistor
import com.qkt.positions.LegBook
import com.qkt.positions.LegRole
import com.qkt.positions.PositionLeg
import java.math.BigDecimal
import java.nio.file.Files
import java.nio.file.Path
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * A live deploy runs a set whose members move (runtime membership plan, step 8): the venue listing read at start gives
 * the candidates any close of the next day picks, the session subscribes all of them, and the strategy steps the
 * membership at each close as a backtest does.
 */
class StrategyHandleMovingSetTest {
    @TempDir
    lateinit var tmp: Path

    private val hour = 3_600_000L
    private val now = 1_794_002_400_000L

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

    /** Contract [code] of the gateway's listing, open from [open] hours after now until [expiry] hours. */
    private fun contract(
        code: String,
        open: Int,
        expiry: Int,
    ) = EventContracts.meta(
        "GW:$code",
        EventContracts.terms().copy(
            openAtMs = now + open * hour,
            closeAtMs = now + expiry * hour,
            expiryMs = now + expiry * hour,
        ),
    )

    private fun listing(vararg rows: InstrumentMeta) =
        object : SetCatalog {
            override val source = "the venue listing"
            override val survivorsOnly = false

            override fun venue(
                alias: String,
                spec: SymbolSetSpec.Filtered,
            ) = VenueCatalog(rows.toList())
        }

    private val listing = listing(contract("A", 0, 2), contract("B", 1, 3), contract("LATE", 30, 40))

    /** Contracts listed long before and expiring long after any wall-clock start the factory takes. */
    private val standing = listing(contract("A", -100_000, 1_000_000), contract("B", -100_000, 1_000_000))

    private fun strategy(): Path =
        tmp.resolve("rolling.qkt").also {
            Files.writeString(
                it,
                """
                STRATEGY rolling VERSION 1
                SYMBOLS
                    m = GW:* WHERE kind = event TOP 1 BY symbol DESC DYNAMIC EVERY 1h
                RULES
                    FOR EACH s IN m DO
                      WHEN s.close > 0.5 THEN BUY s SIZING 1
                """.trimIndent(),
            )
        }

    private fun held(symbol: String) =
        LegBook(symbol).also {
            it.add(
                PositionLeg(
                    legId = "l-$symbol",
                    parentLegId = null,
                    role = LegRole.PRIMARY,
                    side = Side.BUY,
                    symbol = symbol,
                    quantity = BigDecimal.ONE,
                    entryPrice = BigDecimal.ONE,
                    openedAt = 0L,
                ),
            )
        }

    @Test
    fun `a live deploy subscribes the next day's candidates and runs the set's membership`() {
        val requested = mutableListOf<List<String>>()
        val factory =
            StrategyHandle.RealFactory(
                stateDir = StateDir.resolve(tmp.resolve("state").toString()),
                marketSourceProvider = { symbols ->
                    requested += symbols
                    Silent
                },
                symbolSets = standing,
            )
        val handle = factory.create("rolling", strategy(), false)
        try {
            assertThat(handle.ast.streams.map { it.alias }).containsExactly("m.B")
            assertThat(requested.single()).containsExactly("GW:B")
        } finally {
            handle.close()
        }
        val persistor = FileStatePersistor(tmp.resolve("persist"))
        val (ast, strategy) = liveStrategy(strategy(), listing, now, persistor)
        assertThat(ast.streams.map { it.qktSymbol }).containsExactly("GW:A", "GW:B")
        assertThat(strategy).isInstanceOf(DynamicSetHost::class.java)
        assertThat((strategy as DynamicSetHost).perStreamWarmup.keys.map { it.symbol }).doesNotContain("GW:A")
    }

    @Test
    fun `a held candidate keeps the start, a held symbol no close picks refuses it`() {
        val persistor = FileStatePersistor(tmp.resolve("persist"))
        persistor.saveLegBook("rolling", "GW:A", held("GW:A"))
        assertThat(liveStrategyAst(strategy(), listing, now, persistor).streams.map { it.qktSymbol })
            .containsExactly("GW:A", "GW:B")
        persistor.saveLegBook("rolling", "GW:LATE", held("GW:LATE"))
        assertThatThrownBy { liveStrategyAst(strategy(), listing, now, persistor) }
            .hasMessageContaining("strategy 'rolling' holds GW:LATE")
    }

    @Test
    fun `a strategy without a moving set compiles as before`() {
        val file =
            tmp.resolve("plain.qkt").also {
                Files.writeString(
                    it,
                    "STRATEGY plain VERSION 1\nSYMBOLS\n    x = GW:A EVERY 1m\n" +
                        "RULES\n    WHEN x.close > 1 THEN BUY x SIZING 1",
                )
            }
        val (ast, strategy) = liveStrategy(file, listing, now, FileStatePersistor(tmp.resolve("p2")))
        assertThat(strategy).isNotInstanceOf(DynamicSetHost::class.java)
        assertThat(AstCompiler().compile(ast)::class).isEqualTo(strategy::class)
    }
}
