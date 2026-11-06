package com.qkt.dsl.compile

import com.qkt.common.Side
import com.qkt.dsl.ast.NumLit
import com.qkt.dsl.ast.Resize
import com.qkt.dsl.ast.SizeQty
import com.qkt.execution.OrderRequest
import com.qkt.instrument.InstrumentMeta
import com.qkt.instrument.InstrumentRegistry
import com.qkt.marketdata.Candle
import com.qkt.positions.LegRole
import com.qkt.positions.Position
import com.qkt.positions.PositionLeg
import com.qkt.positions.StrategyPositionView
import com.qkt.strategy.Signal
import com.qkt.strategy.testStrategyContext
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

/** RESIZE against the venue's lot grid: no primary leg, unknown instruments and deltas below the minimum. */
class ResizeQuantizationTest {
    private val one = BigDecimal.ONE
    private val candle = Candle("BACKTEST:BTCUSDT", one, one, one, one, BigDecimal.ZERO, 0L, 1L)

    private fun num(v: String) = NumLit(BigDecimal(v))

    private fun legs(vararg legs: PositionLeg) =
        object : StrategyPositionView {
            override fun positionFor(symbol: String): Position? = null

            override fun allPositions(): Map<String, Position> = emptyMap()

            override fun legsFor(symbol: String): List<PositionLeg> = legs.toList()
        }

    private fun leg(role: LegRole) =
        PositionLeg(
            "l1",
            "BACKTEST:BTCUSDT",
            Side.BUY,
            BigDecimal("1.00"),
            one,
            0L,
            role,
            parentLegId = "p".takeIf { role == LegRole.STACK },
        )

    private fun registry(
        step: String,
        min: String,
    ) = object : InstrumentRegistry {
        override fun lookup(qktSymbol: String): InstrumentMeta =
            InstrumentMeta(qktSymbol, one, BigDecimal(step), BigDecimal(min), null, BigDecimal("0.01"), 2, 0)
    }

    private val noInstruments =
        object : InstrumentRegistry {
            override fun lookup(qktSymbol: String): InstrumentMeta? = null
        }

    private fun resize(
        target: String,
        positions: StrategyPositionView,
        instruments: InstrumentRegistry,
        stream: String = "btc",
    ): List<Signal> {
        val ctx =
            EvalContext(
                candle,
                mapOf("btc" to HubKey("BACKTEST", "BTCUSDT", "1m")),
                emptyMap(),
                testStrategyContext(positions = positions, instruments = instruments),
            )
        return ActionCompiler(ExprCompiler()).compile(Resize(stream, SizeQty(num(target)), num("0"))).invoke(ctx)
    }

    @Test
    fun `resize is a no-op without a primary leg`() {
        assertThat(resize("2", legs(leg(LegRole.STACK)), noInstruments)).isEmpty()
    }

    @Test
    fun `without instrument metadata the raw delta is traded`() {
        val add = (resize("1.37", legs(leg(LegRole.PRIMARY)), noInstruments).single() as Signal.Submit).request

        assertThat(add.side).isEqualTo(Side.BUY)
        assertThat(add.quantity).isEqualByComparingTo("0.37")
        assertThat((add as OrderRequest.Market).closesLegId).isNull()
    }

    @Test
    fun `a delta that quantizes below the venue minimum is suppressed`() {
        val below =
            resize(
                "1.17",
                legs(leg(LegRole.PRIMARY)),
                registry(step = "0.1", min = "0.15"),
            ).single() as Signal.Suppressed
        val zero =
            resize(
                "1.05",
                legs(leg(LegRole.PRIMARY)),
                registry(step = "0.1", min = "0.01"),
            ).single() as Signal.Suppressed

        assertThat(below.reason).isEqualTo("RESIZE delta 0.17 quantized below venue minimum 0.15")
        assertThat(zero.reason).isEqualTo("RESIZE delta 0.05 quantized below venue minimum 0.01")
    }

    @Test
    fun `resize on an undeclared stream fails when it fires`() {
        assertThatThrownBy { resize("1", legs(leg(LegRole.PRIMARY)), noInstruments, stream = "eth") }
            .hasMessageContaining("Unknown stream alias: eth")
    }
}
