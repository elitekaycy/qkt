package com.qkt.observe.insights

import com.qkt.common.Side
import com.qkt.execution.OrderRequest
import com.qkt.execution.StopLossSpec
import com.qkt.execution.TimeInForce
import com.qkt.execution.TrailMode
import com.qkt.execution.TriggerType
import java.math.BigDecimal

/** One sample of every single-leg [OrderRequest] subtype, for telemetry payload coverage. */
internal object SingleLegOrderRequests {
    fun market(
        id: String = "m1",
        side: Side = Side.BUY,
    ) = OrderRequest.Market(
        id = id,
        symbol = "XAUUSD",
        side = side,
        quantity = BigDecimal("0.10"),
        timeInForce = TimeInForce.GTC,
        timestamp = 1718000000000L,
        strategyId = "latch",
    )

    fun samples(): List<OrderRequest> =
        listOf(
            market().copy(closesTicket = "ticket-1", closesLegId = "leg-1", partialClose = true),
            OrderRequest.Limit(
                "lim",
                "XAUUSD",
                Side.BUY,
                BigDecimal("0.10"),
                BigDecimal("2349.5"),
                TimeInForce.GTD,
                1L,
                "latch",
                2L,
            ),
            OrderRequest.Stop(
                "stop",
                "XAUUSD",
                Side.BUY,
                BigDecimal("0.10"),
                BigDecimal("2355"),
                TimeInForce.GTC,
                1L,
                "latch",
            ),
            OrderRequest.StopLimit(
                "slim",
                "XAUUSD",
                Side.BUY,
                BigDecimal("0.10"),
                BigDecimal("2355"),
                BigDecimal("2356"),
                TimeInForce.GTC,
                1L,
                "latch",
            ),
            OrderRequest.IfTouched(
                "touch",
                "XAUUSD",
                Side.SELL,
                BigDecimal("0.10"),
                BigDecimal("2360"),
                TriggerType.LIMIT,
                BigDecimal("2359"),
                TimeInForce.GTC,
                1L,
                "latch",
            ),
            OrderRequest.TrailingStop(
                "trail",
                "XAUUSD",
                Side.SELL,
                BigDecimal("0.10"),
                BigDecimal("10"),
                TrailMode.ABSOLUTE,
                TimeInForce.GTC,
                1L,
                "latch",
            ),
            OrderRequest.ArmedTrailingStop(
                "armed",
                "XAUUSD",
                Side.SELL,
                BigDecimal("0.10"),
                BigDecimal("2350"),
                BigDecimal("8"),
                BigDecimal("12"),
                TimeInForce.GTC,
                1L,
                "latch",
            ),
            OrderRequest.SteppedStop(
                id = "stepped",
                symbol = "XAUUSD",
                side = Side.SELL,
                quantity = BigDecimal("0.10"),
                entryPrice = BigDecimal("2350"),
                initialDistance = BigDecimal("10"),
                steps =
                    listOf(
                        StopLossSpec.Step(BigDecimal("12"), BigDecimal.ZERO),
                        StopLossSpec.Step(BigDecimal("20"), BigDecimal("5")),
                    ),
                timeInForce = TimeInForce.GTC,
                timestamp = 1L,
                strategyId = "latch",
            ),
            OrderRequest.TimeTighteningStop(
                id = "time-tightening",
                symbol = "XAUUSD",
                side = Side.SELL,
                quantity = BigDecimal("0.10"),
                entryPrice = BigDecimal("2350"),
                initialDistance = BigDecimal("10"),
                tightenBy = BigDecimal("2"),
                intervalMs = 5_000L,
                floorDistance = BigDecimal("4"),
                timeInForce = TimeInForce.GTC,
                timestamp = 1L,
                strategyId = "latch",
            ),
            OrderRequest.TrailingStopLimit(
                "tsl",
                "XAUUSD",
                Side.SELL,
                BigDecimal("0.10"),
                BigDecimal("1.5"),
                TrailMode.PERCENT,
                BigDecimal("0.2"),
                TimeInForce.GTC,
                1L,
                "latch",
            ),
        )
}
