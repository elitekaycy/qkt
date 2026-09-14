package com.qkt.execution

import com.qkt.common.Side
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class OrderRequestIdsTest {
    private fun market(id: String) =
        OrderRequest.Market(
            id = id,
            symbol = "EXNESS:XAUUSD",
            side = Side.BUY,
            quantity = BigDecimal("0.01"),
            timeInForce = TimeInForce.GTC,
            timestamp = 0L,
            strategyId = "s",
        )

    @Test
    fun `a bracket reports its own id and its entry's`() {
        val bracket =
            OrderRequest.Bracket(
                id = "dsl-s--1",
                symbol = "EXNESS:XAUUSD",
                side = Side.BUY,
                quantity = BigDecimal("0.01"),
                entry = market("dsl-s--0"),
                stopLoss = StopLossSpec.Fixed(BigDecimal("4200")),
                takeProfit = BigDecimal("4400"),
                timeInForce = TimeInForce.GTC,
                timestamp = 0L,
                strategyId = "s",
            )
        assertThat(bracket.allIds()).containsExactly("dsl-s--1", "dsl-s--0")
    }

    @Test
    fun `an OTO reports parent and children`() {
        val oto =
            OrderRequest.OTO(
                id = "dsl-s--5",
                symbol = "EXNESS:XAUUSD",
                side = Side.BUY,
                quantity = BigDecimal("0.01"),
                parent = market("dsl-s--5"),
                children = listOf(market("dsl-s--6"), market("dsl-s--7")),
                timeInForce = TimeInForce.GTC,
                timestamp = 0L,
                strategyId = "s",
            )
        assertThat(oto.allIds()).containsExactlyInAnyOrder("dsl-s--5", "dsl-s--6", "dsl-s--7")
    }
}
