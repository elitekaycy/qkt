package com.qkt.dsl.compile

import com.qkt.common.Clock
import com.qkt.common.FixedClock
import com.qkt.common.SequentialIdGenerator
import com.qkt.dsl.ast.Latch
import com.qkt.marketdata.Candle
import com.qkt.strategy.testStrategyContext
import java.math.BigDecimal

object LatchCompilerFixture {
    fun compile(latch: Latch): CompiledLatch {
        val exprCompiler = ExprCompiler()
        val sizingCompiler = SizingCompiler(exprCompiler)
        val ids = SequentialIdGenerator(prefix = "latch-test-")
        val compiler = LatchCompiler(exprCompiler, sizingCompiler, ids)
        return compiler.compile(latch, strategyId = "test")
    }

    fun ctx(
        symbol: String,
        close: BigDecimal = BigDecimal("2000.00"),
        clock: Clock = FixedClock(time = 0L),
    ): EvalContext {
        val broker = "BACKTEST"
        val sym = symbol
        val candle =
            Candle(
                symbol = "$broker:$sym",
                open = close,
                high = close,
                low = close,
                close = close,
                volume = BigDecimal.ZERO,
                startTime = 0L,
                endTime = 1L,
            )
        return EvalContext(
            candle = candle,
            streams = mapOf("gold" to HubKey(broker, sym, "1m")),
            lets = emptyMap(),
            strategyContext = testStrategyContext(clock = clock),
        )
    }
}
