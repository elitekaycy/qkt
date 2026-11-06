package com.qkt.research

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.qkt.dsl.compile.AstCompiler
import com.qkt.dsl.parse.Dsl
import com.qkt.dsl.parse.ParseResult
import com.qkt.instrument.InstrumentMeta
import com.qkt.instrument.InstrumentRegistry
import com.qkt.marketdata.Tick
import com.qkt.marketdata.source.SequenceTickFeed
import com.qkt.strategy.Signal
import com.qkt.strategy.Strategy
import com.qkt.strategy.StrategyContext
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory

class ReplaySymbolChecksTest {
    private fun compile(src: String): Strategy =
        when (val r = Dsl.parse(src)) {
            is ParseResult.Success -> AstCompiler().compile(r.value)
            is ParseResult.Failure -> error("parse failed: ${r.errors}")
        }

    /** A real (non-noop) registry that knows no symbol. */
    private val empty =
        object : InstrumentRegistry {
            override fun lookup(qktSymbol: String): InstrumentMeta? = null
        }

    private val idle =
        object : Strategy {
            override fun onTick(
                tick: Tick,
                ctx: StrategyContext,
                emit: (Signal) -> Unit,
            ) = Unit
        }

    @Test
    fun `a strategy reading quote fields is warned about once at setup`() {
        val strategy =
            compile(
                """
                STRATEGY spready VERSION 1
                SYMBOLS
                    gold = BACKTEST:XAUUSD EVERY 1m
                RULES
                    WHEN gold.spread > 0
                    THEN BUY gold SIZING 1
                """.trimIndent(),
            )
        val logger = LoggerFactory.getLogger(ReplayEngine::class.java) as Logger
        val logged = ListAppender<ILoggingEvent>().also { it.start() }
        val level = logger.level
        logger.level = Level.WARN
        logger.addAppender(logged)
        try {
            ReplayEngine(strategies = listOf("spready" to strategy), feed = SequenceTickFeed(emptySequence()))
        } finally {
            logger.detachAppender(logged)
            logger.level = level
        }

        assertThat(logged.list.map { it.formattedMessage })
            .anyMatch { it.startsWith("strategy reads quote fields (bid/ask/spread) on streams ") }
    }

    @Test
    fun `a real registry that cannot size a fiat-quoted symbol fails the replay up front`() {
        assertThatThrownBy {
            ReplayEngine(
                strategies = listOf("idle" to idle),
                feed = SequenceTickFeed(emptySequence()),
                symbols = listOf("BINANCE:BTCUSDT", "EXNESS:XAUUSD"),
                instruments = empty,
            )
        }.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessage(
                "InstrumentMeta unresolvable for EXNESS:XAUUSD — refusing to backtest " +
                    "(PnL would silently book contractSize=1)",
            )
    }
}
