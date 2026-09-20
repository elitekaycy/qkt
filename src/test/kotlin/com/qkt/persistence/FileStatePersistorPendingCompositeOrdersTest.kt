package com.qkt.persistence

import com.qkt.common.Side
import com.qkt.execution.OrderRequest
import com.qkt.execution.StopLossSpec
import com.qkt.execution.TimeInForce
import java.math.BigDecimal
import java.nio.file.Path
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class FileStatePersistorPendingCompositeOrdersTest {
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
}
