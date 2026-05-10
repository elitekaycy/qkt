package com.qkt.dsl.compile

import com.qkt.dsl.ast.ActionOpts
import com.qkt.dsl.ast.Buy
import com.qkt.dsl.ast.Log
import com.qkt.dsl.ast.NumLit
import com.qkt.dsl.ast.SizeQty
import com.qkt.marketdata.Candle
import com.qkt.strategy.testStrategyContext
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory

class ActionCompilerExtensionsTest {
    private val candle =
        Candle("BTCUSDT", BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ZERO, 0L, 1L)
    private val ctx =
        EvalContext(
            candle = candle,
            streams = mapOf("btc" to HubKey("BACKTEST", "BTCUSDT", "1m")),
            lets = emptyMap(),
            strategyContext = testStrategyContext(),
        )
    private val logger = LoggerFactory.getLogger("test")

    @Test
    fun `Log emits no signals`() {
        val sigs =
            ActionCompiler(ExprCompiler(), logger)
                .compile(Log(com.qkt.dsl.ast.LogLevel.INFO, "entered long", emptyMap()))
                .invoke(ctx)
        assertThat(sigs).isEmpty()
    }

    @Test
    fun `Buy still wraps single signal in list`() {
        val sigs =
            ActionCompiler(ExprCompiler(), logger)
                .compile(Buy("btc", ActionOpts(sizing = SizeQty(NumLit(BigDecimal.ONE)))))
                .invoke(ctx)
        assertThat(sigs).hasSize(1)
    }

    @Test
    fun `CLOSE on long emits Sell at full quantity`() {
        val pos =
            object : com.qkt.positions.StrategyPositionView {
                override fun positionFor(symbol: String) =
                    if (symbol == "BTCUSDT") {
                        com.qkt.positions.Position("BTCUSDT", BigDecimal("2.5"), BigDecimal("100"))
                    } else {
                        null
                    }

                override fun allPositions() = mapOf("BTCUSDT" to positionFor("BTCUSDT")!!)
            }
        val ec =
            EvalContext(
                candle = candle,
                streams = mapOf("btc" to HubKey("BACKTEST", "BTCUSDT", "1m")),
                lets = emptyMap(),
                strategyContext = testStrategyContext(positions = pos),
            )
        val sigs =
            ActionCompiler(ExprCompiler(), logger)
                .compile(
                    com.qkt.dsl.ast
                        .Close("btc"),
                ).invoke(ec)
        assertThat(sigs).containsExactly(
            com.qkt.strategy.Signal
                .CancelPendingForSymbol("BTCUSDT"),
            com.qkt.strategy.Signal
                .Sell("BTCUSDT", BigDecimal("2.5")),
        )
    }

    @Test
    fun `CLOSE on short emits Buy at absolute quantity`() {
        val pos =
            object : com.qkt.positions.StrategyPositionView {
                override fun positionFor(symbol: String) =
                    if (symbol == "BTCUSDT") {
                        com.qkt.positions.Position("BTCUSDT", BigDecimal("-1.5"), BigDecimal("100"))
                    } else {
                        null
                    }

                override fun allPositions() = mapOf("BTCUSDT" to positionFor("BTCUSDT")!!)
            }
        val ec =
            EvalContext(
                candle = candle,
                streams = mapOf("btc" to HubKey("BACKTEST", "BTCUSDT", "1m")),
                lets = emptyMap(),
                strategyContext = testStrategyContext(positions = pos),
            )
        val sigs =
            ActionCompiler(ExprCompiler(), logger)
                .compile(
                    com.qkt.dsl.ast
                        .Close("btc"),
                ).invoke(ec)
        assertThat(sigs).containsExactly(
            com.qkt.strategy.Signal
                .CancelPendingForSymbol("BTCUSDT"),
            com.qkt.strategy.Signal
                .Buy("BTCUSDT", BigDecimal("1.5")),
        )
    }

    @Test
    fun `CLOSE on flat emits only stack cancellation`() {
        val sigs =
            ActionCompiler(ExprCompiler(), logger)
                .compile(
                    com.qkt.dsl.ast
                        .Close("btc"),
                ).invoke(ctx)
        assertThat(sigs).containsExactly(
            com.qkt.strategy.Signal
                .CancelPendingForSymbol("BTCUSDT"),
        )
    }

    @Test
    fun `CANCEL emits CancelPendingForSymbol for the stream`() {
        val sigs =
            ActionCompiler(ExprCompiler(), logger)
                .compile(
                    com.qkt.dsl.ast
                        .Cancel("btc"),
                ).invoke(ctx)
        assertThat(sigs).containsExactly(
            com.qkt.strategy.Signal
                .CancelPendingForSymbol("BTCUSDT"),
        )
    }

    @Test
    fun `CANCEL_ALL emits one CancelPendingForSymbol per known stream`() {
        val multiCtx =
            EvalContext(
                candle = candle,
                streams =
                    mapOf(
                        "btc" to HubKey("BACKTEST", "BTCUSDT", "1m"),
                        "eth" to HubKey("BACKTEST", "ETHUSDT", "1m"),
                    ),
                lets = emptyMap(),
                strategyContext = testStrategyContext(),
            )
        val sigs =
            ActionCompiler(ExprCompiler(), logger)
                .compile(com.qkt.dsl.ast.CancelAll)
                .invoke(multiCtx)
        assertThat(sigs).containsExactlyInAnyOrder(
            com.qkt.strategy.Signal
                .CancelPendingForSymbol("BTCUSDT"),
            com.qkt.strategy.Signal
                .CancelPendingForSymbol("ETHUSDT"),
        )
    }

    @Test
    fun `CLOSE_ALL emits one signal per non-zero position`() {
        val pos =
            object : com.qkt.positions.StrategyPositionView {
                override fun positionFor(symbol: String) = allPositions()[symbol]

                override fun allPositions() =
                    mapOf(
                        "BTCUSDT" to com.qkt.positions.Position("BTCUSDT", BigDecimal("2"), BigDecimal("100")),
                        "ETHUSDT" to com.qkt.positions.Position("ETHUSDT", BigDecimal("-3"), BigDecimal("50")),
                        "ZERO" to com.qkt.positions.Position("ZERO", BigDecimal.ZERO, BigDecimal("10")),
                    )
            }
        val ec =
            EvalContext(
                candle = candle,
                streams = mapOf("btc" to HubKey("BACKTEST", "BTCUSDT", "1m")),
                lets = emptyMap(),
                strategyContext = testStrategyContext(positions = pos),
            )
        val sigs =
            ActionCompiler(ExprCompiler(), logger)
                .compile(com.qkt.dsl.ast.CloseAll)
                .invoke(ec)
        assertThat(sigs).containsExactlyInAnyOrder(
            com.qkt.strategy.Signal
                .CancelPendingForSymbol("BTCUSDT"),
            com.qkt.strategy.Signal
                .Sell("BTCUSDT", BigDecimal("2")),
            com.qkt.strategy.Signal
                .Buy("ETHUSDT", BigDecimal("3")),
        )
    }
}
