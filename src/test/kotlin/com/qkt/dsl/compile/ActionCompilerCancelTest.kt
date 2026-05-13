package com.qkt.dsl.compile

import com.qkt.dsl.ast.Cancel
import com.qkt.dsl.ast.CancelAll
import com.qkt.marketdata.Candle
import com.qkt.strategy.Signal
import com.qkt.strategy.testStrategyContext
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class ActionCompilerCancelTest {
    private fun makeCtx(streams: Map<String, HubKey>): EvalContext {
        val candle =
            Candle(
                symbol = streams.values.firstOrNull()?.symbol ?: "BACKTEST:BTCUSDT",
                open = BigDecimal.ONE,
                high = BigDecimal.ONE,
                low = BigDecimal.ONE,
                close = BigDecimal.ONE,
                volume = BigDecimal.ZERO,
                startTime = 0L,
                endTime = 1L,
            )
        return EvalContext(
            candle = candle,
            streams = streams,
            lets = emptyMap(),
            strategyContext = testStrategyContext(),
        )
    }

    @Test
    fun `CANCEL emits CancelPendingForSymbol for the stream`() {
        val ctx = makeCtx(mapOf("btc" to HubKey("BACKTEST", "BTCUSDT", "1m")))
        val signals = ActionCompiler(ExprCompiler()).compile(Cancel("btc")).invoke(ctx)
        assertThat(signals).containsExactly(Signal.CancelPendingForSymbol("BACKTEST:BTCUSDT"))
    }

    @Test
    fun `CANCEL_ALL emits one CancelPendingForSymbol per known stream`() {
        val ctx =
            makeCtx(
                mapOf(
                    "btc" to HubKey("BACKTEST", "BTCUSDT", "1m"),
                    "eth" to HubKey("BACKTEST", "ETHUSDT", "1m"),
                ),
            )
        val signals = ActionCompiler(ExprCompiler()).compile(CancelAll).invoke(ctx)
        assertThat(signals).containsExactlyInAnyOrder(
            Signal.CancelPendingForSymbol("BACKTEST:BTCUSDT"),
            Signal.CancelPendingForSymbol("BACKTEST:ETHUSDT"),
        )
    }
}
