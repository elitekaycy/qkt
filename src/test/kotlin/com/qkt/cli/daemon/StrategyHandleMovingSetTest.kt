package com.qkt.cli.daemon

import com.qkt.dsl.compile.AstCompiler
import com.qkt.dsl.sets.DynamicSetHost
import com.qkt.dsl.sets.SetCatalog
import com.qkt.instrument.InstrumentMeta
import com.qkt.marketdata.TickFeed
import com.qkt.marketdata.source.MarketSource
import com.qkt.marketdata.source.MarketSourceCapability
import com.qkt.persistence.FileStatePersistor
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

    private val now = MovingSetListing.NOW

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

    private fun contract(
        code: String,
        open: Int,
        expiry: Int,
    ): InstrumentMeta = MovingSetListing.contract(code, open, expiry)

    private fun listing(vararg rows: InstrumentMeta): SetCatalog = MovingSetListing.listing(*rows)

    private val listing: SetCatalog = listing(contract("A", 0, 2), contract("B", 1, 3), contract("LATE", 30, 40))

    /** Contracts listed long before and expiring long after any wall-clock start the factory takes. */
    private val standing: SetCatalog = listing(contract("A", -100_000, 1_000_000), contract("B", -100_000, 1_000_000))

    private fun strategy(): Path = MovingSetListing.strategy(tmp)

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

    @Test
    fun `a moving set whose member rule stacks entries is refused live, before anything trades`() {
        val file =
            tmp.resolve("stacked.qkt").also {
                val rule = "BUY s SIZING 1"
                val text = strategy().toFile().readText()
                Files.writeString(it, text.replace(rule, "$rule\n        STACK_AT MFE >= 50 WITHIN 30m SIZING 1"))
            }
        assertThatThrownBy { liveStrategy(file, listing, now, FileStatePersistor(tmp.resolve("p4"))) }
            .hasMessageContaining("a rule over a symbol set whose members move uses STACK_AT")
    }

    @Test
    fun `a slot universe is refused live, before anything trades`() {
        val file =
            tmp.resolve("slots.qkt").also {
                Files.writeString(
                    it,
                    "STRATEGY slots VERSION 1\nSYMBOLS\n" +
                        "    m = GW:*@front.p70 WHERE kind = event TOP 1 BY symbol EVERY 1h\n" +
                        "RULES\n    FOR EACH s IN m DO\n      WHEN s.close > 0.5 THEN BUY s SIZING 1",
                )
            }
        assertThatThrownBy { liveStrategy(file, listing, now, FileStatePersistor(tmp.resolve("p3"))) }
            .hasMessage("symbol set 'm' is a slot universe, which live sessions do not run yet; backtest it")
    }
}
