package com.qkt.persistence

import com.qkt.common.Money
import com.qkt.common.Side
import com.qkt.execution.LegIntent
import com.qkt.execution.OrderRequest
import com.qkt.execution.StopLossSpec
import com.qkt.execution.TimeInForce
import com.qkt.execution.withLegIntent
import com.qkt.positions.LegRole
import java.nio.file.Path
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class FileStatePersistorLegIntentTest {
    private fun market(id: String) =
        OrderRequest.Market(
            id = id,
            symbol = "EURUSD",
            side = Side.BUY,
            quantity = Money.of("1"),
            timeInForce = TimeInForce.GTC,
            timestamp = 100L,
            strategyId = "s1",
        )

    @Test
    fun `every leg intent kind round-trips on a leaf`(
        @TempDir tmp: Path,
    ) {
        val persistor = FileStatePersistor(tmp)
        val intents =
            mapOf(
                "open" to LegIntent.Open("leg-a", LegRole.INDEPENDENT),
                "stack" to LegIntent.Open("tier-1", LegRole.STACK, parentLegId = "leg-a"),
                "close" to LegIntent.Close(legId = "leg-a", ticket = "42", partial = true),
                "net" to LegIntent.Net,
                "unplanned" to LegIntent.Unplanned,
            )
        val orders = intents.mapValues { (id, intent) -> market(id).withLegIntent(intent) }

        persistor.savePendingOrders("s1", orders)
        val loaded = persistor.loadPendingOrders("s1")

        for ((id, intent) in intents) {
            assertThat(loaded.getValue(id).legIntent).isEqualTo(intent)
        }
    }

    @Test
    fun `a bracket's entry intent survives the nested DTO`(
        @TempDir tmp: Path,
    ) {
        val persistor = FileStatePersistor(tmp)
        val bracket =
            OrderRequest
                .Bracket(
                    id = "b1",
                    symbol = "EURUSD",
                    side = Side.BUY,
                    quantity = Money.of("1"),
                    entry = market("b1-entry"),
                    takeProfit = Money.of("1.20"),
                    stopLoss = StopLossSpec.Fixed(Money.of("1.00")),
                    timeInForce = TimeInForce.GTC,
                    timestamp = 100L,
                    strategyId = "s1",
                ).withLegIntent(LegIntent.Open("b1", LegRole.INDEPENDENT))

        persistor.savePendingOrders("s1", mapOf("b1" to bracket))
        val back = persistor.loadPendingOrders("s1").getValue("b1") as OrderRequest.Bracket

        assertThat(back.entry.legIntent).isEqualTo(LegIntent.Open("b1", LegRole.INDEPENDENT))
        assertThat(back.legIntent).isEqualTo(LegIntent.Unplanned)
    }
}
