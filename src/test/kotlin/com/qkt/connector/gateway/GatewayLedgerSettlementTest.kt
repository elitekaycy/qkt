package com.qkt.connector.gateway

import com.qkt.events.BrokerEvent
import com.qkt.events.ContractSettled
import com.qkt.positions.Position
import com.qkt.positions.PositionProvider
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class GatewayLedgerSettlementTest {
    private val code = "BTC_USDC-9OCT26-82000-P"

    @Test
    fun `a settlement that cannot be read is not marked delivered, so a readable report of it still settles`() {
        val heard = mutableListOf<BrokerEvent>()
        val routing = GatewayRouting()
        routing.attach(
            GatewayRouting.Attached(
                null,
                object : PositionProvider {
                    override fun positionFor(symbol: String): Position? = null

                    override fun allPositions(): Map<String, Position> = emptyMap()
                },
            ) { heard += it },
        )
        val ledger = GatewayLedger(GatewaySymbols("DERIBIT:").apply { update(listOf(code)) }, routing, Any())

        assertThatThrownBy { ledger.onSettlement(WireSettlement(code, "not-a-price", 9)) }
            .isInstanceOf(GatewayProtocolException::class.java)
        ledger.onSettlement(WireSettlement(code, "1000", 9))
        ledger.onSettlement(WireSettlement(code, "1000", 9))

        assertThat(heard.filterIsInstance<ContractSettled>().map { it.price }).containsExactly(BigDecimal("1000"))
    }
}
