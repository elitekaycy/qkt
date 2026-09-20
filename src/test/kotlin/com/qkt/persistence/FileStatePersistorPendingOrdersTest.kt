package com.qkt.persistence

import com.qkt.common.Side
import com.qkt.execution.OrderRequest
import com.qkt.execution.TimeInForce
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
