package com.qkt.persistence

import com.qkt.common.Side
import com.qkt.execution.OrderRequest
import com.qkt.execution.StopLossSpec
import com.qkt.execution.TimeInForce
import com.qkt.execution.TrailMode
import com.qkt.execution.TriggerType
import java.math.BigDecimal
import java.nio.file.Files
import java.nio.file.Path
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
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
    fun `Market close intent round-trips without becoming an opposite entry`(
        @TempDir tmp: Path,
    ) {
        val persistor = FileStatePersistor(tmp)
        val close =
            OrderRequest.Market(
                id = "close-1",
                symbol = "EXNESS:EURUSD",
                side = Side.SELL,
                quantity = BigDecimal("0.06"),
                timeInForce = TimeInForce.GTC,
                timestamp = 1000L,
                strategyId = "hedge",
                closesTicket = "424242",
                closesLegId = "primary",
                partialClose = true,
            )

        persistor.savePendingOrders("hedge", mapOf(close.id to close))

        assertThat(persistor.loadPendingOrders("hedge")).containsExactlyEntriesOf(mapOf(close.id to close))
    }

    @Test
    fun `legacy Market intent without close fields loads with entry defaults`(
        @TempDir tmp: Path,
    ) {
        val strategyDir = tmp.resolve("hedge")
        Files.createDirectories(strategyDir)
        Files.writeString(
            strategyDir.resolve("pending-orders.json"),
            """{"version":1,"strategyId":"hedge","orders":[{"clientOrderId":"entry-1","request":{"type":"Market","id":"entry-1","symbol":"EXNESS:EURUSD","side":"BUY","quantity":"0.10","timeInForce":"GTC","timestamp":1000,"strategyId":"hedge"}}]}""",
        )

        val loaded = FileStatePersistor(tmp).loadPendingOrders("hedge").getValue("entry-1")

        assertThat(loaded).isEqualTo(
            OrderRequest.Market(
                id = "entry-1",
                symbol = "EXNESS:EURUSD",
                side = Side.BUY,
                quantity = BigDecimal("0.10"),
                timeInForce = TimeInForce.GTC,
                timestamp = 1000L,
                strategyId = "hedge",
            ),
        )
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

    @Test
    fun `Bracket variant round trips for pre-fill recovery`(
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
                stopLoss = StopLossSpec.Fixed(BigDecimal("4690")),
                timeInForce = TimeInForce.GTC,
                timestamp = 1000L,
                strategyId = "hedge",
            )
        persistor.savePendingOrders("hedge", mapOf("b-1" to bracket, "entry" to market))
        val loaded = persistor.loadPendingOrders("hedge")
        assertThat(loaded).hasSize(2)
        assertThat(loaded["entry"]).isEqualTo(market)
        assertThat(loaded["b-1"]).isEqualTo(bracket)
    }

    @Test
    fun `OTO wrapper round trips with its parent and unarmed children`(
        @TempDir tmp: Path,
    ) {
        val persistor = FileStatePersistor(tmp)
        val parent =
            OrderRequest.Limit(
                id = "parent",
                symbol = "XAUUSDm",
                side = Side.BUY,
                quantity = BigDecimal("0.10"),
                limitPrice = BigDecimal("4700"),
                timeInForce = TimeInForce.GTC,
                timestamp = 1000L,
                strategyId = "hedge",
            )
        val child =
            OrderRequest.Stop(
                id = "child",
                symbol = "XAUUSDm",
                side = Side.SELL,
                quantity = BigDecimal("0.10"),
                stopPrice = BigDecimal("4680"),
                timeInForce = TimeInForce.GTC,
                timestamp = 1000L,
                strategyId = "hedge",
            )
        val oto =
            OrderRequest.OTO(
                id = "oto",
                symbol = "XAUUSDm",
                side = Side.BUY,
                quantity = BigDecimal("0.10"),
                parent = parent,
                children = listOf(child),
                timeInForce = TimeInForce.GTC,
                timestamp = 1000L,
                strategyId = "hedge",
            )

        persistor.savePendingOrders("hedge", mapOf(parent.id to oto))

        assertThat(persistor.loadPendingOrders("hedge")).containsExactlyEntriesOf(mapOf(parent.id to oto))
    }

    @Test
    fun `loadPendingOrders returns empty when file missing`(
        @TempDir tmp: Path,
    ) {
        val persistor = FileStatePersistor(tmp)
        assertThat(persistor.loadPendingOrders("absent")).isEmpty()
    }

    @Test
    fun `loadPendingOrders rejects a version mismatch`(
        @TempDir tmp: Path,
    ) {
        val dir = tmp.resolve("hedge")
        Files.createDirectories(dir)
        Files.writeString(
            dir.resolve("pending-orders.json"),
            """{"version":99,"strategyId":"hedge","orders":[]}""",
        )
        val persistor = FileStatePersistor(tmp)
        assertThatThrownBy { persistor.loadPendingOrders("hedge") }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("schema mismatch")
    }
}
