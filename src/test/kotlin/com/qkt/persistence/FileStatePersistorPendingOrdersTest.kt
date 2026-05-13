package com.qkt.persistence

import com.qkt.common.Side
import com.qkt.execution.OrderRequest
import com.qkt.execution.TimeInForce
import com.qkt.execution.TriggerType
import java.math.BigDecimal
import java.nio.file.Files
import java.nio.file.Path
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class FileStatePersistorPendingOrdersTest {
    @Test
    fun `Market order round-trips`(
        @TempDir tmp: Path,
    ) {
        val persistor = FileStatePersistor(tmp)
        val market =
            OrderRequest.Market(
                id = "c-1",
                symbol = "XAUUSDm",
                side = Side.BUY,
                quantity = BigDecimal("0.20"),
                timeInForce = TimeInForce.GTC,
                timestamp = 1000L,
                strategyId = "hedge",
            )
        persistor.savePendingOrders("hedge", mapOf("c-1" to market))
        val loaded = persistor.loadPendingOrders("hedge")
        assertThat(loaded).hasSize(1)
        assertThat(loaded["c-1"]).isEqualTo(market)
    }

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
    fun `Bracket variant is silently skipped`(
        @TempDir tmp: Path,
    ) {
        val persistor = FileStatePersistor(tmp)
        val market =
            OrderRequest.Market(
                id = "entry",
                symbol = "XAUUSDm",
                side = Side.BUY,
                quantity = BigDecimal("0.20"),
                timeInForce = TimeInForce.GTC,
                timestamp = 1000L,
                strategyId = "hedge",
            )
        val bracket =
            OrderRequest.Bracket(
                id = "b-1",
                symbol = "XAUUSDm",
                side = Side.BUY,
                quantity = BigDecimal("0.20"),
                entry = market,
                takeProfit = BigDecimal("4720"),
                stopLoss = BigDecimal("4690"),
                timeInForce = TimeInForce.GTC,
                timestamp = 1000L,
                strategyId = "hedge",
            )
        // Bracket is skipped; market (the entry) is kept as a flat Market.
        persistor.savePendingOrders("hedge", mapOf("b-1" to bracket, "entry" to market))
        val loaded = persistor.loadPendingOrders("hedge")
        assertThat(loaded).hasSize(1)
        assertThat(loaded["entry"]).isEqualTo(market)
        assertThat(loaded).doesNotContainKey("b-1")
    }

    @Test
    fun `loadPendingOrders returns empty when file missing`(
        @TempDir tmp: Path,
    ) {
        val persistor = FileStatePersistor(tmp)
        assertThat(persistor.loadPendingOrders("absent")).isEmpty()
    }

    @Test
    fun `loadPendingOrders returns empty on version mismatch`(
        @TempDir tmp: Path,
    ) {
        val dir = tmp.resolve("hedge")
        Files.createDirectories(dir)
        Files.writeString(
            dir.resolve("pending-orders.json"),
            """{"version":99,"strategyId":"hedge","orders":[]}""",
        )
        val persistor = FileStatePersistor(tmp)
        assertThat(persistor.loadPendingOrders("hedge")).isEmpty()
    }
}
