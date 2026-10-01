package com.qkt.backtest

import com.qkt.common.FixedClock
import com.qkt.dsl.compile.AstCompiler
import com.qkt.dsl.compile.DslCompiledStrategy
import com.qkt.dsl.parse.Dsl
import com.qkt.dsl.parse.ParseResult
import com.qkt.marketdata.Candle
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

    private fun deactivate(vararg structures: StructurePosition): List<Signal> {
        var active = true
        val gated = GatedChild("book:child", inner, hold = false, gateFor = { active }, flattenSymbols = emptyList())
        val view =
            object : StructureView {
                override fun live(alias: String) = structures.firstOrNull { it.alias == alias }

                override fun all() = structures.toList()

                override fun mark(symbol: String): BigDecimal? = null
            }
        val ctx = testStrategyContext(clock = FixedClock(1_000L)).copy(structures = view)
        val emitted = mutableListOf<Signal>()
        active = false
        gated.onCandle(
            Candle("s", BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ZERO, 0, 1),
            ctx,
        ) {
            emitted += it
        }
        return emitted
    }

    @Test
    fun `an open structure closes as one forced group and a pending one has its working legs cancelled`() {
        val open =
            StructurePosition(
                "ps-1",
                "ps",
                StructureState.OPEN,
                BigDecimal("0.1"),
                listOf(leg(p81, "-0.1", "646"), leg(p78, "0.1", "219")),
            )
        val pending =
            StructurePosition(
                "qs-1",
                "qs",
                StructureState.PENDING,
                BigDecimal("0.1"),
                listOf(leg(p81, "-0.1", "600"), leg(p78, "0.1", null)),
            )

        val emitted = deactivate(open, pending)

        val close = emitted.filterIsInstance<Signal.SubmitGroup>().single()
        assertThat(close.closes).isEqualTo("ps-1")
        assertThat(close.force).isTrue()
        assertThat(close.requests.map { it.symbol }).containsExactly(p81, p78)
        assertThat(
            emitted.filterIsInstance<Signal.CancelPendingForSymbol>(),
        ).containsExactly(Signal.CancelPendingForSymbol(p78, force = true))
    }
}
