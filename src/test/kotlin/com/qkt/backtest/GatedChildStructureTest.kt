package com.qkt.backtest

import com.qkt.common.FixedClock
import com.qkt.dsl.compile.AstCompiler
import com.qkt.dsl.compile.DslCompiledStrategy
import com.qkt.dsl.parse.Dsl
import com.qkt.dsl.parse.ParseResult
import com.qkt.marketdata.Candle
import com.qkt.positions.Position
import com.qkt.positions.StrategyPositionView
import com.qkt.strategy.Signal
import com.qkt.strategy.StructureLegPosition
import com.qkt.strategy.StructurePosition
import com.qkt.strategy.StructureState
import com.qkt.strategy.StructureView
import com.qkt.strategy.testStrategyContext
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** A portfolio gate closing on a child that holds option structures ends them, as it flattens its streams. */
class GatedChildStructureTest {
    private val inner =
        AstCompiler().compile(
            (
                Dsl.parse(
                    "STRATEGY child VERSION 1\nSYMBOLS\n    s = EXNESS:XAUUSD EVERY 1h\n" +
                        "RULES\n    WHEN s.close > 0 THEN FLATTEN\n",
                ) as ParseResult.Success
            ).value,
        ) as DslCompiledStrategy
    private val p81 = "DERIBIT:BTC_USDC_9OCT26_81000_P"
    private val p78 = "DERIBIT:BTC_USDC_9OCT26_78000_P"
    private val oct9 = 1_791_532_800_000L

    private fun leg(
        symbol: String,
        quantity: String,
        entry: String?,
    ) = StructureLegPosition(
        symbol,
        BigDecimal.ONE,
        oct9,
        if (entry == null) BigDecimal.ZERO else BigDecimal(quantity),
        entry?.let(::BigDecimal),
        if (entry == null) BigDecimal.ZERO else BigDecimal(quantity),
        BigDecimal.ZERO,
    )

    private fun deactivate(
        structures: List<StructurePosition>,
        flatten: List<String> = emptyList(),
        held: Map<String, String> = emptyMap(),
    ): List<Signal> {
        var active = true
        val gated = GatedChild("book:child", inner, hold = false, gateFor = { active }, flattenSymbols = flatten)
        val view =
            object : StructureView {
                override fun live(alias: String) = structures.firstOrNull { it.alias == alias }

                override fun all() = structures

                override fun mark(symbol: String): BigDecimal? = null
            }
        val positions =
            object : StrategyPositionView {
                override fun positionFor(symbol: String) =
                    held[symbol]?.let { Position(symbol, BigDecimal(it), BigDecimal.ONE) }

                override fun allPositions() = held.keys.associateWith { requireNotNull(positionFor(it)) }

                override fun legsFor(symbol: String): List<com.qkt.positions.PositionLeg> = emptyList()
            }
        val ctx = testStrategyContext(clock = FixedClock(1_000L), positions = positions).copy(structures = view)
        val emitted = mutableListOf<Signal>()
        active = false
        val candle = Candle("s", BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ZERO, 0, 1)
        gated.onCandle(candle, ctx) { emitted += it }
        return emitted
    }

    private val open =
        StructurePosition(
            "ps-1",
            "ps",
            StructureState.OPEN,
            BigDecimal("0.1"),
            listOf(leg(p81, "-0.1", "646"), leg(p78, "0.1", "219")),
            working = false,
        )

    @Test
    fun `an open structure closes as one forced group and a pending one has its working legs cancelled`() {
        val pending =
            StructurePosition(
                "qs-1",
                "qs",
                StructureState.PENDING,
                BigDecimal("0.1"),
                listOf(leg(p81, "-0.1", "600"), leg(p78, "0.1", null)),
                working = true,
            )

        val emitted = deactivate(listOf(open, pending))

        val close = emitted.filterIsInstance<Signal.SubmitGroup>().single()
        assertThat(close.closes).isEqualTo("ps-1")
        assertThat(close.force).isTrue()
        assertThat(close.requests.map { it.symbol }).containsExactly(p81, p78)
        assertThat(emitted.filterIsInstance<Signal.CancelPendingForSymbol>())
            .containsExactly(Signal.CancelPendingForSymbol(p78, force = true))
    }

    @Test
    fun `a flattened stream a structure holds is closed by the structure alone, with only the rest flattened`() {
        val emitted = deactivate(listOf(open), flatten = listOf(p81), held = mapOf(p81 to "-0.3"))

        assertThat(
            emitted
                .filterIsInstance<Signal.SubmitGroup>()
                .single()
                .requests
                .map { it.symbol },
        ).contains(p81)
        assertThat(
            emitted.filterIsInstance<Signal.Buy>(),
        ).containsExactly(Signal.Buy(p81, BigDecimal("0.2"), force = true))
    }
}
