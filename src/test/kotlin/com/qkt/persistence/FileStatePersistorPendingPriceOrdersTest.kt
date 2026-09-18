package com.qkt.persistence

import com.qkt.common.Side
import com.qkt.execution.OrderRequest
import com.qkt.execution.TimeInForce
import com.qkt.execution.TrailMode
import com.qkt.execution.TriggerType
import java.math.BigDecimal
import java.nio.file.Path
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class FileStatePersistorPendingPriceOrdersTest {
    @Test
    fun `Limit and Stop orders round-trip`(
        @TempDir tmp: Path,
    ) {
        val persistor = FileStatePersistor(tmp)
        val limit =
            OrderRequest.Limit(
                id = "c-1",
                symbol = "XAUUSDm",
                side = Side.BUY,
                quantity = BigDecimal("0.20"),
                limitPrice = BigDecimal("4695.5"),
                timeInForce = TimeInForce.GTC,
                timestamp = 1000L,
                strategyId = "hedge",
            )
        val stop =
            OrderRequest.Stop(
                id = "c-2",
                symbol = "XAUUSDm",
                side = Side.SELL,
                quantity = BigDecimal("0.20"),
                stopPrice = BigDecimal("4710.0"),
                timeInForce = TimeInForce.GTC,
                timestamp = 1000L,
                strategyId = "hedge",
            )
        persistor.savePendingOrders("hedge", mapOf("c-1" to limit, "c-2" to stop))
        val loaded = persistor.loadPendingOrders("hedge")
        assertThat(loaded["c-1"]).isEqualTo(limit)
        assertThat(loaded["c-2"]).isEqualTo(stop)
    }

    @Test
    fun `IfTouched with limit roundtrips`(
        @TempDir tmp: Path,
    ) {
        val persistor = FileStatePersistor(tmp)
        val it =
            OrderRequest.IfTouched(
                id = "c-3",
                symbol = "XAUUSDm",
                side = Side.BUY,
                quantity = BigDecimal("0.10"),
                triggerPrice = BigDecimal("4720"),
                onTrigger = TriggerType.LIMIT,
                limitPrice = BigDecimal("4721"),
                timeInForce = TimeInForce.GTC,
                timestamp = 1000L,
                strategyId = "hedge",
            )
        persistor.savePendingOrders("hedge", mapOf("c-3" to it))
        val loaded = persistor.loadPendingOrders("hedge")
        assertThat(loaded["c-3"]).isEqualTo(it)
    }

    @Test
    fun `StopLimit TrailingStop and TrailingStopLimit round-trip`(
        @TempDir tmp: Path,
    ) {
        val persistor = FileStatePersistor(tmp)
        val stopLimit =
            OrderRequest.StopLimit(
                id = "c-1",
                symbol = "XAUUSDm",
                side = Side.SELL,
                quantity = BigDecimal("0.20"),
                stopPrice = BigDecimal("4710.0"),
                limitPrice = BigDecimal("4709.0"),
                timeInForce = TimeInForce.GTC,
                timestamp = 1000L,
                strategyId = "hedge",
            )
        val trailingStop =
            OrderRequest.TrailingStop(
                id = "c-2",
                symbol = "XAUUSDm",
                side = Side.SELL,
                quantity = BigDecimal("0.20"),
                trailAmount = BigDecimal("15.0"),
                trailMode = TrailMode.ABSOLUTE,
                timeInForce = TimeInForce.GTC,
                timestamp = 1000L,
                strategyId = "hedge",
            )
        val trailingStopLimit =
            OrderRequest.TrailingStopLimit(
                id = "c-3",
                symbol = "XAUUSDm",
                side = Side.SELL,
                quantity = BigDecimal("0.20"),
                trailAmount = BigDecimal("2.5"),
                trailMode = TrailMode.PERCENT,
                limitOffset = BigDecimal("1.0"),
                timeInForce = TimeInForce.GTC,
                timestamp = 1000L,
                strategyId = "hedge",
            )
        persistor.savePendingOrders(
            "hedge",
            mapOf("c-1" to stopLimit, "c-2" to trailingStop, "c-3" to trailingStopLimit),
        )
        val loaded = persistor.loadPendingOrders("hedge")
        assertThat(loaded["c-1"]).isEqualTo(stopLimit)
        assertThat(loaded["c-2"]).isEqualTo(trailingStop)
        assertThat(loaded["c-3"]).isEqualTo(trailingStopLimit)
    }
}
