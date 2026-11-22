package com.qkt.cli

import com.qkt.dsl.ast.StreamDecl
import com.qkt.instrument.NoopInstrumentRegistry
import com.qkt.marketdata.store.BinaryBarStore
import com.qkt.marketdata.store.macro.MacroPoint
import com.qkt.marketdata.store.macro.MacroSeriesStore
import com.qkt.marketdata.store.macro.PolicyRateSeries
import java.math.BigDecimal
import java.nio.file.Path
import java.time.Instant
import java.time.LocalDate
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class BacktestSetupMacroTest {
    @TempDir
    lateinit var tmp: Path

    private val from = LocalDate.of(2024, 1, 2)
    private val to = LocalDate.of(2024, 3, 1)

    @Test
    fun `a series the store does not bracket is fetched from FRED, a policy rate from its own source`() {
        val store = MacroSeriesStore(tmp)
        val fred = mutableListOf<String>()
        val policy = mutableListOf<PolicyRateSeries>()

        BacktestSetup.provisionMacro(
            listOf("DFII10", "RBA_CASH_RATE"),
            store,
            from,
            to,
            fetchFred = { id, _, _ -> fred += id },
            fetchPolicy = { series, _, _ -> policy += series },
        )

        assertThat(fred).containsExactly("DFII10")
        assertThat(policy).containsExactly(PolicyRateSeries.RBA_CASH_RATE)
    }

    @Test
    fun `a series the store already brackets is not fetched again`() {
        val store = MacroSeriesStore(tmp)
        store.write("DFII10", listOf(MacroPoint(from, BigDecimal("1.8")), MacroPoint(to, BigDecimal("1.9"))))
        var fetched = 0

        BacktestSetup.provisionMacro(
            listOf("DFII10"),
            store,
            from,
            to,
            fetchFred = { _, _, _ -> fetched++ },
            fetchPolicy = { _, _, _ -> fetched++ },
        )

        assertThat(fetched).isZero()
    }

    @Test
    fun `the provisioner leaves stored macro series alone and fetches nothing`() {
        val dataRoot = tmp.resolve("data")
        val windowFrom = Instant.parse("2024-03-01T00:00:00Z")
        val windowTo = Instant.parse("2024-03-05T00:00:00Z")
        MacroSeriesStore(dataRoot).write(
            "DFII10",
            listOf(
                MacroPoint(LocalDate.of(2023, 11, 1), BigDecimal("1.8")),
                MacroPoint(LocalDate.of(2024, 3, 6), BigDecimal("1.9")),
            ),
        )
        val args = Args(arrayOf("backtest"))
        val stores = BacktestSetup.stores(args, dataRoot.toString(), fetcherOverride = null)
        val stream = StreamDecl(alias = "real", broker = "MACRO", symbol = "DFII10", timeframe = "1d")

        BacktestSetup
            .provisioner(
                args = args,
                dataRoot = dataRoot.toString(),
                stores = stores,
                window = windowFrom to windowTo,
                replay =
                    listOf(stream.qktSymbol) to
                        BacktestBarReplay.BarReplayConfig(
                            forceBars = true,
                            tickFills = false,
                            binaryBarStore = BinaryBarStore(dataRoot),
                            finestDeclared = emptyMap(),
                            barWindows = emptyMap(),
                        ),
                streams = listOf(stream),
                instruments = NoopInstrumentRegistry,
                calendarFor = {
                    com.qkt.common.TradingCalendar
                        .fxDefault()
                },
            )()

        assertThat(MacroSeriesStore(dataRoot).read("DFII10", LocalDate.of(2023, 1, 1), LocalDate.of(2024, 12, 31)))
            .hasSize(2)
    }
}
