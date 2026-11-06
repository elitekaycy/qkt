package com.qkt.app.order

import com.qkt.common.Side
import com.qkt.execution.ManagedOrder
import com.qkt.execution.OrderRequest
import com.qkt.execution.OrderState
import com.qkt.execution.StopLossSpec
import com.qkt.execution.TimeInForce
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class PendingExposureBookTest {
    private val book = OrderBook(isReferenced = { false }, reclaim = {})
    private val exposure = PendingExposureBook(book)

    private fun limit(
        id: String,
        side: Side = Side.BUY,
        symbol: String = "XAUUSD",
        strategyId: String = "alpha",
        quantity: String = "1",
    ) = OrderRequest.Limit(id, symbol, side, BigDecimal(quantity), BigDecimal("2000"), TimeInForce.GTC, 0L, strategyId)

    private fun seed() {
        exposure.register(limit("plain"))
        exposure.register(limit("oco-a", quantity = "2"), groupId = "g")
        exposure.register(limit("oco-b", symbol = "EURUSD"), groupId = "g")
        exposure.register(limit("short", side = Side.SELL))
        exposure.register(limit("other", strategyId = "beta"))
        exposure.register(limit("dead"))
        book.put(ManagedOrder("dead", limit("dead"), OrderState.CANCELLED, createdAt = 0L, lastUpdatedAt = 0L))
        exposure.register(limit("done"))
        exposure.recordFill("done", BigDecimal.ONE)
    }

    @Test
    fun `an oco counts once and terminal or filled entries do not count`() {
        seed()

        assertThat(exposure.orderCountFor(Side.BUY, "alpha")).isEqualTo(2)
        assertThat(exposure.orderCountFor(Side.BUY, null)).isEqualTo(3)
        assertThat(exposure.orderCountFor(Side.SELL, "alpha")).isEqualTo(1)
        assertThat(exposure.orderCountFor(Side.SELL, "beta")).isEqualTo(0)
    }

    @Test
    fun `symbols with unfilled entry quantity are listed per strategy`() {
        seed()

        assertThat(exposure.symbolsFor("alpha")).containsExactlyInAnyOrder("XAUUSD", "EURUSD")
        assertThat(exposure.symbolsFor("beta")).containsExactly("XAUUSD")
        assertThat(exposure.symbolsFor(null)).containsExactlyInAnyOrder("XAUUSD", "EURUSD")
        assertThat(exposure.symbolsFor("nobody")).isEmpty()
    }

    @Test
    fun `quantity sums ungrouped entries and the largest leg of each group`() {
        seed()
        exposure.register(limit("oco-c", quantity = "0.5"), groupId = "g")

        assertThat(exposure.quantityFor("XAUUSD", Side.BUY, "alpha")).isEqualByComparingTo("3")
        assertThat(exposure.quantityFor("XAUUSD", Side.BUY, null)).isEqualByComparingTo("4")
    }

    @Test
    fun `re-registering keeps the group and the filled quantity and removal returns the group`() {
        exposure.register(limit("leg", quantity = "2"), groupId = "g")
        exposure.recordFill("leg", BigDecimal("1.5"))
        exposure.recordFill("unknown", BigDecimal.ONE)

        exposure.register(limit("leg", quantity = "2"))

        assertThat(exposure.quantityFor("XAUUSD", Side.BUY, "alpha")).isEqualByComparingTo("0.5")
        assertThat("leg" in exposure).isTrue()
        assertThat(exposure.remove("leg")).isEqualTo("g")
        assertThat(exposure.remove("leg")).isNull()
        assertThat("leg" in exposure).isFalse()
    }

    @Test
    fun `the exposure of a composite is carried by its entry order`() {
        val entry = limit("entry", strategyId = "")
        val bracket =
            OrderRequest.Bracket(
                "br",
                "XAUUSD",
                Side.BUY,
                BigDecimal.ONE,
                entry,
                BigDecimal("2100"),
                StopLossSpec.Fixed(BigDecimal("1900")),
                TimeInForce.GTC,
                0L,
                "alpha",
            )
        val parent = limit("parent")
        val children = listOf(limit("c"))
        val oto = OrderRequest.OTO("oto", "XAUUSD", Side.BUY, BigDecimal.ONE, parent, children, TimeInForce.GTC, 0L)

        assertThat(exposureEntryRequest(bracket)).isEqualTo(entry.copy(strategyId = "alpha"))
        assertThat(exposureEntryRequest(oto)).isSameAs(parent)
        assertThat(exposureEntryRequest(parent)).isSameAs(parent)
    }
}
