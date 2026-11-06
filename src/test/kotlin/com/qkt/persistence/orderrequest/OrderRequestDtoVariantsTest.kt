package com.qkt.persistence.orderrequest

import com.qkt.common.Side
import com.qkt.execution.ExpiryAction
import com.qkt.execution.OrderRequest
import com.qkt.execution.OrderRequestEvidenceStackRequest
import com.qkt.execution.TimeInForce
import java.math.BigDecimal
import java.time.Instant
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class OrderRequestDtoVariantsTest {
    private val market =
        OrderRequest.Market(
            id = "m",
            symbol = "XAUUSD",
            side = Side.BUY,
            quantity = BigDecimal("0.1"),
            timeInForce = TimeInForce.GTC,
            timestamp = 1L,
        )

    private val timeExit =
        OrderRequest.TimeExit(
            id = "t",
            symbol = "XAUUSD",
            side = Side.SELL,
            quantity = BigDecimal("0.1"),
            target = market,
            deadline = Instant.ofEpochMilli(5_000L),
            onExpiry = ExpiryAction.CANCEL,
            timeInForce = TimeInForce.GTC,
            timestamp = 1L,
        )

    private fun oto(
        parent: OrderRequest,
        children: List<OrderRequest>,
    ) = OrderRequest.OTO(
        id = "oto",
        symbol = "XAUUSD",
        side = Side.BUY,
        quantity = BigDecimal("0.1"),
        parent = parent,
        children = children,
        timeInForce = TimeInForce.GTC,
        timestamp = 1L,
    )

    @Test
    fun `a stack is persisted by its own file and has no flat dto`() {
        assertThat(OrderRequestDto.fromDomain(OrderRequestEvidenceStackRequest.stackRequest())).isNull()
    }

    @Test
    fun `an unknown persisted order type is rejected by name`() {
        val dto = OrderRequestDto.fromDomain(market)!!.copy(type = "Iceberg")

        assertThatThrownBy { dto.toDomain() }.hasMessageContaining("Unknown OrderRequest type in persisted state: Iceberg")
    }

    @Test
    fun `an oto whose parent or child cannot be persisted is refused`() {
        assertThatThrownBy { encodeOto(oto(timeExit, listOf(market))) }
            .hasMessageContaining("OTO oto parent TimeExit cannot be persisted")
        assertThatThrownBy { encodeOto(oto(market, listOf(timeExit))) }
            .hasMessageContaining("OTO oto child t (TimeExit) cannot be persisted")
    }
}
