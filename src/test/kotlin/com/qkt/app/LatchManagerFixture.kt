package com.qkt.app

import com.qkt.common.FixedClock
import com.qkt.common.Side
import com.qkt.dsl.ast.LatchConfirm
import com.qkt.dsl.ast.LatchFirstTick
import com.qkt.dsl.compile.CompiledExpr
import com.qkt.dsl.compile.CompiledLatch
import com.qkt.dsl.compile.CompiledLatchEntry
import com.qkt.dsl.compile.EvalContext
import com.qkt.dsl.compile.HubKey
import com.qkt.dsl.compile.LatchEntryBuilder
import com.qkt.dsl.compile.Value
import com.qkt.execution.OrderRequest
import com.qkt.execution.TimeInForce
import com.qkt.marketdata.Candle
import com.qkt.marketdata.Tick
import com.qkt.strategy.testStrategyContext
import java.math.BigDecimal

object LatchManagerFixture {
    fun manager(
        emit: (OrderRequest) -> Unit,
        now: Long = 0L,
    ): LatchManager = LatchManager(FixedClock(time = now), emit)

    fun ec(symbol: String): EvalContext {
        val candle =
            Candle(
                symbol = symbol,
                open = BigDecimal("2000.00"),
                high = BigDecimal("2000.00"),
                low = BigDecimal("2000.00"),
                close = BigDecimal("2000.00"),
                volume = BigDecimal.ZERO,
                startTime = 0L,
                endTime = 1L,
            )
        return EvalContext(
            candle = candle,
            streams =
                mapOf(
                    "s" to HubKey("BACKTEST", symbol, "1m"),
                    "silver" to HubKey("BACKTEST", "XAGUSD", "1m"),
                ),
            lets = emptyMap(),
            strategyContext = testStrategyContext(),
        )
    }

    fun compiledLatch(
        ref: String,
        offset: String,
        windowMs: Long,
        confirm: LatchConfirm = LatchFirstTick,
    ): CompiledLatch {
        val refVal = BigDecimal(ref)
        val offVal = BigDecimal(offset)
        return CompiledLatch(
            streamAlias = "s",
            reference = CompiledExpr { Value.Num(refVal) },
            offset = CompiledExpr { Value.Num(offVal) },
            armWindowMs = windowMs,
            name = null,
            entries =
                listOf(
                    CompiledLatchEntry(
                        streamAlias = "s",
                        builder =
                            LatchEntryBuilder { direction, anchor, ec ->
                                val side = if (direction > 0) Side.BUY else Side.SELL
                                OrderRequest.Limit(
                                    id = "test-entry",
                                    symbol = ec.candle.symbol,
                                    side = side,
                                    quantity = BigDecimal.ONE,
                                    limitPrice = anchor,
                                    timeInForce = TimeInForce.GTC,
                                    timestamp = 0L,
                                    strategyId = "test",
                                )
                            },
                    ),
                ),
            confirm = confirm,
        )
    }

    fun candle(
        symbol: String,
        close: String,
        endTime: Long,
    ): Candle =
        Candle(
            symbol = symbol,
            open = BigDecimal(close),
            high = BigDecimal(close),
            low = BigDecimal(close),
            close = BigDecimal(close),
            volume = BigDecimal.ZERO,
            startTime = endTime - 60_000L,
            endTime = endTime,
        )

    fun tick(
        symbol: String,
        price: String,
        timestamp: Long,
    ): Tick = Tick(symbol = symbol, price = BigDecimal(price), timestamp = timestamp)
}
