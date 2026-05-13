package com.qkt.dsl.compile

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.LoggerContext
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.AppenderBase
import com.qkt.dsl.ast.Log
import com.qkt.dsl.ast.LogLevel
import com.qkt.dsl.ast.NumLit
import com.qkt.dsl.ast.StringLit
import com.qkt.marketdata.Candle
import com.qkt.strategy.testStrategyContext
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import org.slf4j.MDC

class ActionCompilerLogTest {
    private val candle =
        Candle(
            "BACKTEST:BTCUSDT",
            BigDecimal.ONE,
            BigDecimal.ONE,
            BigDecimal.ONE,
            BigDecimal.ONE,
            BigDecimal.ZERO,
            0L,
            1L,
        )
    private val ctx =
        EvalContext(
            candle = candle,
            streams = mapOf("btc" to HubKey("BACKTEST", "BTCUSDT", "1m")),
            lets = emptyMap(),
            strategyContext = testStrategyContext(),
        )
    private val logger =
        LoggerFactory.getLogger("test.action.log") as ch.qos.logback.classic.Logger
    private val captured = mutableListOf<ILoggingEvent>()
    private val appender =
        object : AppenderBase<ILoggingEvent>() {
            override fun append(eventObject: ILoggingEvent) {
                captured.add(eventObject)
            }
        }

    @BeforeEach
    fun setup() {
        appender.context = LoggerFactory.getILoggerFactory() as LoggerContext
        appender.start()
        logger.addAppender(appender)
        logger.level = Level.DEBUG
    }

    @AfterEach
    fun cleanup() {
        logger.detachAppender(appender)
        appender.stop()
        captured.clear()
        MDC.clear()
    }

    @Test
    fun `INFO LOG renders message and fires at INFO`() {
        val action =
            Log(
                LogLevel.INFO,
                "buy at {price}",
                mapOf("price" to NumLit(BigDecimal("50125"))),
            )
        val sigs = ActionCompiler(ExprCompiler(), logger).compile(action).invoke(ctx)
        assertThat(sigs).isEmpty()
        assertThat(captured).hasSize(1)
        assertThat(captured[0].level).isEqualTo(Level.INFO)
        assertThat(captured[0].formattedMessage).isEqualTo("buy at 50125")
    }

    @Test
    fun `WARN LOG fires at WARN`() {
        val action = Log(LogLevel.WARN, "drawdown high", emptyMap())
        ActionCompiler(ExprCompiler(), logger).compile(action).invoke(ctx)
        assertThat(captured).hasSize(1)
        assertThat(captured[0].level).isEqualTo(Level.WARN)
    }

    @Test
    fun `ERROR LOG fires at ERROR`() {
        val action = Log(LogLevel.ERROR, "broker down", emptyMap())
        ActionCompiler(ExprCompiler(), logger).compile(action).invoke(ctx)
        assertThat(captured).hasSize(1)
        assertThat(captured[0].level).isEqualTo(Level.ERROR)
    }

    @Test
    fun `DEBUG LOG fires at DEBUG`() {
        val action = Log(LogLevel.DEBUG, "tick", emptyMap())
        ActionCompiler(ExprCompiler(), logger).compile(action).invoke(ctx)
        assertThat(captured).hasSize(1)
        assertThat(captured[0].level).isEqualTo(Level.DEBUG)
    }

    @Test
    fun `LOG sets log MDC keys for the call`() {
        val action =
            Log(
                LogLevel.INFO,
                "trade",
                mapOf("qty" to NumLit(BigDecimal("0.5")), "side" to StringLit("BUY")),
            )
        ActionCompiler(ExprCompiler(), logger).compile(action).invoke(ctx)
        assertThat(captured).hasSize(1)
        assertThat(captured[0].mdcPropertyMap).containsEntry("log.qty", "0.5")
        assertThat(captured[0].mdcPropertyMap).containsEntry("log.side", "BUY")
    }

    @Test
    fun `LOG clears log MDC keys after the call`() {
        val action = Log(LogLevel.INFO, "x", mapOf("k" to NumLit(BigDecimal.ONE)))
        ActionCompiler(ExprCompiler(), logger).compile(action).invoke(ctx)
        assertThat(MDC.get("log.k")).isNull()
    }

    @Test
    fun `compile rejects unmatched placeholder`() {
        val action = Log(LogLevel.INFO, "buy at {price}", emptyMap())
        assertThatThrownBy { ActionCompiler(ExprCompiler(), logger).compile(action) }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("placeholder")
    }
}
