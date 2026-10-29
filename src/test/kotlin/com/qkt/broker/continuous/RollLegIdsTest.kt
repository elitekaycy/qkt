package com.qkt.broker.continuous

import com.qkt.common.Side
import com.qkt.connector.gateway.GatewayClientIds
import com.qkt.execution.OrderRequest
import com.qkt.execution.TimeInForce
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class RollLegIdsTest {
    private val stream = "PAPER_DERIBIT:BTC_USDC@front"
    private val atMs = 1_793_260_800_000L

    @Test
    fun `the soak's longest leg fits the gateway's id limit`() {
        val leg =
            OrderRequest.Market(
                "${RollLegIds.base(stream, atMs, "soak_o14_roll")}:unwind",
                "PAPER_DERIBIT:BTC_USDC_30OCT26",
                Side.BUY,
                BigDecimal("0.001"),
                TimeInForce.GTC,
                atMs,
                "soak_o14_roll",
            )

        assertThat(GatewayClientIds.of(leg).length).isLessThanOrEqualTo(GatewayClientIds.MAX_LENGTH)
    }

    @Test
    fun `a leg prefix is short whatever the names`() {
        val long = "A".repeat(200)

        assertThat(RollLegIds.base("$long:$long@front", atMs, long)).hasSize("roll:$atMs:".length + 12)
    }

    @Test
    fun `a leg prefix is the same after a restart and differs per stream, roll and strategy`() {
        val base = RollLegIds.base(stream, atMs, "soak_o14_roll")

        assertThat(RollLegIds.base(stream, atMs, "soak_o14_roll")).isEqualTo(base)
        assertThat(
            setOf(
                base,
                RollLegIds.base("PAPER_DERIBIT:ETH_USDC@front", atMs, "soak_o14_roll"),
                RollLegIds.base(stream, atMs + 1, "soak_o14_roll"),
                RollLegIds.base(stream, atMs, "soak_o15_roll"),
            ),
        ).hasSize(4)
    }
}
