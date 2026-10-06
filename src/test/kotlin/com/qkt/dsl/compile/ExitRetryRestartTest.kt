package com.qkt.dsl.compile

import com.qkt.dsl.parse.Dsl
import com.qkt.dsl.parse.ParseResult
import com.qkt.marketdata.Candle
import com.qkt.persistence.FileStatePersistor
import com.qkt.positions.Position
import com.qkt.positions.StrategyPositionView
import com.qkt.strategy.Signal
import com.qkt.strategy.StrategyContext
import com.qkt.strategy.testStrategyContext
import java.math.BigDecimal
import java.nio.file.Path
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * The re-arm of an exit the venue ended unfilled (#1359) is persisted with the rule edges, so a
 * restart between the failure and the retry still retries, and still sends only what is held.
 */
class ExitRetryRestartTest {
    private val source =
        """
        STRATEGY exit_restart VERSION 1

        SYMBOLS
            btc = BACKTEST:BTCUSDT EVERY 1m

        RULES
            WHEN POSITION.btc != 0 THEN SELL btc SIZING 1
        """.trimIndent()

    private var held = BigDecimal.ONE
    private val positions =
        object : StrategyPositionView {
            override fun positionFor(symbol: String): Position? =
                held.takeIf { it.signum() != 0 }?.let { Position(symbol, it, BigDecimal("100")) }

            override fun allPositions(): Map<String, Position> =
                positionFor("BACKTEST:BTCUSDT")?.let { mapOf(it.symbol to it) }.orEmpty()
        }
    private val ctx: StrategyContext = testStrategyContext(strategyId = "exit_restart", positions = positions)

    private fun started(state: Path): DslCompiledStrategy {
        val parsed = Dsl.parse(source) as ParseResult.Success
        return (AstCompiler().compile(parsed.value) as DslCompiledStrategy).also {
            it.bindStatePersistor("exit_restart", FileStatePersistor(state))
        }
    }

    private fun bar(
        strategy: DslCompiledStrategy,
        index: Int,
        submitted: (Signal) -> Unit = {},
    ): List<Signal> {
        val p = BigDecimal("100")
        val candle = Candle("BACKTEST:BTCUSDT", p, p, p, p, BigDecimal.ZERO, index * 60_000L, (index + 1) * 60_000L)
        val out = mutableListOf<Signal>()
        // The pipeline links each signal to its order id while the rule is still firing.
        strategy.onCandle(candle, ctx) {
            out += it
            submitted(it)
        }
        return out
    }

    @Test
    fun `an exit re-armed before a restart is retried after it with only the held remainder`(
        @TempDir state: Path,
    ) {
        val before = started(state)
        assertThat(bar(before, 0) { before.onOrderSubmitted(it, "x-1") }.single()).isInstanceOf(Signal.Sell::class.java)
        held = BigDecimal("0.4")
        assertThat(before.onExitOrderUnfilled("x-1")?.heldQuantity).isEqualByComparingTo("0.4")

        val after = started(state)
        val retry = bar(after, 1).single() as Signal.Sell

        assertThat(retry.size).isEqualByComparingTo("0.4")
    }

    @Test
    fun `an exit that went out normally is not resent after a restart`(
        @TempDir state: Path,
    ) {
        val before = started(state)
        bar(before, 0) { before.onOrderSubmitted(it, "x-1") }

        assertThat(bar(started(state), 1)).isEmpty()
    }
}
