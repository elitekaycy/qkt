package com.qkt.dsl.compile

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.qkt.dsl.ast.ActionOpts
import com.qkt.dsl.ast.Buy
import com.qkt.dsl.ast.Limit
import com.qkt.dsl.ast.Log
import com.qkt.dsl.ast.LogLevel
import com.qkt.dsl.ast.NumLit
import com.qkt.dsl.ast.OcoEntry
import com.qkt.dsl.ast.Sell
import com.qkt.dsl.ast.SizeQty
import com.qkt.dsl.ast.Stop
import com.qkt.dsl.ast.StreamFieldRef
import com.qkt.marketdata.Candle
import com.qkt.strategy.testStrategyContext
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory

/** What the strategy logger records for an undefined LOG field and for an OCO_ENTRY skipped while warming up. */
class StrategyLogLinesTest {
    private val logger = LoggerFactory.getLogger("com.qkt.dsl.test.strategy-log-lines") as Logger
    private val lines = ListAppender<ILoggingEvent>().also { it.start() }
    private val one = BigDecimal.ONE
    private val ctx =
        EvalContext(
            Candle("BACKTEST:XAUUSD", one, one, one, BigDecimal("100"), BigDecimal.ZERO, 0L, 1L),
            mapOf("g" to HubKey("BACKTEST", "XAUUSD", "1m")),
            emptyMap(),
            testStrategyContext(strategyId = "s1"),
        )
    private val actions = ActionCompiler(ExprCompiler(), strategyLogger = logger)

    init {
        logger.level = Level.DEBUG
        logger.addAppender(lines)
    }

    @AfterEach
    fun detach() {
        logger.detachAppender(lines)
    }

    /** The bar has no bid, so `g.bid` is undefined. */
    private val bid = StreamFieldRef("g", "bid")

    @Test
    fun `an undefined LOG field renders as undefined`() {
        actions.compile(Log(LogLevel.INFO, "bid={b}", mapOf("b" to bid)))(ctx)

        assertThat(lines.list.map { it.formattedMessage }).containsExactly("bid=undefined")
    }

    @Test
    fun `an OCO_ENTRY skipped for an undefined leg price warns once for the leg and once for the pair`() {
        val size = SizeQty(NumLit(one))
        val oco =
            actions.compile(
                OcoEntry(
                    Buy("g", ActionOpts(sizing = size, orderType = Stop(NumLit(BigDecimal("105"))))),
                    Sell("g", ActionOpts(sizing = size, orderType = Limit(bid))),
                ),
            )

        oco(ctx)
        oco(ctx)

        assertThat(lines.list.map { it.formattedMessage })
            .containsExactly(
                "order skipped: entry price undefined during warm-up (strategy=s1, stream=g)",
                "order skipped: OCO_ENTRY leg price undefined during warm-up (strategy=s1)",
            )
    }
}
