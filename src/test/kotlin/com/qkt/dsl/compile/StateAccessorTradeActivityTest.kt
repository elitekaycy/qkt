package com.qkt.dsl.compile

import com.qkt.dsl.ast.StateAccessor
import com.qkt.dsl.ast.StateSource
import com.qkt.marketdata.Candle
import com.qkt.strategy.OpenOrderView
import com.qkt.strategy.testStrategyContext
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** Trade-history and open-order accessors compiled by [StateAccessorCompiler]. */
class StateAccessorTradeActivityTest {
    private val candle =
        Candle("X", BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ZERO, 0L, 1L)

    @Test
    fun `POSITION_TRADES_TODAY counts only fills today on the stream's symbol`() {
        val history = com.qkt.pnl.TradeHistory()
        val midnight = 1_705_276_800_000L
        history.recordTrade("test", midnight + 1_000, BigDecimal("10"), "BACKTEST:BTCUSDT")
        history.recordTrade("test", midnight + 7_200_000, BigDecimal("-5"), "BACKTEST:BTCUSDT")
        history.recordTrade("test", midnight + 7_200_000, BigDecimal("10"), "BACKTEST:ETHUSDT")
        val view = com.qkt.pnl.TradeHistoryViewImpl(history, "test")
        val noon = midnight + 12 * 3600 * 1000
        val ec =
            EvalContext(
                candle = candle,
                streams = mapOf("btc" to HubKey("BACKTEST", "BTCUSDT", "1m")),
                lets = emptyMap(),
                strategyContext =
                    testStrategyContext(
                        clock = com.qkt.common.FixedClock(time = noon),
                        tradeHistory = view,
                    ),
            )
        val v =
            ExprCompiler()
                .compile(StateAccessor(StateSource.POSITION_TRADES_TODAY, "btc"))
                .evaluate(ec) as Value.Num
        assertThat(v.v).isEqualByComparingTo("2")
    }

    @Test
    fun `POSITION_LAST_TRADE_AT returns Undefined when no fills on that symbol`() {
        val ec =
            EvalContext(
                candle = candle,
                streams = mapOf("btc" to HubKey("BACKTEST", "BTCUSDT", "1m")),
                lets = emptyMap(),
                strategyContext = testStrategyContext(),
            )
        val v = ExprCompiler().compile(StateAccessor(StateSource.POSITION_LAST_TRADE_AT, "btc")).evaluate(ec)
        assertThat(v).isEqualTo(Value.Undefined)
    }

    @Test
    fun `POSITION_LAST_TRADE_AT returns most recent fill timestamp on the stream's symbol`() {
        val history = com.qkt.pnl.TradeHistory()
        history.recordTrade("test", 100L, BigDecimal("10"), "BACKTEST:BTCUSDT")
        history.recordTrade("test", 200L, BigDecimal("-5"), "BACKTEST:ETHUSDT")
        history.recordTrade("test", 300L, BigDecimal("15"), "BACKTEST:BTCUSDT")
        val view = com.qkt.pnl.TradeHistoryViewImpl(history, "test")
        val ec =
            EvalContext(
                candle = candle,
                streams = mapOf("btc" to HubKey("BACKTEST", "BTCUSDT", "1m")),
                lets = emptyMap(),
                strategyContext = testStrategyContext(tradeHistory = view),
            )
        val v =
            ExprCompiler()
                .compile(StateAccessor(StateSource.POSITION_LAST_TRADE_AT, "btc"))
                .evaluate(ec) as Value.Num
        assertThat(v.v).isEqualByComparingTo("300")
    }

    @Test
    fun `OPEN_ORDERS returns the strategy entry count for the stream symbol`() {
        val ec =
            EvalContext(
                candle = candle,
                streams = mapOf("btc" to HubKey("BACKTEST", "BTCUSDT", "1m")),
                lets = emptyMap(),
                strategyContext =
                    testStrategyContext(
                        openOrders = OpenOrderView { symbol -> if (symbol == "BACKTEST:BTCUSDT") 2 else 0 },
                    ),
            )

        val value = ExprCompiler().compile(StateAccessor(StateSource.OPEN_ORDERS, "btc")).evaluate(ec) as Value.Num

        assertThat(value.v).isEqualByComparingTo("2")
    }
}
