package com.qkt.dsl.compile

import com.qkt.dsl.ast.BinOp
import com.qkt.dsl.ast.BinaryOp
import com.qkt.dsl.ast.NumLit
import com.qkt.dsl.ast.StreamFieldRef
import com.qkt.instrument.InstrumentMeta
import com.qkt.instrument.InstrumentRegistry
import com.qkt.marketdata.Candle
import com.qkt.strategy.testStrategyContext
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class ExprCompilerInstrumentMetaTest {
    private val xauKey = HubKey("EXNESS", "XAUUSD", "1m")

    private val gold =
        InstrumentMeta(
            qktSymbol = "EXNESS:XAUUSD",
            contractSize = BigDecimal("100"),
            volumeStep = BigDecimal("0.01"),
            volumeMin = BigDecimal("0.01"),
            volumeMax = null,
            pointSize = BigDecimal("0.01"),
            digits = 2,
            tradeStopsLevelPoints = 0,
        )

    private fun registry(meta: InstrumentMeta?): InstrumentRegistry =
        object : InstrumentRegistry {
            override fun lookup(qktSymbol: String): InstrumentMeta? =
                if (meta != null && meta.qktSymbol == qktSymbol) meta else null
        }

    private fun ctx(reg: InstrumentRegistry): EvalContext {
        val candle =
            Candle(
                symbol = "EXNESS:XAUUSD",
                open = BigDecimal("4500"),
                high = BigDecimal("4500"),
                low = BigDecimal("4500"),
                close = BigDecimal("4500"),
                volume = BigDecimal("1"),
                startTime = 0L,
                endTime = 60_000L,
            )
        return EvalContext(
            candle = candle,
            streams = mapOf("gold" to xauKey),
            lets = emptyMap(),
            strategyContext = testStrategyContext(instruments = reg),
        )
    }

    @Test
    fun `tick_size resolves to InstrumentMeta pointSize`() {
        val v = ExprCompiler().compile(StreamFieldRef("gold", "tick_size")).evaluate(ctx(registry(gold)))
        assertThat(v).isEqualTo(Value.Num(BigDecimal("0.01")))
    }

    @Test
    fun `contract_size resolves to InstrumentMeta contractSize`() {
        val v = ExprCompiler().compile(StreamFieldRef("gold", "contract_size")).evaluate(ctx(registry(gold)))
        assertThat(v).isEqualTo(Value.Num(BigDecimal("100")))
    }

    @Test
    fun `volume_step resolves to InstrumentMeta volumeStep`() {
        val v = ExprCompiler().compile(StreamFieldRef("gold", "volume_step")).evaluate(ctx(registry(gold)))
        assertThat(v).isEqualTo(Value.Num(BigDecimal("0.01")))
    }

    @Test
    fun `volume_min resolves to InstrumentMeta volumeMin`() {
        val v = ExprCompiler().compile(StreamFieldRef("gold", "volume_min")).evaluate(ctx(registry(gold)))
        assertThat(v).isEqualTo(Value.Num(BigDecimal("0.01")))
    }

    @Test
    fun `unknown meta field fails at compile with the unified error`() {
        assertThatThrownBy {
            ExprCompiler().compile(StreamFieldRef("gold", "tick_sze"))
        }.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("Unknown stream field for gold: tick_sze")
    }

    @Test
    fun `meta accessor composes with arithmetic`() {
        val mul =
            BinaryOp(
                op = BinOp.MUL,
                lhs = StreamFieldRef("gold", "tick_size"),
                rhs = NumLit(BigDecimal("2")),
            )
        val v = ExprCompiler().compile(mul).evaluate(ctx(registry(gold)))
        assertThat(v).isEqualTo(Value.Num(BigDecimal("0.02")))
    }
}
