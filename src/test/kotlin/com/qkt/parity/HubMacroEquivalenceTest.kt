package com.qkt.parity

import com.qkt.backtest.Backtest
import com.qkt.candles.TimeWindow
import com.qkt.common.Money
import com.qkt.common.TimeRange
import com.qkt.dsl.compile.AstCompiler
import com.qkt.dsl.parse.Dsl
import com.qkt.dsl.parse.ParseResult
import com.qkt.marketdata.Tick
import com.qkt.marketdata.hub.HubMarketSource
import com.qkt.marketdata.source.CompositeMarketSource
import com.qkt.marketdata.source.MacroMarketSource
import com.qkt.marketdata.source.MarketRequest
import com.qkt.marketdata.source.MarketSource
import com.qkt.marketdata.source.MarketSourceCapability
import com.qkt.marketdata.source.SymbolPattern
import com.qkt.marketdata.store.macro.MacroSeriesStore
import java.math.BigDecimal
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.io.path.createDirectories
import kotlin.io.path.writeBytes
import kotlin.io.path.writeText
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * The same real-yield observations, served once through the engine's existing `MACRO:` path and
 * once through the hub's `HUB:` path, must drive a strategy to the identical trade list.
 *
 * This is the migration proof. `MACRO:` has been the point-in-time contract for months; if the
 * hub path reproduced it only approximately, every backtest that moved over would silently change
 * its answer. Both sides use real 2024 DFII10 data and the same availability rule (next business
 * day, 13:00 UTC), so any difference would be a fault in one reader, not in the data.
 */
class HubMacroEquivalenceTest {
    private val from = Instant.parse("2024-01-01T00:00:00Z")
    private val to = Instant.parse("2025-01-01T00:00:00Z")

    private fun resource(name: String): ByteArray =
        checkNotNull(javaClass.getResourceAsStream("/hub/$name")) { "missing $name" }.use { it.readAllBytes() }

    /** Daily synthetic gold ticks at 00:00 UTC, one per calendar day, so both runs see one bar per day. */
    private fun goldTicks(): List<Tick> {
        val out = ArrayList<Tick>()
        var day = LocalDate.of(2024, 1, 1)
        var price = BigDecimal("2050")
        while (day.isBefore(LocalDate.of(2025, 1, 1))) {
            // Deterministic, mildly trending, so the rule below has something to react to.
            price = price.add(BigDecimal(if (day.dayOfYear % 7 < 4) "3" else "-2"))
            out.add(
                Tick(
                    "BACKTEST:XAUUSD",
                    Money.of(price.toPlainString()),
                    day.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli() + 12 * 3_600_000L,
                ),
            )
            day = day.plusDays(1)
        }
        return out
    }

    private class PriceTape(
        private val ticks: List<Tick>,
    ) : MarketSource {
        override val name = "tape"
        override val capabilities = setOf(MarketSourceCapability.TICKS)

        override fun supports(symbol: String) = symbol == "BACKTEST:XAUUSD"

        override fun ticks(
            symbol: String,
            range: TimeRange,
        ): Sequence<Tick> =
            ticks.asSequence().filter {
                it.symbol == symbol &&
                    it.timestamp >= range.from.toEpochMilli() &&
                    it.timestamp < range.to.toEpochMilli()
            }
    }

    private fun strategy(
        streamDecl: String,
        field: String,
    ): String =
        """
        STRATEGY realYieldGate VERSION 1

        SYMBOLS
            gold    = BACKTEST:XAUUSD EVERY 1d
            real10y = $streamDecl EVERY 1d

        RULES
            WHEN real10y.$field < ema(real10y.$field, 5) AND gold.close > ema(gold.close, 3) AND POSITION.gold = 0
            THEN BUY gold SIZING 1

            WHEN POSITION.gold > 0 AND real10y.$field > ema(real10y.$field, 5)
            THEN CLOSE gold
        """.trimIndent()

    private fun run(
        source: MarketSource,
        strategySource: String,
        symbols: List<String>,
    ): List<String> {
        val strategy = AstCompiler().compile((Dsl.parse(strategySource) as ParseResult.Success).value)
        val result =
            Backtest
                .fromSource(
                    strategies = listOf("s" to strategy),
                    source = source,
                    request = MarketRequest(symbols = symbols, from = from, to = to),
                    candleWindow = TimeWindow.parse("1d"),
                    startingBalance = BigDecimal("100000"),
                ).run()
        return result.trades.map {
            val t = it.trade
            "${t.timestamp} ${t.side} ${t.quantity.stripTrailingZeros().toPlainString()} @ " +
                t.price.stripTrailingZeros().toPlainString()
        }
    }

    @Test
    fun `MACRO and HUB serve the same real-yield history to the same trades`(
        @TempDir tmp: Path,
    ) {
        // The engine's existing macro store: <root>/macro/DFII10/2024.csv, rows date,value.
        val macroRoot = tmp.resolve("macro")
        macroRoot.resolve("macro").resolve("DFII10").createDirectories()
        macroRoot
            .resolve("macro")
            .resolve("DFII10")
            .resolve("2024.csv")
            .writeBytes(resource("dfii10_2024.csv"))

        // The hub store: the same observations as journalled by the hub with a derived next-business-day lag.
        val hubRoot = tmp.resolve("hub")
        hubRoot.resolve("journal").resolve("rates.us.dfii10").createDirectories()
        hubRoot
            .resolve(
                "journal",
            ).resolve("rates.us.dfii10")
            .resolve("2024-01-01.ndjson")
            .writeBytes(resource("rates.us.dfii10.2024.ndjson"))
        hubRoot.resolve("manifest.json").writeBytes(resource("manifest.json"))
        hubRoot.resolve("heartbeat").writeText("1")

        val tape = PriceTape(goldTicks())
        val macroSource =
            CompositeMarketSource(
                routes = listOf(SymbolPattern.prefix("MACRO:") to MacroMarketSource(MacroSeriesStore(macroRoot))),
                fallback = tape,
            )
        val hubSource =
            CompositeMarketSource(
                routes = listOf(SymbolPattern.prefix("HUB:") to HubMarketSource(hubRoot)),
                fallback = tape,
            )

        val viaMacro = run(macroSource, strategy("MACRO:DFII10", "value"), listOf("BACKTEST:XAUUSD", "MACRO:DFII10"))
        val viaHub =
            run(
                hubSource,
                strategy("HUB:rates.us.dfii10", "value"),
                listOf("BACKTEST:XAUUSD", "HUB:rates.us.dfii10/value"),
            )

        assertThat(viaMacro).isNotEmpty
        assertThat(viaHub).isEqualTo(viaMacro)
    }

    @Test
    fun `the hub path sees a value only after its next-business-day release`(
        @TempDir tmp: Path,
    ) {
        val hubRoot = tmp.resolve("hub")
        hubRoot.resolve("journal").resolve("rates.us.dfii10").createDirectories()
        hubRoot
            .resolve(
                "journal",
            ).resolve("rates.us.dfii10")
            .resolve("2024-01-01.ndjson")
            .writeBytes(resource("rates.us.dfii10.2024.ndjson"))
        val source = HubMarketSource(hubRoot)
        val monday = Instant.parse("2024-06-03T12:00:00Z").toEpochMilli()
        val ticks = source.ticks("HUB:rates.us.dfii10/value", TimeRange(from, Instant.ofEpochMilli(monday))).toList()
        // Friday 2024-05-31's value publishes Monday 13:00 UTC, so at 12:00 the newest visible
        // observation is Thursday's.
        val newest = ticks.last()
        assertThat(newest.timestamp).isEqualTo(Instant.parse("2024-05-31T13:00:00Z").toEpochMilli())
        assertThat(newest.price).isEqualByComparingTo(BigDecimal("2.19"))
        assertThat(Files.exists(hubRoot)).isTrue()
    }
}
